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
Initial fear was that lazy-by-default would regress it; the PoC
proposed in PR #35 measured the opposite (+9.5% on `javaReadClose`
against the OHLC chain), so the "gate on `hasFilter()`" idea is
dropped — see Phase 2.

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
2. Java *already* wins at 100% selectivity — JNI pays a copy from
   Rust Arrow buffers into Java for every batch, and that copy costs
   more than the tight Java fold over the same rows.

Initial reading was "gate lazy behind `hasFilter()` so the full-fold
path stays eager and the filter path switches to lazy." Measurement
rejected the gate (see below — lazy is faster on both paths).

### API gate (rejected by PoC measurement)

Early draft proposed gating lazy decode behind
`ScanOptions.hasFilter()` so the no-filter path stayed bit-for-bit
eager:

```
ScanOptions.hasFilter() == false  →  eager path (today), zero change
ScanOptions.hasFilter() == true   →  lazy + compute pushdown
```

The motivation was protecting `javaReadClose` (the README full-fold
bench) from any regression caused by the extra method-call cost of
going through an interface to reach the lazy variant.

**PoC measurement rejected this gate.** Lazy decode is *strictly
faster* than eager on full fold (+9.5% on `javaReadClose`) because
the materialisation write/read intermediate buffer disappears — the
lazy variant returns the encoded segment directly and applies the
transform on access; the fused variant unpacks bitpacked → double in
one pass. Net halving of memory traffic on the OHLC chain. The gate
is dropped; lazy is the default whenever the chain pattern matches.

### Phase 1 — Array hierarchy refactor (no behavior change)

Today each primitive Array is a `public final class` with a
`MemorySegment` buffer field. Lazy variants cannot extend it. Convert
every numeric and bool Array to a **non-sealed interface** and move
the current behaviour into a **public** `MaterializedXxxArray` record.
No static factory on the interface — encoders construct
`new MaterializedXxxArray(...)` directly, keeping the interface a
pure contract:

```java
public non-sealed interface DoubleArray extends Array {
    double getDouble(long i);
    void forEachDouble(DoubleConsumer c);
    double fold(double identity, DoubleBinaryOperator op);
}

public final class MaterializedDoubleArray implements DoubleArray {
    public MaterializedDoubleArray(DType dtype, long length, MemorySegment buffer) { ... }
    @Override public double getDouble(long i) { ... }       // existing body
    @Override public void forEachDouble(DoubleConsumer c) { ... }
    @Override public double fold(double identity, DoubleBinaryOperator op) { ... }
}
```

Scope: `BoolArray`, `ByteArray`, `ShortArray`, `IntArray`, `LongArray`,
`Float16Array`, `FloatArray`, `DoubleArray`. Defer `VarBinArray`,
`VarBinViewArray`, `NullArray`, `EmptyArray` — no lazy candidate
encoding for them yet. Container types (`StructArray`, `MaskedArray`,
`ListArray`, etc.) stay `final class` — not lazy candidates.

Rewrite all `new XxxArray(...)` call sites (~115 across reader,
writer, integration, performance, core tests) to
`new MaterializedXxxArray(...)`. Pure textual change.
`ArraySegments` pattern matches resolve to the concrete
`MaterializedXxxArray` so its `buffer()` accessor stays
package-private.

**Behavior unchanged.** Every existing test, integration test, and
benchmark sees `MaterializedXxxArray` everywhere via the interface.
While only one class implements the interface, the JVM can still
treat `getDouble` / `fold` calls as direct calls — the same speed
as today's `final class`.

**Not sealed.** Custom encoding-specific concretes (phase 2+) just
`implements XxxArray` — no `permits` clause to edit, no exhaustive
`switch` required in callers. Callers that need a fast path use
`instanceof` plus a `default` branch, so unknown implementations
automatically fall back to the generic per-row path. Third-party
encodings end up on equal footing with the built-in ones.

**No `static of(...)` on the interface.** An earlier draft added a
factory returning a (then package-private) buffer-backed default.
That coupled the interface to its concrete and proved unused once
decoders were rewritten to call `new MaterializedXxxArray(...)`
directly. The interface stays a pure contract.

#### Possible follow-up: kernel-based MaterializedXxxArray

Every encoding's eager decode is the same pattern: allocate a buffer,
loop over rows, write `kernel(i)`. Generalising would let the
constructor own the loop:

```java
public MaterializedDoubleArray(DType dtype, long length,
                                LongToDoubleFunction kernel, Arena arena) {
    MemorySegment dst = arena.allocate(length * 8, 8);
    for (long i = 0; i < length; i++) {
        dst.setAtIndex(LE_DOUBLE, i, kernel.applyAsDouble(i));
    }
    // store dst
}
```

Then `AlpEncodingDecoder.decodeF64` shrinks to ~4 lines:

```java
return new MaterializedDoubleArray(dtype, n,
        i -> (double) src.getAtIndex(LE_LONG, i) * scale, arena);
```

Trade-offs:

- **Win:** dedup. Every eager decoder collapses to one expression +
  one constructor call.
- **Cost — per-row indirect call.** The JVM compiles the
  constructor body once and shares it across every decoder that
  calls it. The `kernel.applyAsDouble(i)` call inside that shared
  loop therefore sees *every* lambda that ever passes through.
  With one decoder calling it, the JVM can inline the kernel and
  keep the loop tight. With two, it can still inline both but the
  call is no longer a single direct jump. With three or more, the
  JVM gives up inlining and every row pays a real method-call cost.
  Options when that ceiling hurts: accept it, give each decoder its
  own copy of the constructor (defeats the dedup), or fall back to
  the existing buffer constructor for the slowest decoders.
- **Lost flexibility.** Encoding-specific tricks — `i % srcCap`
  branch-split, broadcast-cap branches, in-place writes when source
  is writable — don't fit a pure kernel. Forcing them into the kernel
  means a per-row `cap` check (banned by the hot-loop rule); keeping
  them out means the kernel constructor only covers the boring case.

Add the kernel constructor *alongside* the existing buffer
constructor. Decoders that want the loop dedup opt in; decoders with
quirky paths keep their own loop. Tracked as a follow-up; not part of
this phase.

### Phase 2 — Lazy ALP + fused chain

Add the first lazy implementation:

```java
public record AlpDoubleArray(
        DType dtype, long length,
        MemorySegment encoded, double scale
) implements DoubleArray {
    public double getDouble(long i) {
        return (double) encoded.getAtIndex(LE_LONG, i) * scale;
    }
    public double sumWhereGt(double threshold) { /* pushdown */ }
    // forEachDouble / fold = lazy variants
}
```

`AlpDoubleArray` is a top-level `public record` that
`implements DoubleArray`. No `permits` clause to edit — the
interface stays open.

**Patches are not handled by the lazy variant.** Patched chunks fall
back to `MaterializedDoubleArray` (see the fused-chain subsection
below). Checking a patch bitmap or a sorted patch-index on every
access would add a per-row branch inside `getDouble`, which the
codebase's hot-loop rule (see CLAUDE.md) bans because it kills
auto-vectorisation in the million-row inner loops. Patched chunks
are the majority of the OHLC dataset today, so eager materialisation
for them stays the dominant cost; "Extended fusion" in the Future
section is the path to recover it without paying the per-row branch.

**No filter gate.** The PoC measurement showed lazy is *strictly
faster* than the eager path even on full-fold workloads (+9.5% on
`javaReadClose`, OHLC chain), because the materialisation write/read
intermediate buffer is skipped entirely. The earlier "gate lazy behind
`hasFilter()`" idea is dropped in the final design — lazy is the
default whenever the chain pattern matches:

```java
return new AlpDoubleArray(dtype, n, encoded, scale);             // always lazy
// fallback to: new MaterializedDoubleArray(...) when not lazy-eligible
// (read-only source, broadcast source, patched chunk)
```

No `DecodeContext.hasFilter()` plumbing is needed — the gate is gone.

#### Fused chain detection

When the ALP child layout is `FoR(Bitpacked)` (or `Bitpacked` directly
when the writer dropped FoR for `ref==0`), the decoder skips the
intermediate FoR/ALP buffers entirely and returns a
`FusedAlpForBitpackedDoubleArray` that holds the raw packed buffer +
`(bitWidth, offset, ref, scale)`. Public surface is `compareGt`:
unpack each row, apply `+ref`, compare against the threshold in the
encoded int domain, emit a bit into the result `BoolArray`. The full
double materialisation is deferred to `sumMasked` (or any other
generic reduction) which only touches the matching rows. The
full-fold path (`getDouble`/`fold`/`forEachDouble`) lazily
materialises through one pass that writes doubles directly from the
bitpacked unpack — halving the memory traffic vs the old eager chain
(one decode pass instead of bitpacked→FoR→ALP). The class keeps a
package-private fused `sumWhereGt` for the hot
`sumMasked(compareGt(t))` path so the unpack and the accumulate stay
in one loop.

`AlpEncodingDecoder` walks the `ArrayNode` tree to detect the chain
(no decoding cost to peek). Falls back to `AlpDoubleArray` for the
bare-ALP case, `MaterializedDoubleArray` for patched chunks.

### Phase 3 — compute pushdown

**Factor compute as `compare → BoolArray` per encoding, plus a
generic reduction over the masked array.** Fused operators
(`sumWhereGt`, `minWhereBetween`, …) blow up the method count:
`{sum, min, max, count, avg} × {gt, lt, ge, le, eq, between}
× {Alp, AlpRd, For, ZigZag, Fused×N}` is hundreds of methods per
class, and the count multiplies with every new encoding or new
reducer. Cap the blow-up by exposing the *predicate* on the
encoding-specific class and keeping the *reduction* generic:

```java
DoubleArray col = chunk.column("close");
BoolArray sel = switch (col) {
    case FusedAlpForBitpackedDoubleArray fused -> fused.compareGt(threshold);
    case AlpDoubleArray alp                    -> alp.compareGt(threshold);
    default                                     -> Filters.scalarGt(col, threshold);
};
double sum = col.sumMasked(sel);   // generic over DoubleArray
```

Each encoding ships **one** primary pushdown shape — `compare`
(and `between`, which is just compare with two bounds). Reductions
(`sum` / `min` / `max` / `count`) live on the `DoubleArray`
interface and read from the encoded form through the same per-row
`getDouble`; selective masks skip most rows. The method count
drops from `(reducers × predicates × encodings)` to
`reducers + (predicates × encodings)` — a handful of generic
reducers plus a small predicate set per encoding.

`sumWhereGt` survives as a **private** fused method inside
`FusedAlpForBitpackedDoubleArray` (and only there) because the PoC
showed a single-pass unpack-compare-accumulate is materially faster
than two passes on the bitpacked chain. The fused class can choose
to short-circuit `sumMasked(compareGt(t))` to that private path; no
other encoding pays the cost of the extra method and no other
encoding has to mirror its shape.

The earlier "Kernel SPI" idea — a pluggable
`CompareKernel`/`TakeKernel` interface registered per encoding — is
**deferred** until a second encoding (FoR, ZigZag, AlpRd) ships its
own `compareXxx` and we actually see duplication worth factoring
out. Until then, a plain method on the concrete class is easier to
read, the JVM has a direct call to inline (no extra indirection
through a registry), and each encoding can pick the predicates that
make sense for its math.

Per-encoding predicate math:

- **`AlpDoubleArray.compareGt(threshold)`** — encode the scalar to
  the ALP integer domain (`enc = floor(threshold / scale)`) and
  compare ints. Falls back to materialization when the scalar does
  not round-trip through the encoding.
- **`ForLongArray.compareGt(threshold)` (when it lands)** — subtract
  the reference once and compare ints.
- **`take(indices)`** — decode only the requested indices.
  Unblocks the take/slice/projection wins from phase 0; same
  `compare`-shaped story, different input.

For multi-column filters: `AND` evaluates the predicates in column
order and intersects the resulting `BoolArray` masks; `OR` unions
them. Columns referenced only by the filter (not by projection) are
decoded just enough to test and are not delivered to the consumer.

### Future — extend the lazy family

Once ALP proves the shape (PR #35 PoC), apply the same pattern per
encoding. No interface change — each new variant is just another
`implements DoubleArray` (or `LongArray`, `IntArray`):

- `AlpRdDoubleArray` — same idea for ALP-RD
- `ForLongArray`, `ForIntArray` — Frame-of-Reference, lazy
- `ZigZagLongArray`, `ZigZagIntArray` — XOR/shift on access (order
  not preserved, so no pushdown — but lazy still skips the
  materialisation pass)
- Additional fused classes for other common chains, modelled on
  `FusedAlpForBitpackedDoubleArray` from the Phase 2 PoC
- Extended fusion: handle bitpacked patches inside the fused kernel,
  closing the patched-chunk path that today falls back to
  `MaterializedDoubleArray`

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

- **API surface grows.** Every numeric `*Array` becomes a
  `non-sealed interface`. The default buffer-backed impl becomes a
  public `MaterializedXxxArray` record. Downstream consumers that
  constructed `new DoubleArray(...)` directly must switch to
  `new MaterializedDoubleArray(...)`; consumers that only *receive*
  arrays from `Chunk.column(...)` see no change. Encoding-specific
  concretes (`AlpDoubleArray`, `FusedAlpForBitpackedDoubleArray`,
  etc.) are first-class public types that callers pattern-match
  against. The interface stays a pure contract — no `static of(...)`
  factory, no permits, no encoder coupling.
- **Patched chunks lose the lazy win.** Lazy `AlpDoubleArray` does
  not carry a patch index — adding one would force a per-row branch
  inside `getDouble`, which the codebase's hot-loop rule bans for
  vectorisation reasons. So patched chunks fall back to
  `MaterializedDoubleArray` and pay the full eager decode. Patched
  chunks dominate the OHLC dataset today; closing this gap needs the
  "Extended fusion" follow-up that handles patches inside the
  fused-chain unpack.
- **Compute pushdown lives as `compare → BoolArray` methods on the
  encoding-specific concrete; reductions stay generic on the
  interface.** No Kernel SPI yet — `AlpDoubleArray.compareGt(...)`
  is a direct public method; `DoubleArray.sumMasked(BoolArray)` is
  the generic reducer. Fused single-pass paths (e.g.
  `FusedAlpForBitpackedDoubleArray.sumWhereGt`) stay package-private
  and are reached by short-circuiting inside the fused class, not
  by being added to the public surface. SPI lands when a second
  encoding's `compareXxx` proves the shape repeats.
- **Filter semantics change.** Today `RowFilter` is a zone-map prune
  hint; the consumer still re-checks every row. After phase 3 the chunk
  returned by `next()` is already filtered. This is a breaking change
  in the consumer contract for callers that set a filter today. Audit
  existing call sites before shipping.

### Risks to manage

- **Too many implementations slow every call.** Phase 2 introduces
  three classes behind `DoubleArray`: `MaterializedDoubleArray`,
  `AlpDoubleArray`, `FusedAlpForBitpackedDoubleArray`. The JVM can
  still inline `getDouble` / `fold` with three implementations in
  play, but a fourth pushes it over the edge — at that point every
  call through the interface turns into a real method-call lookup,
  and the per-row inner loops slow down across the board. Cap the
  broadly-used implementations at three unless a measured win on
  the filter benchmark forces another. Encoding-specific classes
  that callers pattern-match on directly (and never reach through
  the interface) don't count against this cap, because the call is
  resolved at the `case` arm and goes straight to the concrete
  method.
- **Patched-vs-bare chunk routing.** `AlpEncodingDecoder` picks
  between `MaterializedDoubleArray` (patched), `AlpDoubleArray`
  (bare ALP), and `FusedAlpForBitpackedDoubleArray` (bare ALP over
  `FoR(Bitpacked)`). Misclassifying a patched chunk as bare returns
  wrong values silently. Integration tests against Rust output for
  patched/unpatched/fused combinations are mandatory before shipping
  Phase 2.
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
Keep `DoubleArray` as a single concrete class.

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
