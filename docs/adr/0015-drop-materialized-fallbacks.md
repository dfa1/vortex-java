# ADR 0015: Drop Materialized fallbacks once Lazy has shipped

- **Status:** Accepted
- **Date:** 2026-06-16
- **Deciders:** project maintainer
- **Supersedes:** —
- **Superseded by:** —
- **Related:** [ADR 0010 — Lazy decode for 1:1 transform encodings](0010-lazy-decode.md),
  [ADR 0012 — Zero-copy layout decoding: lazy Chunked / Dict](0012-zero-copy-layout-decoding.md)

## Context

ADR 0010 + 0012 established Lazy as the canonical decode shape for every
encoding where per-element transform is feasible. The reader still carries
the older eager paths for most of these encodings — they were left in
place as a safety net during the lazy rollout, gated only by tests.

The cost compounds:

- **Test matrix.** Every encoding test now exercises two output shapes,
  duplicated across unit + integration suites.
- **Decoder churn.** `ZigZag`, `FoR`, `ALP`, `RunEnd`, `Sparse`, `RLE`,
  `Dict`, `Chunked`, `VarBinView`, `DateTimeParts`, `DecimalByteParts`,
  `Decimal`, `Constant` each have a non-trivial materialized branch that
  must keep up with format changes — without ever firing in production.
- **Surprise factor for contributors.** New decoders default to the
  pattern they read in neighboring files; if the neighboring file still
  has a Materialized branch, the new encoding inherits the obsolete shape
  by mimicry.
- **Real-file fragility.** The per-column chunking misalignment fix in
  0.7.2 ([fix(reader): align per-column chunking via shared decode +
  sliced views](https://github.com/dfa1/vortex-java/commit/5df3d9a9)) had
  to consult both the Lazy and Materialized output shapes when wiring the
  `Offset*Array` slice path. One shape would have been enough.

## Decision

When an encoding ships a Lazy variant that meets the criteria below, the
Materialized fallback is **removed in the same PR or in a follow-up PR
within one release cycle**. Reverts are allowed if a regression appears;
the policy is "stop carrying two shapes by default", not "never go back".

### Removal criteria

A Materialized branch may be deleted when **all** of these hold:

1. The Lazy variant is the default for every public read path
   (`ScanIterator`, `Chunk.column`, `decodeFlatSegment`).
2. Every integration test that asserts row values passes against the Lazy
   shape — including the Rust round-trip suite
   (`RustWritesJavaReadsIntegrationTest`,
   `RustJavaReaderComparisonIntegrationTest`).
3. The encoding's `Decode shape` row in
   [`docs/compatibility.md`](../compatibility.md) reads `Lazy / Lazy`.
4. At least one production-shaped workload has decoded the encoding via
   the Lazy path — the NYC Yellow Taxi fixture set is the canonical
   stand-in until a broader corpus exists.

### What gets removed

- The eager output path inside `decode(DecodeContext)` (typically the
  `ctx.arena().allocate(...)` + per-row loop + `Materialized*Array`
  return).
- The matching test cases that pin the eager shape (`Materialized*Array`
  instance-of assertions, byte-level segment comparisons that only the
  eager path produces).
- Helper methods that exist only to support the eager path (e.g.
  `applyReference`, per-ptype materializers in
  `FrameOfReferenceEncodingDecoder`).

### What stays

- Decompression-style encodings — `bitpacked`, `pco`, `zstd`, `fsst`,
  `delta`, `patched` — keep their Materialized output. ADR 0010 §
  "Decompression encodings stay eager" applies; ADR 0015 does not change
  it.
- Materialization fallbacks inside `ArraySegments.of(arr, arena)` stay —
  they exist for callers that explicitly request a `MemorySegment` from a
  Lazy array, not for default decode.

## Consequences

### Positive

- One shape per encoding to read, test, and document.
- `docs/compatibility.md` becomes the single source of truth for shape
  decisions; the code matches.
- New decoders inherit the Lazy pattern by default.

### Negative

- Loss of an in-tree A/B comparison point. Mitigation: keep the
  Materialized path live in benchmarks only, never in production decode.
- Encoding-specific micro-optimizations that the eager path enabled
  (e.g. SIMD-friendly `applyReference` in FoR) need to migrate to the
  Lazy path or to a `materializeXxx` helper in `ArraySegments`.

## Rollout

- **0.7.x:** policy ratified, no removals yet.
- **0.8.0:** eager paths removed from `ZigZag` (all PTypes + broadcast),
  `FoR` (all integer PTypes), `ALP` (broadcast-without-patches),
  `Constant` (Decimal → `LazyConstantDecimalArray`; `DecimalArray` interface
  introduced to replace two concrete `Array` permits entries),
  `RunEnd` (Bool → `LazyRunEndBoolArray`), `Sparse` (Bool → `LazySparseBoolArray`),
  `RLE` (validity → `OffsetBoolArray`; empty → `LazyConstantXxxArray`).
  `Dict` encoding-level path intentionally kept eager (layout-level path is lazy via ADR 0012).
  `DateTimeParts`, `DecimalByteParts`, `Decimal`, `VarBinView`, `Chunked` were already
  fully lazy before this sweep.
- **0.9.0:** revisit Decompression encodings — if a Lazy variant lands
  (e.g. window-decoded `Pco`) the same criteria apply.

## Notes

- Removal PRs must be one encoding per commit so a regression can be
  reverted in isolation.
- After each removal, refresh the encoding's row in
  `docs/compatibility.md` and the ADR 0010 / 0012 status header if
  applicable.
