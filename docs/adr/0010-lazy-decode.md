# ADR 0010: Lazy decode for 1:1 transform encodings

- **Status:** Proposed
- **Date:** 2026-06-13
- **Deciders:** project maintainer
- **Supersedes:** —
- **Superseded by:** —
- **Related:** [ADR 0005 — Vector API adoption](0005-vector-api-adoption.md),
  [CLAUDE.md §Memory model](../../CLAUDE.md)

## Context

Today every encoding decoder is **eager**. `AlpEncodingDecoder.decode()`
walks all `n` rows, computes `(double) src[i] * scale`, writes the result
into a fresh `MemorySegment`, and returns a `DoubleArray` backed by that
materialized buffer. `FrameOfReferenceEncodingDecoder` does the same with
`+ ref`. `ZigZagEncodingDecoder` does the same with `(u >>> 1) ^ -(u & 1)`.

The current `RustVsJavaReadBenchmark.javaReadClose` reads every decoded
value via `close.fold(0.0, Double::sum)`. The fold touches every row, so
eager decode looks optimal — each value is computed once, summed once.

That benchmark shape rewards eager materialization. **Most real analytics
workloads do not have this shape.** Common cases:

| Workload | Rows accessed | Eager decode cost | Lazy decode cost |
|----------|---------------|-------------------|------------------|
| Full fold (bench) | 100% | n transforms + n loads | n loads + n transforms (per-access) |
| `WHERE close > 100`, 1% selectivity | 1% | n transforms + n compares | n int compares (no transform) |
| Projection ignoring column | 0% | n transforms | 0 |
| `LIMIT 100` slice | ~0% | n transforms | 100 transforms |
| `take(idx[])` (random access) | k rows | n transforms | k transforms |
| `min` / `max` / `sum` aggregations | 100% | n transforms + reduce | reduce on encoded + 1 scale at end |

Rust's `vortex-array` ALP implementation is lazy: `ALPArray` stores the
encoded `i64` child + exponents, and `compute/` ships kernels —
`compare.rs`, `filter.rs`, `take.rs`, `slice.rs`, `between.rs`, `nan_count.rs`
— that operate **directly on the encoded form**. For `compare`, Rust
encodes the scalar into the ALP integer domain and compares ints, never
materializing doubles. Decode only happens when materialization is forced
(e.g., handing rows to an Arrow consumer that does not implement the
kernel).

vortex-java has no equivalent. The eager model is a hidden assumption
inherited from early scaffolding, not a deliberate choice.

### Why the current benchmark biases optimization

Every optimization landed in this codebase so far is measured against
`javaReadClose` (full sum). That benchmark is **strictly hostile to
laziness** because it accesses 100% of rows. Two consequences:

1. Every micro-optimization (hoist `scale`, FoR in-place, byte-offset
   loop) is judged on full-materialization throughput. Lazy decode looks
   like a regression in this metric even when it would be a huge win on
   any selective workload.
2. The bench is the only public number in the README, so external
   consumers see "Java 1.3× faster than Rust on close" — true for full
   fold, false for filter pushdown, where Rust crushes Java by skipping
   decode entirely.

### Generalization

The lazy idea is not ALP-specific. Any encoding that is a 1:1 transform
of a single child is a candidate:

- **ALP** — encoded int, `value = (double) int * 10^(f - e)`
- **FoR** — encoded int, `value = encoded + ref`
- **ZigZag** — encoded uint, `value = (u >>> 1) ^ -(u & 1)`
- **Composition** — ALP(FoR(Bitpacked)) is still a 1:1 closed form: read
  the bitpacked int, add `ref`, multiply by `scale`. Three transforms
  fused into one expression.

`Bitpacked`, `Pco`, `Zstd`, `Fsst` are **not** candidates — their output
shape differs from their input (compact compressed bytes → wider element
array), so element-at-i requires unpacking a window. They must remain
eager. `Dict` is a special case (lazy is trivial — `getDouble(i) =
values[indices[i]]`) but is already O(1) per access.

## Decision

**Adopt lazy decode + compute pushdown in two phases.** Phase 0 (bench)
gates the work; phases 1 and 2 are sequential.

### Phase 0 — bench shape (blocks 1 and 2)

Add benchmarks that reward laziness. Without these, phase 1 will look
like a regression on the only number we measure.

- `RustVsJavaReadBenchmark.javaFilterClose` — `WHERE close > X` with
  selectivity sweeps at 0.1% / 1% / 10% / 100%. Reports rows-matched/s
  *and* full-scan throughput as control.
- `RustVsJavaReadBenchmark.javaTakeClose` — `take` with k random indices
  for k ∈ {100, 10k, 1M}.
- `RustVsJavaReadBenchmark.javaSliceClose` — `LIMIT 100` semantics.
- `RustVsJavaReadBenchmark.javaProjectionClose` — request `close`,
  iterate without touching `getDouble`. Measures decode cost paid for
  nothing.

Keep the existing `javaReadClose` (full fold) as the **negative test**:
phase 1 must not regress it more than 10%, phase 2 must not regress it
at all.

### Phase 1 — lazy materialization (no compute pushdown)

Change `AlpEncodingDecoder.decode()` to return a `DoubleArray` view that
holds the encoded `MemorySegment` + `double scale` + (optional)
`PatchesIndex`. `getDouble(i)` becomes `(double) src.get(LE_LONG, i) *
scale`, with O(log p) patch lookup if patches exist (binary search the
sorted patch indices for `i`).

Two implementation options for the patch fast path:

- **Sparse bitmap**: `BitSet` of `n` bits over patched indices. O(1)
  lookup. Memory: `n / 8` bytes per chunk. For 1M-row chunks: 125 KB.
- **Sorted index array + binary search**: `long[] patchIdx`. O(log p)
  per access. Memory: `p * 8` bytes. For p = 1% of n, this is `n / 12.5`
  bytes — slightly larger than bitmap.

For phase 1 use the bitmap. It costs more memory but is O(1) and
predictable.

`DoubleArray` becomes a sealed interface; existing eager array is
`DirectDoubleArray`, the lazy variant is `AlpDoubleArray`. Same for
`LongArray` to support lazy FoR and ZigZag.

### Phase 2 — compute pushdown

Add a `Kernel` SPI that operates on encoded arrays. Initial kernels:

- `CompareKernel`: `compare(arr, scalar, op) → BoolArray`. For ALP,
  encode the scalar to the int domain and compare ints. For FoR,
  subtract the reference and compare ints. Falls back to materialization
  when the scalar does not round-trip through the encoding.
- `BetweenKernel`: `between(arr, lo, hi) → BoolArray`. Same approach.
- `TakeKernel`: `take(arr, indices)` — decode only the requested
  indices.
- `SumKernel`, `MinKernel`, `MaxKernel`: `sum(ALP) = sum(int) * scale +
  patch_correction`. Min/max derivable when `scale > 0`.

`ScanIterator` already has a `RowFilter`; route it through the kernel
SPI before falling back to materialization.

## Consequences

### Positive

- **Filter pushdown becomes possible.** Selective filters (the dominant
  shape in OLAP) skip decode entirely. Expected 10–50× on 1%-selective
  filters based on Rust's published numbers.
- **Projection-only reads cost zero.** Today a column included in scan
  options but never read still pays full decode.
- **Aggregation pushdown.** Sum/min/max over encoded form is one scale
  multiplication at the end, not n per row.
- **The README benchmark stops biasing every decision.** Phase 0 makes
  realistic workloads visible.
- **Closes the gap with Rust on the workloads that actually matter for
  analytics.**

### Negative

- **`javaReadClose` (full fold) will likely regress.** Per-element
  access goes from `seg.getDouble(i)` to `(double) seg.getLong(i) *
  scale`, plus a virtual call on the sealed-interface dispatch. Expect
  5–10% regression. This is the price of laziness for workloads that
  touch every row.
- **API surface grows.** `DoubleArray` and `LongArray` become sealed
  interfaces with multiple variants. Downstream consumers that
  pattern-matched on the concrete type need updates.
- **Patch lookup is now per-access.** Today patches are applied once at
  decode time, then never touched. Lazy needs an index structure (cost
  above) and pays per-row. For full fold over an ALP column with 1%
  patches that's `n * O(1)` bitmap checks — measurable but small.
- **Kernel SPI is a non-trivial design.** Initial scope must be small:
  compare, between, take. Sum/min/max can wait.

### Risks to manage

- **Bimorphic dispatch.** With two `DoubleArray` impls (direct, alp), C2
  inlines both at bimorphic call sites. Adding a third (FoR-on-long-via-
  cast? dict-decoded?) makes it megamorphic and slow. Cap the
  implementations at two unless evidence forces more.
- **Patches edge case.** Patch handling in kernels is the hard part:
  filter must AND in patch presence/value correctness. Easy to get
  wrong. Integration tests against Rust output are mandatory before
  shipping phase 2.
- **Lifetime tangle.** Lazy array holds an encoded segment from a child
  decoder. That segment lives on the chunk arena. If the array escapes
  the chunk's `try-with-resources`, it dereferences freed memory. The
  existing `Chunk.close()` contract already covers this; phase 1 must
  not introduce a `DoubleArray` that survives its chunk.
- **Benchmark integrity.** Phase 0 benchmarks must compare against the
  Rust JNI reader on the same workloads, not just Java-vs-Java. The
  point is to close the gap with Rust, not to look good against an
  artificial baseline.

## Alternatives considered

### A — Stay eager, optimize the existing path

Continue micro-optimizing eager decode (Vector API, better SIMD, fused
multiply-add). Status-quo on API.

Pros: zero risk, zero API churn, the current optimization budget keeps
flowing.
Cons: hard ceiling. Eager decode on a filter-rejected row is **always**
wasted work. No amount of SIMD turns wasted work into useful work. Caps
the library at "fast columnar reader for full scans" instead of "fast
OLAP-style engine."

Rejected: ceiling is too low for the project's stated use case (JVM
analytics engines, OLAP systems).

### B — Lazy only for ALP, not the general pattern

Pursue lazy ALP because the benchmark called it out; skip FoR / ZigZag.

Pros: smaller scope.
Cons: leaves the same waste in every other 1:1 transform encoding. ALP
on its own is not the long pole — `ALP(FoR(Bitpacked))` is. Lazy ALP
that still forces FoR materialization recovers only part of the win.

Rejected: the pattern is general; solving it once for the family is
cheaper than three separate one-off lazy implementations.

### C — Compute pushdown without lazy materialization

Add kernels (filter, take, sum) that re-decode internally when called.
Skip the `DoubleArray` polymorphism.

Pros: no API change.
Cons: re-decoding internally means the chunk got eagerly decoded once
already at scan time. The kernel pays the decode cost a second time.
Net negative.

Rejected: only works if scan does not eagerly decode — which is exactly
phase 1.

## References

- Rust reference: `https://github.com/spiraldb/vortex/tree/main/encodings/alp/src/alp/compute`
- Rust ALP `CompareKernel`: encodes scalar into ALP int domain, compares
  ints. No decode.
- Rust ALPArray definition: `https://github.com/spiraldb/vortex/blob/main/encodings/alp/src/alp/array.rs`
- Local: `AlpEncodingDecoder.decodeF64` (current eager path),
  `FrameOfReferenceEncodingDecoder.applyReference` (recently made
  in-place when src writable — small win on the eager path; lazy would
  obsolete this code)
- [ADR 0005](0005-vector-api-adoption.md) — Vector API is an
  optimization on top of an eager loop; lazy makes most of those loops
  conditional, changing what is even worth vectorizing.
- [CLAUDE.md §Memory model — Encoding output allocation rule](../../CLAUDE.md)
  — current rule mandates arena allocation for decode output. Phase 1
  changes this rule: lazy arrays do not allocate decode output, they
  hold the input.
