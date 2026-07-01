# ADR 0013: Compute primitives — masks, kernels, no-materialize contract

- **Status:** Accepted, implemented — with §1–§3 superseded in implementation (see the
  *Implementation note* below). Built in `reader.compute`: §4 (`Predicate`), §5 (`RowFilter`
  unified over `Predicate` — a `RowFilter.Column` binds a column to a shared public `Predicate`,
  the same predicate compiled against zone-map stats for pruning and against the decoded array for
  the boundary fold), and §6 (zone-map aggregate push-down, both the whole-zone and boundary-zone
  tiers). §1 (`Mask`) and §2/§3 (the `FilterKernel`/`MapKernel`/`ReduceKernel` interfaces and the
  mask-based no-materialize contract) were built and then **removed**: the shipped design is the
  fused single-pass kernels `Compute.filteredSum` / `filteredAggregate`, which fold filter and
  reduce in one scan with **no intermediate selection bitmap** — a stronger no-materialize
  guarantee than a `Mask` could give. Deferred to their own ADRs: encoded-domain kernel
  specialization (perf escalation — pushing the predicate into the ALP/FoR/Dict integer domain
  without decoding) and the ergonomic façade (a columnar transducer, ADR 0019).
- **Date:** 2026-06-15
- **Deciders:** project maintainer
- **Supersedes:** —
- **Superseded by:** —
- **Related:** [ADR 0002 — Pluggable DType, Layout, and Compute](0002-pluggable-dtype-layout-compute.md),
  [ADR 0005 — Vector API adoption](0005-vector-api-adoption.md),
  [ADR 0010 — Lazy decode](0010-lazy-decode.md),
  [ADR 0012 — Zero-copy layout decoding](0012-zero-copy-layout-decoding.md)

## Implementation note (2026-07-01)

The primitives shipped, but §1–§3 as drawn below did not survive the perf work. The `Mask` sealed
type, the `FilterKernel` / `MapKernel` / `ReduceKernel` interfaces, and the mask-based
no-materialize contract were implemented, benchmarked, and then removed. A materialized selection
`Mask` is a positional bitmap the reduce must re-scan — strictly slower than fusing the filter and
the reduce into one pass. The shipped surface is two fused kernels behind the minimal public
`Compute` entry point, with everything else package-private:

- `Compute.filteredSum(filterColumn, predicate, aggColumn)` — one filter column, one summed column.
- `Compute.filteredAggregate(chunk, filter, aggColumn)` — an n-ary `AND` `RowFilter` and a
  `SUM` / `MIN` / `MAX` / non-null-count fold over the rows it selects.

Both fold filter and reduce in a single scan with no intermediate bitmap, honoring §3's
no-materialize intent more strictly than a `Mask` pipeline would; each is type-specialized into
boxing-free long / double lanes with a generic boxing fallback. `MapKernel` was never built (no
consumer). The `Predicate` (§4) and `RowFilter` unification (§5) vocabulary is unchanged and is what
these kernels consume. The sections below are preserved as the original proposal; read §1–§3 as
history, not as the built design.

## Context

ADR 0010 establishes that the reader defers transform work until access
(`LazyAlpDoubleArray`, lazy FoR / ZigZag, lazy Chunked / Dict per ADR 0012).
The win lands at the **encoding** layer: chunks are skipped via zone maps,
dictionaries return code-vector views, ALP / FoR / ZigZag arrays carry the
encoded segment + transform constants without allocating a decoded buffer.

The lazy infrastructure stops at the `Array` boundary. Once a caller pulls
a chunk out of the scan, the natural next step is filter / project / reduce.
Today the only way to do that is element-by-element via the per-type
accessor (`DoubleArray.getDouble`, `LongArray.getLong`, …) or by forcing
materialization through `ArraySegments.of(Array)`. Neither composes:

- Per-element accessors fight loop fusion — JIT cannot see across user code
  boundaries, so `filter` then `sum` decode twice.
- Forced materialization discards the lazy gain. A filter that retains 1%
  of rows still pays the full decode cost the moment a downstream stage
  asks for a buffer.

External libraries (Stream API, Clojure-style transducers, Fungoid-style
pipelines) compose nicely at the user level but cannot solve this, because
they consume already-decoded values. The fusion has to happen **inside**
the compute layer, on the encoded representation, before the user-level
abstraction touches anything.

Rust's `vortex-array` ships compute kernels for exactly this reason —
`compare.rs`, `filter.rs`, `take.rs`, `slice.rs`, `between.rs`, `nan_count.rs`,
`sum`, `min_max` — each operating directly on the encoded form when
possible. `compare(ALPArray, scalar)` encodes the scalar into the ALP
integer domain and compares ints; the doubles never exist. That is the
shape vortex-java needs.

This ADR defines the **primitives** required to support that shape. The
choice of user-facing API (transducer, Stream, builder DSL) is deferred
to a later ADR — primitives first, syntax later.

## Decision

Introduce a small set of compute primitives, intended for a future
`vortex-compute` module:

### 1. Selection mask as a first-class type

```java
public sealed interface Mask permits AllTrue, AllFalse, RangeMask, BitmapMask {
    long length();
    long trueCount();
    boolean get(long i);
}
```

- `AllTrue` / `AllFalse` are zero-allocation singletons sized via factory.
- `RangeMask(start, end)` represents a contiguous slice — produced by
  `LIMIT` / `SLICE` operators.
- `BitmapMask` wraps a `MemorySegment` of validity bits — produced by
  `compare` / `between` / `is_null` kernels.

A `Chunk` returned by the scan carries an optional `Mask`. Successive
filter kernels intersect masks in place; downstream kernels honor the
mask (skip excluded positions during reduce, emit a smaller result for
`take`). Nothing materializes until a sink demands it.

### 2. Kernel signatures

```java
public interface FilterKernel<A extends Array> {
    Mask apply(A array, Mask current, Predicate predicate);
}

public interface MapKernel<A extends Array, B extends Array> {
    B apply(A array, Mask current);
}

public interface ReduceKernel<A extends Array, R> {
    R apply(A array, Mask current);
}
```

- Kernels receive the input `Array` (possibly a lazy variant), the current
  selection mask, and operator-specific parameters.
- Implementations dispatch on the concrete `Array` subtype via pattern
  switch (e.g. `LazyAlpDoubleArray` → encoded-domain compare, fallback
  arrays → materialized path).
- Output is an `Array` or `Mask` of the **same length** as the input —
  positional alignment is preserved through the pipeline so masks remain
  meaningful across stages.

### 3. No-materialize contract

A kernel that operates on a lazy `Array` variant **must not** call
`ArraySegments.of(array)` unless every fallback path has been exhausted.
The contract:

1. Encoded-domain implementation if the predicate or reduction can be
   pushed through the transform. (`compare(LazyAlpDoubleArray, scalar)`
   encodes the scalar, compares longs.)
2. Streaming per-element implementation using the accessor if (1) is not
   possible. Allocates only the result.
3. Forced materialization only as a last resort, gated by a debug log /
   counter so regressions are visible.

This is the rule that prevents the lazy gain from leaking. A new kernel
that breaks it is a bug, not a perf concern.

### 4. Predicate encoding

```java
public sealed interface Predicate {
    record Eq(Object value) implements Predicate {}
    record Lt(Object value) implements Predicate {}
    record Gt(Object value) implements Predicate {}
    record Between(Object lo, Object hi) implements Predicate {}
    record IsNull() implements Predicate {}
    record And(Predicate left, Predicate right) implements Predicate {}
    record Or(Predicate left, Predicate right) implements Predicate {}
}
```

Predicates are sealed records so kernels can dispatch via pattern switch
and lower into encoded-domain comparisons per encoding (e.g. ALP encodes
the scalar to its integer domain once, then runs a long compare).

### 5. Reuse `RowFilter` for push-down

`reader.RowFilter` already pushes simple predicates into the scan to skip
chunks via zone maps. The new `Predicate` type is the input format
`RowFilter` will accept once it grows beyond the current `Predicate`
shape — same vocabulary used by both layers, so pushdown is just "the
same predicate compiled against zone-map stats instead of an array."

### 6. Aggregate push-down via zone-map stats

§5 is *predicate* push-down: skip zones whose min/max rule them out. The
`vortex.stats` (zoned) layout also enables *aggregate* push-down — answer
a reduction from the per-zone stats table **without decoding the data
segment at all**.

The stats table carries one row per zone. The writer emits `MIN`/`MAX`
today; `NULL_COUNT` and `SUM` are the next increment (Rust parity — Rust
fixtures emit exactly `[MIN, MAX, NULL_COUNT]` and, for numeric columns,
`[MIN, MAX, SUM, NULL_COUNT]`, nothing else). Those four stats answer the
common reductions directly:

- `SUM(col)`   → sum the per-zone `SUM` column.
- `COUNT(col)` → `Σ zone_len − Σ NULL_COUNT` (count of non-nulls).
- `MIN`/`MAX`  → reduce the per-zone `MIN`/`MAX` columns.

A `ReduceKernel` therefore runs in **two tiers**, mirroring the predicate
case:

1. **Whole-zone tier** — for every zone the predicate selects entirely
   (or with no predicate at all), fold the zone's contribution from the
   stats row. No data segment is touched.
2. **Residual tier** — only zones the predicate *partially* selects fall
   back to the streaming per-element reduce (§3 contract), and only for
   those zones.

So a `filter(...).sum(col)` over a column where the filter prunes at zone
granularity becomes a read of the small stats table plus a streaming
reduce of the boundary zones — the same `Predicate` / reduction
vocabulary compiled against zone-map stats at tier 1 and against the
encoded array at tier 2.

This needs the scan to expose per-zone stats to the reduce kernel. The
decode path already exists: `inspector` `ZonedStatsSchema` reconstructs
the stats-table dtype and decodes the zones child; the scan would surface
the same per-zone rows to the kernel rather than (only) to the inspector.

## Consequences

### Positive

- Filter / project / aggregate compose without materializing intermediates.
  A `filter(close > 100).sum(volume)` pipeline touches the close column's
  encoded i64s once and the volume column's encoded i64s once.
- The lazy decoders introduced by ADR 0010 / 0012 become useful for more
  than single-column projection.
- User-facing API layer (transducer, Stream, fluent builder) is a thin
  wrapper — same primitives, multiple syntaxes possible.
- Test coverage is per-kernel, decoupled from any specific API surface.
- Aggregate push-down (§6) lets `SUM` / `COUNT` / `MIN` / `MAX` be answered
  from the zone-map stats table, skipping data decode entirely for whole
  zones — the payoff that motivates emitting `NULL_COUNT` + `SUM` zone
  stats on the writer side.

### Negative

- Adds a new module (`vortex-compute`) and a non-trivial type vocabulary.
  Kernel implementations grow combinatorially with `(Array variant) ×
  (Predicate variant)`; we'll need a default fallback path so unknown
  combinations still work.
- `BitmapMask` introduces a second memory representation alongside
  decoded buffers — `ArrayStats` and zone-map plumbing need to learn
  about it.

### Risks to manage

- **Kernel matrix explosion.** Mitigate by writing one generic streaming
  path that works for any `Array` via accessors, then specializing only
  the hot encodings (ALP, FoR, BitPacked, Dict). Specialization is a
  performance escalation, not a correctness requirement.
- **User-facing API churn.** Without a decided façade (transducer vs
  Stream vs builder), early callers of `vortex-compute` end up depending
  on raw kernels and break when the façade lands. Mitigate by keeping
  kernels package-private until the façade ADR is accepted, exposing
  only a minimal `Compute` entry point.
- **Mask semantics under cascaded encodings.** A mask produced against
  `LazyAlpDoubleArray` must still align positionally after the chain is
  unwrapped through `Chunked` / `Dict`. Test cross-encoding pipelines
  early.

## Alternatives considered

### A. Push everything through the Stream API

Reuse `Stream<Double>` etc. with custom Spliterators that respect masks.
Rejected: Stream forces autoboxing on primitive specializations (no
`DoubleStream.filter(DoublePredicate)` that emits a packed mask), and the
internal Spliterator state isn't a natural place to carry encoded-domain
short-circuits. Worth offering as a *convenience* sink on top of the
kernels, not as the primary fusion mechanism.

### B. Port Fungoid / Clojure-transducer model directly

The transducer composition shape is appealing — `comp(map, filter, take)`
returns a single reducing function. But transducers fuse at the *value*
level (one item at a time), which still forces per-element decode. Vortex
needs per-chunk, per-encoding fusion, which is a different abstraction.
Transducers (or a similar fluent API) can live on top of the kernels as
a syntax layer; they cannot replace the kernels.

### C. Defer the question until ADR 0002 (pluggable compute) is taken up

ADR 0002 is marked Deferred and covers compute *pluggability*. This ADR
covers the **primitives** that pluggable compute would plug into. We can
ship the primitives now without committing to user-installable kernels.
Pluggability is a later question — the no-materialize contract and
predicate vocabulary stand on their own.

## References

- Rust `vortex-array` compute kernels:
  `https://github.com/vortex-data/vortex/tree/develop/vortex-array/src/compute`
- Arrow C++ compute kernels documentation:
  `https://arrow.apache.org/docs/cpp/compute.html`
- Fungoid (transducer-inspired pipeline lib) as a possible façade layer:
  `https://github.com/dfa1/fungoid.js`
