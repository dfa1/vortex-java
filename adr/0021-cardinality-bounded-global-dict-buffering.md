# ADR 0021: Cardinality-bounded buffering for global dictionary candidates

- **Status:** Proposed
- **Date:** 2026-07-18
- **Deciders:** project maintainer
- **Supersedes:** —
- **Superseded by:** —
- **Related:** [ADR 0009 — Write API ergonomics](0009-write-api-ergonomics.md)

## Context

`VortexWriter` builds one shared dictionary per low-cardinality column across
every chunk, instead of re-emitting a per-chunk dictionary — closing the file
size gap to the Rust reference on real-world nullable/categorical columns
(commit `5fe8b544`). Because the writer's `writeChunk` API is streaming (a
caller pushes row batches incrementally; the writer never sees the whole
column up front — see ADR 0009), a shared dictionary can only be built once
every chunk has been seen. Until then, a candidate column's **raw values**
are buffered from its first chunk until `close()`.

This raw buffering has no bound proportional to what actually matters. A
column is admitted as a dict candidate based on its first chunk's cardinality
(`GLOBAL_DICT_MAX_CARDINALITY = 2048`), but nothing re-checks that gate as
more chunks arrive — a column whose distinct set only grows past the cap
after millions of later rows keeps accumulating raw duplicate values in the
meantime. On a wide, high-row-count file (18.5M rows × 38 string columns,
the NYC 311 slug from the Raincloud conformance corpus) the aggregate raw
buffering reached several GB and threw `OutOfMemoryError`, independent of
available heap — memory scaled with file size × column count, not with a
bounded quantity.

The immediate fix (`fix: bound VortexWriter's global-dict retained memory to
avoid OOM on huge files`) added an aggregate **byte** budget across all
buffering candidates (`WriteOptions#globalDictMaxRetainedBytes`, default
256 MB, since made configurable). When the running total crosses the budget,
the largest-retained columns are demoted to per-chunk encoding until back
under it. This stopped the OOM, but has two real costs:

1. **Wrong eviction signal.** Byte size is not the same as dict-worthiness.
   A huge column with genuinely tiny cardinality (exactly the case a shared
   dictionary helps most) is evicted *first* under this policy, purely
   because it is the biggest byte contributor — not because it stopped being
   a good candidate. Measured on the NYC 311 file: every one of its 38
   string columns was demoted under the 256 MB default, costing 1.67× the
   Rust reference's file size, because the aggregate raw-byte total for that
   many wide columns crosses 256 MB almost immediately regardless of any
   individual column's actual cardinality.
2. **No principled default.** The budget is a workload-shaped guess users
   must tune per host/file (`withGlobalDictMaxRetainedBytes`); there is no
   value that is simultaneously safe on constrained hosts and lossless on
   wide, low-cardinality files.

The Rust reference does not solve this the same way — its compressor
(`vortex-compressor/src/scheme/estimate.rs`) estimates a scheme's
compression ratio by compressing a **sample**, not by buffering full raw
data, because its compressor operates on arrays it already holds in memory
rather than accepting a caller's incremental row-batch stream. There is no
single heuristic to port directly; the fix has to fit our own streaming
constraint.

## Decision

Replace raw-value buffering with **cardinality-bounded** buffering:

1. Per dict-candidate column, maintain a deduplicated `value → code` map
   incrementally as chunks stream in, capped at `GLOBAL_DICT_MAX_CARDINALITY`
   entries. The moment inserting a new distinct value would exceed the cap,
   demote immediately — this is the same, already-correct disqualifying
   condition the admission gate uses, just checked continuously instead of
   once.
2. Buffer per-chunk **code arrays** (`U16`, or `U32` past 65 536 distinct
   values) instead of raw value arrays. Codes are cheap regardless of file
   size — 18.5M rows × 2 bytes ≈ 36 MB, versus potentially many GB of raw
   duplicated strings today.
3. At `close()`, build the dictionary payload directly from the
   already-deduplicated map (no re-scan of raw data needed) and write the
   buffered code arrays as the column's chunked codes segment.

Memory for a surviving candidate is then bounded by
`cardinality × avg_value_size + row_count × code_width` — proportional to
the *actual* quantities that make a dictionary worthwhile, not to raw file
size. A column only gets demoted when its real cardinality disqualifies it,
never because of its byte footprint alone.

`WriteOptions#globalDictMaxRetainedBytes` stays as a secondary safety net
(the code-array total across many wide columns is still technically
unbounded by row count, just far smaller in practice) but stops being the
primary demotion signal.

## Consequences

### Positive

- Correct-by-construction memory bound tied to configured cardinality, not a
  workload-dependent byte guess.
- Demotion order now matches dict-worthiness: a column is only evicted when
  its cardinality actually disqualifies it, never for being byte-heavy while
  genuinely low-cardinality.
- Removes the NYC-311-shaped regression (1.67× file size vs. Rust) without
  needing a larger configured budget — every column that is genuinely
  low-cardinality keeps its shared dictionary regardless of row count.
- `close()`-time dictionary construction gets simpler (dedup map is already
  the dictionary; no re-scan of buffered raw chunks to compute codes).

### Negative

- Real rework of the buffering path: admission, per-chunk ingest, and the
  `close()`-time build all change. Bigger than the byte-budget PRs it
  supersedes.
- Chunks buffered before a column's cardinality was known to be growing
  toward the cap need their already-computed codes kept consistent if a
  later demotion discards the shared dictionary — codes computed against a
  dictionary that turns out incomplete must be convertible back to raw
  per-chunk encoding, or the column must re-derive per-chunk encoding from
  scratch for its already-buffered chunks. The exact mechanics need to be
  worked out during implementation, not assumed here.
- `U16` vs `U32` code width must be decided per column before any chunk is
  written (or upgraded in place if cardinality crosses 65 536 while staying
  under `GLOBAL_DICT_MAX_CARDINALITY`, if that constant is ever raised past
  a `U16` code's range) — matches the existing narrowest-ptype-selection
  precedent (`PType.narrowestUnsigned`) but the growing-cardinality writer
  path is a different call site.

### Risks to manage

- `GLOBAL_DICT_MAX_CARDINALITY` currently caps at 2048 (fits `U16` easily);
  if it is ever raised, code-width selection needs to stay correct.
- Must not regress the existing demotion test coverage
  (`GlobalDictUtf8Test#retainedBytesBudgetExceeded_utf8_demotesToPerChunkChunkedLayout`)
  — cardinality-based demotion needs its own equivalent test, and the
  byte-budget test stays relevant as the secondary safety net's coverage.

## Alternatives considered

- **Keep the byte-budget heuristic as-is (status quo).** Simple, already
  shipped, and configurable. Rejected as the long-term design because the
  eviction signal is wrong (see Context) and there is no single default that
  works across host memory sizes and file widths — a real fix should not
  need per-workload tuning to get correct behavior on a file shape this
  common (many wide categorical columns).
- **Reservoir-sample values for cardinality estimation only, still buffer
  raw data for the final dictionary build.** Bounds the *estimation* step
  cheaply, but does not solve the actual memory cost — building the real
  dictionary still needs the full distinct set and per-row codes, which the
  cardinality-bounded map already gives directly and exactly (no sampling
  error, no separate estimation pass).
- **Two-pass write: require the whole column before writing (matching how
  the Rust reference's compressor operates).** Would let the writer measure
  exact cardinality with zero buffering complexity, but breaks the streaming
  `writeChunk` API's ergonomics (ADR 0009) — callers would have to hand the
  writer a fully materialized column instead of streaming batches, a much
  larger and unrelated behavior change.

## References

- OOM fix: `fix: bound VortexWriter's global-dict retained memory to avoid
  OOM on huge files`
- Configurability follow-up:
  `feat: make writer's global-dict retained-memory budget configurable`
- Rust reference sample-based estimation:
  [`vortex-compressor/src/scheme/estimate.rs`](https://github.com/spiraldb/vortex/blob/main/vortex-compressor/src/scheme/estimate.rs)
- Original global-dict-across-chunks fix: commit `5fe8b544`
