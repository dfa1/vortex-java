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

- `RustVsJavaFilterBenchmark.javaFilterClose` / `jniFilterClose` —
  `WHERE close > X` with selectivity sweeps at 0.1% / 1% / 10% / 100%.
  Threshold is computed in `@Setup` from a sampled quantile so each
  selectivity maps to a real fraction of matching rows. **Already
  landed** alongside this ADR.
- `RustVsJavaReadBenchmark.javaTakeClose` — `take` with k random indices
  for k ∈ {100, 10k, 1M}. (TODO — phase 1 unlocks the win.)
- `RustVsJavaReadBenchmark.javaSliceClose` — `LIMIT 100` semantics. (TODO.)
- `RustVsJavaReadBenchmark.javaProjectionClose` — request `close`,
  iterate without touching `getDouble`. Measures decode cost paid for
  nothing. (TODO.)

Keep the existing `javaReadClose` (full fold) as the **negative test**.
No phase may regress it — the no-filter path stays bit-for-bit eager
(see the API gate below).

#### Phase 0 baseline (10M rows, OHLC, `close` column)

| Selectivity | Java ops/s | JNI ops/s | JNI/Java |
|-------------|-----------:|----------:|---------:|
| 0.1%        |       96   |      361  | **3.7×** |
| 1%          |       96   |      348  | **3.6×** |
| 10%         |       49   |      193  | **3.9×** |
| 100%        |       82   |       53  | 0.65× (Java wins) |

Two observations:
1. Java loses 3.5–4× to JNI at low selectivity. The whole loss is eager
   decode of rejected rows.
2. Java *already* wins at 100% selectivity — JNI's per-batch Arrow
   marshalling costs more than Java's tight fold. **This is the reason
   to gate lazy on `hasFilter()` instead of making it the default.**

### API gate — eager unless a filter is present

```
ScanOptions.hasFilter() == false  →  eager path (today), zero change
ScanOptions.hasFilter() == true   →  lazy + compute pushdown
```

Consequences of the gate:

- `javaReadClose` (no filter) is **untouched**. No `DoubleArray`
  polymorphism, no virtual call, no patch-bitmap allocation. Eliminates
  the "negative consequence" that worried earlier drafts.
- `javaFilterClose` switches to the pushdown path. The user's loop does
  not change: the chunk it receives is already **compacted** to matching
  rows, so the per-row `if (v > threshold)` check goes away.

```java
// Today — user pays the per-row predicate check
for (long i = 0; i < close.length(); i++) {
    double v = close.getDouble(i);
    if (v > threshold) sum += v;
}

// After phase 2 — chunk is pre-filtered, length = matched rows only
for (long i = 0; i < close.length(); i++) {
    sum += close.getDouble(i);
}
```

The filter is applied inside `ScanIterator.next()` *before* the chunk is
returned. `close.length()` reports the matched row count for that chunk.
Empty chunks are skipped inside `next()`.

### Phase 1 — Array hierarchy refactor (no behavior change)

Today each primitive Array is a `public final class` with a
`MemorySegment` buffer field. Lazy variants cannot extend it. Convert
every numeric Array to an **open interface**; keep the current concrete
behavior as a package-private default record exposed only via a static
factory:

```java
public interface DoubleArray extends Array {
    double getDouble(long i);
    void forEachDouble(DoubleConsumer c);
    double fold(double identity, DoubleBinaryOperator op);

    /// Default impl backed by a materialized MemorySegment.
    /// Clients never reference the concrete type.
    static DoubleArray of(DType dtype, long length, MemorySegment buffer) {
        return new BufferedDoubleArray(dtype, length, buffer);
    }
}

// package-private — name does not appear in the public API
record BufferedDoubleArray(DType dtype, long length, MemorySegment buffer)
        implements DoubleArray {
    public double getDouble(long i) {
        return buffer.getAtIndex(PTypeIO.LE_DOUBLE, i);
    }
    // forEachDouble, fold — same body as today's DoubleArray
}
```

Scope: `DoubleArray`, `FloatArray`, `LongArray`, `IntArray`,
`ShortArray`, `ByteArray`. Defer `BoolArray`, `VarBinArray`,
`VarBinViewArray`, `MaskedArray` — different shapes, no lazy candidate
encoding for them yet.

Rewrite ~69 `new DoubleArray(...)` (and sibling) call sites in
`reader.decode.*` and `ScanIterator` to use `DoubleArray.of(...)`.
Pure textual change; the factory returns the same buffer-backed
implementation as before.

**Behavior unchanged.** Every existing test, integration test, and
benchmark sees `BufferedDoubleArray` everywhere via the interface. JIT
call sites stay monomorphic until a second impl is introduced. Run
`./mvnw verify` and `RustVsJavaReadBenchmark` to confirm zero
regression before moving on.

**Not sealed.** Custom encoding-specific concretes (phase 2+) just
`implements DoubleArray` — no permits edit, no exhaustive switch
required in callers. Kernels use `instanceof` + `default` fallback so
unknown impls degrade to the generic per-row path automatically. This
keeps third-party encodings on equal footing with built-in ones.

### Phase 2 — Lazy ALP variant + filter gate

Add the first lazy implementation:

```java
public record AlpDoubleArray(
        DType dtype, long length,
        MemorySegment encoded, double scale,
        PatchesIndex patches  // null if no patches
) implements DoubleArray {
    public double getDouble(long i) {
        if (patches != null && patches.has(i)) {
            return patches.value(i);
        }
        return (double) encoded.getAtIndex(LE_LONG, i) * scale;
    }
    public DoubleArray compareGt(double threshold, Arena arena) { /* pushdown */ }
    // forEachDouble / fold = lazy variants
}
```

`AlpDoubleArray` is a top-level `public record` that
`implements DoubleArray`. No permits edit — the interface stays open.

Patches index: two options for O(1) lookup:

- **Sparse bitmap**: `BitSet` of `n` bits over patched indices. O(1)
  lookup. Memory: `n / 8` bytes per chunk. For 1M-row chunks: 125 KB.
- **Sorted index array + binary search**: `long[] patchIdx`. O(log p)
  per access. Memory: `p * 8` bytes.

Use the bitmap. Predictable per-access cost.

**Filter gate** in `AlpEncodingDecoder.decode`:

```java
return ctx.hasFilter()
    ? new AlpDoubleArray(dtype, n, encoded, scale, patches)   // lazy
    : MaterializedDoubleArray.of(...);                         // today's eager path
```

`DecodeContext` gains an `hasFilter()` hint propagated from
`ScanOptions`. No `RowFilter` parsing inside the decoder — only the
boolean signal.

### Phase 3 — compute pushdown

`ScanIterator` routes `ScanOptions.rowFilter()` through a kernel SPI
before falling back to materialization. Initial kernels:

- `CompareKernel`: `compare(arr, scalar, op) → BoolArray`. For
  `AlpDoubleArray`, encode the scalar to the int domain
  (`enc = round(scalar / scale)`) and compare ints. For
  `ForLongArray` (when it lands), subtract the reference and compare
  ints. Falls back to materialization when the scalar does not
  round-trip through the encoding.
- `BetweenKernel`: same approach for two scalars.
- `TakeKernel`: `take(arr, indices)` — decode only the requested
  indices. Unblocks the take/slice/projection wins from phase 0.
- `SumKernel`, `MinKernel`, `MaxKernel`: deferred.
  `sum(AlpDoubleArray) = sum(int) * scale + patch_correction` is
  straightforward but not on the critical path.

Pattern-match dispatch in `ScanIterator` with a `default` fallback —
no exhaustiveness required, so unknown future impls degrade gracefully:

```java
DoubleArray col = chunk.column("close");
BoolArray sel = switch (col) {
    case AlpDoubleArray alp -> alp.compareGt(threshold, arena);
    default                 -> Filters.scalarGt(col, threshold); // generic via getDouble
};
```

For multi-column filters: `AND` evaluates kernels in column order,
intersecting selection vectors; `OR` unions them. Columns referenced
only by the filter (not by projection) are decoded just enough to test
and are not delivered to the consumer.

### Future — extend the lazy family

Once ALP proves the shape, add (no API change, just new permits):

- `AlpRdDoubleArray` — same idea for ALP-RD
- `ForLongArray`, `ForIntArray` — Frame-of-Reference, in-place lazy
- `ZigZagLongArray`, `ZigZagIntArray` — XOR/shift on access
- Composed: `AlpForBitpackedDoubleArray` fuses three transforms into
  one expression evaluated per access

### Phase 2 — compute pushdown

`ScanIterator` routes `ScanOptions.rowFilter()` through a kernel SPI
before falling back to materialization. Initial kernels:

- `CompareKernel`: `compare(arr, scalar, op) → BoolArray`. For ALP,
  encode the scalar to the int domain (`enc = round(scalar / scale)`)
  and compare ints. For FoR, subtract the reference and compare ints.
  Falls back to materialization when the scalar does not round-trip
  through the encoding (e.g. ALP threshold that is not representable as
  `int * 10^(f-e)` exactly).
- `BetweenKernel`: same approach for two scalars.
- `TakeKernel`: `take(arr, indices)` — decode only the requested
  indices. Unblocks the take/slice/projection wins from phase 0.
- `SumKernel`, `MinKernel`, `MaxKernel`: deferred. `sum(ALP) =
  sum(int) * scale + patch_correction` is straightforward but not on the
  critical path.

For multi-column filters: `AND` evaluates kernels in column order,
intersecting selection vectors; `OR` unions them. Columns referenced
only by the filter (not by projection) are decoded just enough to test
and are not delivered to the consumer.

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

- **API surface grows minimally.** Every numeric `*Array` becomes an
  open interface; the default buffer-backed impl is package-private,
  accessed via `*Array.of(...)`. Downstream consumers that constructed
  `new DoubleArray(...)` directly must switch to the factory; consumers
  that only *receive* arrays from `Chunk.column(...)` see no change.
  Encoding-specific concretes (`AlpDoubleArray`, etc.) become first-class
  public types that kernels can pattern-match against.
- **Patch lookup is per-access in the lazy path.** Today patches are
  applied once at decode time. Lazy needs an index structure (cost
  above) and pays per-row. Only the filter path triggers this.
- **Kernel SPI is a non-trivial design.** Initial scope must be small:
  compare, between, take. Sum/min/max can wait.
- **Filter semantics change.** Today `RowFilter` is a zone-map prune
  hint; the consumer still re-checks every row. After phase 3 the chunk
  returned by `next()` is already filtered. This is a breaking change
  in the consumer contract for callers that set a filter today. Audit
  existing call sites before shipping.

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
