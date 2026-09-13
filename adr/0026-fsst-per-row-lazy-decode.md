# ADR 0026: FSST per-row lazy decode

- **Status:** Accepted
- **Date:** 2026-09-13
- **Deciders:** project maintainer
- **Supersedes:** —
- **Superseded by:** —
- **Related:** [ADR 0010 — Lazy decode for 1:1 transform encodings](0010-lazy-decode.md),
  [ADR 0022 — Extract FSST into a standalone module](0022-fsst-module-extraction.md)

## Context

ADR 0010 grouped `Fsst` with `Bitpacked`, `Pco`, and `Zstd` as encodings that "must remain eager":
*"their output shape differs from their input (compact compressed bytes → wider element array), so
element-at-i requires unpacking a window."* That reasoning holds for the other three — a bitpacked
value, a Pco block, and a Zstd frame all require decoding a run of neighboring elements to recover
one. It does not hold for FSST.

FSST's wire shape already carries two children that make row `i` independent of every other row:
an uncompressed-lengths child (`n` elements, one decoded byte count per row) and a codes-offsets
child (`n + 1` elements, `codesOffsets[i] .. codesOffsets[i + 1]` is row `i`'s compressed code
range). Nothing about decoding row 5 touches row 4's or row 6's bytes. `FsstEncodingDecoder.decode()`
nonetheless decompressed the entire column into one flat buffer up front, regardless of which rows
(if any) a caller went on to read — the exact waste ADR 0010's motivating table catalogs for
`WHERE` filters, projections that drop the column, and `LIMIT`/`take` slices.

Two observations sharpen this:

1. **Lengths need no decompression at all.** The uncompressed-lengths child already states each
   row's decoded byte count directly. `JavaVsJniFsstBenchmark.javaFsstDecode` — which scans the
   whole column and sums `forEachByteLength` — was paying the full decompression cost for a query
   the wire format could answer without running the FSST algorithm even once.
2. **A single row decodes in isolation.** `Decompressor.decompress(MemorySegment, long, long,
   MemorySegment, long)` already takes an arbitrary `[start, end)` code range; the existing eager
   path called it in row-batches purely to avoid an OSR mega-loop over the whole chunk (see the
   removed `ROWS_PER_DECODE_BATCH` comment), not because decoding required more than one row's
   context.

## Decision

`FsstEncodingDecoder.decode()` no longer decompresses. It builds a `Decompressor` from the wire
symbol table (unchanged — at most 255 symbols, negligible cost) and returns a
`LazyFsstVarBinArray` holding that decompressor plus the still-compressed code stream and the two
per-row children:

```java
return new LazyFsstVarBinArray(ctx.dtype(), n, decompressor, compressedBytes,
        uncompLensSeg, uncompLenPType, codesOffsetsSeg, codesOffPType);
```

`LazyFsstVarBinArray implements VarBinArray` (`reader.array`, the same package as every other
`VarBin*Array`):

- **`getByteLength(i)`** reads the uncompressed-lengths child directly — no decompression.
- **`forEachByteLength(c)`** walks the same child in a branch-split, per-ptype loop (CLAUDE.md
  hot-loop rule) — still no decompression, and no per-row cross-validation (matching
  `VarBinOffsetArray.forEachByteLength`'s existing "bulk walk trusts the data" convention).
- **`getBytes(i)` / `getString(i)`** decompress *only* row `i`'s code range, on that call, into a
  freshly sized scratch array.
- **`bytesSegment()`** returns the `MemorySegment.NULL` sentinel and **`segmentIfPresent()`**
  returns empty — the established convention for every other non-contiguous `VarBinArray`
  (`VarBinChunkedArray`, `VarBinRunEndArray`, `VarBinSparseArray`, `VarBinConstantArray`). A caller
  that needs the flat bytes-plus-offsets shape gets it from `VarBinArray.toOffsetMode`, which walks
  every row through `getBytes` — i.e., decodes the whole column exactly once, only when actually
  asked to.

### Validation moves from decode-time to access-time

The old eager path validated the whole column up front: a prefix-sum pass over the uncompressed
lengths, a first/last code-offset bounds check, and a post-decode "did the batch produce exactly
the claimed byte count" comparison. All of that was `O(rows)` or `O(bytes)` work — exactly the cost
this ADR removes from `decode()`.

Per-row validation instead happens inside the accessors, mirroring
`VarBinArrays.checkedLength`'s existing rationale ("offsets arrive from an untrusted file and are
deliberately not scanned at decode time"):

- **`getByteLength(i)`** cross-checks the claimed length against the maximum a decode of that row's
  code range could produce (FSST's own bound: at most 8 output bytes per compressed byte — see
  `Decompressor`'s "unconditional 8-byte store" javadoc). This is `O(1)` — no decompression — yet it
  still rejects a claimed length like "1.5 GB from a 1-byte code range" without ever running the
  decompressor.
- **`getBytes(i)`** sizes its scratch buffer from that same bound (never from the untrusted claimed
  length), decompresses, and only then compares the actual decoded byte count against the claim —
  a strictly *more* precise version of the old aggregate check, now scoped to the one row that was
  actually read.

Only one guard remains eager: `n >= Integer.MAX_VALUE` is still rejected in `decode()`, since it
bounds the codes-offsets child's own `n + 1`-element decode (`ctx.decodeChildSegment`), which
`decode()` always performs regardless of laziness.

## Consequences

### Positive

- **Filter/projection/take pushdown works for FSST columns for free.** A column requested but never
  read costs nothing; a filter that rejects 99% of rows only ever decompresses the 1% it keeps.
- **`forEachByteLength`-shaped aggregations (cardinality checks, length histograms, `LIMIT`
  planning) cost zero decompression.** `JavaVsJniFsstBenchmark.javaFsstDecode` goes from "decompress
  everything, then sum lengths already known" to "read the lengths child directly."
- **Per-row validation is strictly more precise than the old aggregate check**, since a per-row
  mismatch is caught at the row it occurs in rather than only detectable in aggregate.
- **ADR 0010's blanket "decompression-style encodings stay eager" rule gets a documented exception**
  for the one member of that group whose rows are independently addressable in the wire format.

### Negative

- **A full-column scan that reads every row's bytes now issues one `Decompressor.decompress` call
  per row instead of one call per 256-row batch.** The `ROWS_PER_DECODE_BATCH` batching existed to
  keep the decode loop out of OSR compilation, not to amortize per-call overhead across rows; this
  ADR does not re-measure whether per-row calls reintroduce that OSR cost at full-scan scale — no
  benchmark in this codebase exercises full-column `getString`/`getBytes` for FSST today (the only
  FSST throughput benchmark, `javaFsstDecode`, uses `forEachByteLength`, which never decompresses in
  either the old or new design). A future benchmark that does full-column FSST string materialization
  should be added before relying on this path's throughput.
- **Two heap allocations per accessed row** (`getBytes`'s scratch array plus the trimmed copy) where
  the old path allocated once for the whole batch. Acceptable for the row-at-a-time access pattern
  this ADR targets; would need revisiting if profiling shows it dominates a hot path.

### Risks to manage

- **A corrupted length that happens to fit the code-range bound still passes `getByteLength`.**
  E.g. a code range that could produce up to 8 bytes but a claimed length of 5 when the true decode
  produces 2 — `getByteLength` cannot detect this (it has no way to decode without paying the cost
  it exists to avoid); only `getBytes`/`getString` catch it, by comparing the actual decode output.
  Callers that call `getByteLength` alone (e.g. `forEachByteLength`-style aggregations) do not get
  this stronger guarantee — consistent with `VarBinOffsetArray`'s existing lenient bulk-walk
  behavior, but worth remembering when reasoning about what a length-only scan has actually verified.

## Alternatives considered

### A — Keep eager decode, skip only when `forEachByteLength` is the sole call site

Special-case `forEachByteLength` to read the lengths child directly while leaving `getBytes`/
`getString` behind an eager `decode()`.

Rejected: still pays full decompression for every filtered-out or unprojected row whenever any row
is read as a string — the dominant win (filter/take pushdown) requires per-row laziness, not just a
length-only fast path.

### B — Batch decode lazily in windows (e.g. re-run the old 256-row batching, triggered on first
access to any row in the window)

Would preserve the batching that avoided OSR compilation while still deferring work for untouched
windows.

Rejected as unnecessary complexity for this change: the per-row `Decompressor.decompress` call is
already a short, non-mega loop by construction (one row's codes), so there is no OSR concern to
re-solve. If full-column-scan benchmarking (see Consequences → Negative) later shows per-row call
overhead dominates, revisit with real numbers rather than pre-optimizing here.

## References

- `Decompressor.decompress(MemorySegment, long, long, MemorySegment, long)` — the per-range decode
  primitive this ADR calls once per row instead of once per batch.
- `LazyFsstVarBinArray` (`reader.array`) — the lazy implementation.
- `VarBinArrays.checkedLength` — the "don't scan offsets at decode time" convention this ADR extends
  to FSST's length/offset children.
- [ADR 0010](0010-lazy-decode.md) — the original lazy-decode framework; this ADR narrows its
  "decompression encodings stay eager" exclusion to exclude FSST specifically.
