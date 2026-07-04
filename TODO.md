# TODO

## Project

- [ ] Move project to a dedicated organization
- [ ] Create website
   - build something like hardwood.dev but for vortex files

## Performance

- [ ] **Benchmark publishing** — drop CI workflow, add `bench-publish` script; see [ADR-0006](adr/0006-benchmark-publishing.md).
- [ ] Performance tests must be peer-reviewed
- [ ] Run performance tests on other machines (I have access only to Apple M5)
- [ ] **Vector API adoption** — deferred; see [ADR-0005](adr/0005-vector-api-adoption.md) for adoption criteria and candidate loops.

## Security

**Contract:** the reader memory-maps and parses untrusted binary input. Every malformed input must
throw `VortexException`, never `ArrayIndexOutOfBoundsException`, `NegativeArraySizeException`,
`OutOfMemoryError`, `StackOverflowError`, a raw FlatBuffer runtime exception, or a Protobuf
parser exception. Each entry below is either a known gap, a contract audit, or supporting infra.

### Per-encoding adversarial tests

Each encoding's `decode(DecodeContext)` should be exercised against:
- `bufferIndices[i] >= ctx.bufferCount()` → centralize check in `DecodeContext.buffer(i)`.
- Crafted metadata that decodes but disagrees with the buffer payload.

Per-encoding gotchas:
- [ ] **VarBin**: offsets non-monotonic, negative, past data-buffer length.
- [ ] **Dict**: `codes[i] >= values.length`; `codes` ptype declared u8 but values count > 256.
- [ ] **Bitpacked**: `bit_width < 0 || > 64`; `packed_len < n * bit_width / 8`.
- [ ] **ALP**: `dim < 0`, `f_or_d` byte out of enum range; `exceptions_count > n`.
- [ ] **Sparse**: indices non-sorted or `indices[i] >= length`; values count
  mismatches indices count.
- [ ] **Chunked**: zero children with non-zero `row_count`; child layout self-referencing
  (already protected by depth limit, but add explicit test).
- [ ] **Struct**: `fieldNames.size() != children.size()`; field name UTF-8 invalid.
- [ ] **RLE / RunEnd**: `run_ends` non-monotonic; last `run_end` ≠ `row_count`.
- [ ] **Constant**: protobuf scalar value missing or type-mismatched against declared `DType`.
- [ ] **Zoned**: zone-map min > max; zone count ≠ child chunk count.
- [ ] **Pco**: `bits_per_offset > 64`; `bin_count == 0` with non-empty page; per-page
  `n` greater than `DEFAULT_MAX_PAGE_N`; ANS state values inconsistent with weight table.

### Resource caps

- [ ] **Implement `ResourceLimits` + `ReadOptions`** — see [ADR-0004](adr/0004-resource-caps-read-options.md) for design, defaults, and enforcement points. Also covers Pco page/bin caps.

### Fuzz infrastructure

- [ ] **Jazzer + JUnit 5** — add `com.code-intelligence:jazzer-junit` test dep. Two modes:
  regression (`./mvnw test`, replays saved corpus + crashes) and fuzz
  (`JAZZER_FUZZ=1`, nightly profile). See research notes in branch
  `worktree-security-fuzz` commit history.
- [ ] **Seed corpus from integration fixtures** — drop existing `.vortex` test files into
  `reader/src/test/resources/fuzz-corpus/full-file/`. Per-encoding sub-corpora extracted via
  a small tool that walks fixtures and dumps each segment to
  `core/src/test/resources/fuzz-corpus/<encoding>/`.
- [ ] **Fuzz targets**: `VortexReader.open(byte[])`, `PostscriptParser.parseBlobs`, and one
  `@FuzzTest` per encoding `Encoding.decode`. Crash oracle: `ignore = {VortexException.class}`.
- [ ] **Differential fuzz (Java vs Rust)** — round-trip random bytes through Java decode
  and `vortex-jni`; assert both throw or both return identical row count + values. Reuse
  `RustWritesJavaReadsIntegrationTest` harness.
- [ ] **OSS-Fuzz submission** — Jazzer is a first-class OSS-Fuzz engine; submit the project
  once the corpus + targets stabilize. Free continuous fuzzing.

## Build

- [ ] use JPMS, watch out for "dfa1" in package name
- [ ] **Docs need a compiler** — living prose (CLAUDE.md, `docs/*.md`) drifts from code with no
  gate (2026-07-04 found: phantom `register(ExtensionDecoder)`, stale "not yet implemented" on
  MASKED/PATCHED, dead service-file path, stale FQNs after package moves). Tiered plan:
  1. Markdown link checker in CI (relative links; the `docs/adr` → `adr/` move rewrote ~40 by hand).
  2. `DocsConsistencyTest` (integration module): extract backticked FQNs / `Class#member` refs /
     `META-INF/services` paths from living docs, assert existence via reflection. Historical files
     (`adr/`, released CHANGELOG sections) exempt by policy.
  3. Golden-test or generate enumerable tables (encodings/extensions/layout ids in
     `docs/reference.md`, `docs/compatibility.md`) from `EncodingId.WellKnown`/`LayoutId.WellKnown`/
     service manifests — a declared capability the code lacks is a bug, in both directions.
  4. Compile ` ```java ` blocks from living docs (defer until 1–3 pay rent).
  Plus: standing stale-docs dimension in the vortex-reviewer agent (grep living docs for
  identifiers a diff renames/moves/deletes).

## Tooling

- [ ] Optional `vortex-arrow` bridge module for Arrow ecosystem interop — see [ADR-0016](adr/0016-vortex-arrow-bridge.md)

## API

- [ ] **Error messages — structural sanitization of `VortexException`** —
  Phase E (bounds typing via `IoBounds`) shipped; remaining is Phases A–D (the `Sanitize`
  helper + `VortexError` catalog). See [ADR-0003](adr/0003-vortex-exception-sanitization.md)
  for design and phasing.
- [ ] Use domain primitives (`UInt32`, `UInt64`, etc.) as value classes via Project Valhalla instead of raw `long`/`int`
    - See [ADR-0008](adr/0008-domain-primitives-unsigned-integers.md) and https://dfa1.github.io/articles/rethink-domain-primitives-with-valhalla
    - Candidates: `PType` integer kinds, buffer offsets, row indices, byte lengths
    - Goal: type-safety at zero cost (value class = no heap alloc, no boxing)

## Compute

- [ ] **Compute primitives — encoded-domain specialization & façade** — the remaining ADR-0013
  follow-ups now the fused kernels have shipped. See [ADR-0013](adr/0013-compute-primitives.md).
  Done: §4 `Predicate`; §5 `RowFilter` unified over `Predicate`; §6 zone-map aggregate push-down in
  both tiers — the whole-zone `ZoneReducer` fold wired into `VortexAggregatePushDownRule` (rewrites a
  whole-table `MIN`/`MAX`/`COUNT`/`SUM`/`AVG` to a single-row `Values`, auto-registered over a bare
  `jdbc:calcite:` connection), plus the boundary/residual tier so a `SUM`/`MIN`/`MAX`/`COUNT` with a
  `WHERE` folds fully-selected zones from stats and decodes only the straddling boundary chunks via
  the fused `Compute.filteredAggregate`. §1–§3 (`Mask`, the `FilterKernel`/`MapKernel`/`ReduceKernel`
  interfaces) were built then removed for the fused single-pass kernels — no intermediate bitmap.
  Encoded-domain value specialization was measured as a no-win (decode is dispatch-bound, see
  `forLoopDictEncoded`); the real dict lever — the monomorphic code-segment scan — shipped as the
  `DictFilter` lane in both kernels, including multi-leaf `AND`s (the dict leaf drives the scan,
  residual leaves tested per match). Multi-fork numbers: `fusedFilteredSumDict` 762 → 38 ms/op
  ≈ 20×; `fusedFilteredAggregateDict` 983 → 46 ms/op ≈ 22×; `fusedFilteredAggregateMulti`
  (2-leaf `AND` × 2 aggregates) 2269 → 201 ms/op ≈ 11×.
  Next: the columnar transducer façade — [ADR-0019](adr/0019-columnar-transducer-facade.md)
  drafted (Proposed): declarative column-bound stages compiled to one fused pass; the remaining
  measured lever is the multi-aggregate single scan (≈ 2×) plus composition ergonomics for the
  Calcite boundary tier; review, then implement.

## Encodings

See [docs/compatibility.md](docs/compatibility.md) for the full encoding support table and S3 fixture status.

