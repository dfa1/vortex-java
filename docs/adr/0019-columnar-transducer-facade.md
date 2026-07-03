# ADR 0019: Columnar transducer façade for compute

- **Status:** Proposed
- **Date:** 2026-07-02
- **Deciders:** project maintainer
- **Supersedes:** —
- **Superseded by:** —

## Context

ADR 0013 shipped the compute primitives as two fused kernels behind `reader.compute.Compute`:
`filteredSum(filterColumn, predicate, aggColumn)` and `filteredAggregate(chunk, rowFilter,
aggColumn)`. They are deliberately minimal — static entry points, package-private kernels — so
early callers would not couple to a kernel shape before the ergonomics were settled. That bet paid
off twice:

1. The original §1–§3 design (a materialized `Mask` between filter and reduce) was built and then
   **removed**: a selection bitmap between stages is strictly slower than fusing the stages into one
   pass. Whatever the public API looks like, execution must stay single-pass.
2. The dict code-scan lane (`DictFilter`) showed that the wins now come from **encoding-aware
   structural dispatch**: the kernel inspects the filter column's shape (an offset slice over a
   dict view with `u8` codes) and swaps the whole loop, not the per-row callback. Measured on 100M
   rows: 762 → 25.5 ms/op for `filteredSum`, 983 → 36 ms/op for `filteredAggregate` (the last 5×
   of which required a profile-guided fix: the fold's aggregate read had to become monomorphic —
   see ADR 0013's result table). A per-row lambda (`RowPredicate`) cannot express that — the lane
   needs to see the *description* of the filter, not a compiled `test(i)`.

Meanwhile the caller side is growing. The Calcite adapter (ADR 0018) invokes `filteredAggregate`
from the boundary-chunk tier of the aggregate push-down; each new capability (a second aggregate
column, `GROUP BY` push-down Phase 3, a projection) would today mean another bespoke static method
on `Compute` and another hand-wired call site in the push-down rule. There is no way to *compose* a
chunk-level computation — filter by two columns, fold three aggregates — without either a new
kernel entry point per combination or falling back to per-row accessor loops, which the dict-lane
numbers show cost 30× on encoded columns.

The goal is an ergonomic public compute API that composes, while preserving both hard-won execution
properties: single-pass fusion and encoding-aware lane dispatch. Explicitly **not** a goal: a query
engine. Join, sort, expression trees, and query planning belong to Calcite; this façade only makes
the per-chunk leaf computation composable and fast (see `project_compute_facade`: the aim is faster
Calcite push-down).

## Decision

Introduce a **columnar transducer**: an immutable, declarative description of a per-chunk pipeline
— column-bound filter stages and aggregate folds — that a terminal operation compiles into one
fused pass. "Transducer" in the original sense: the transformation is described independently of
the source and reused across chunks; it is *not* the Clojure-style composition of per-element
functions, precisely because per-element functions are what the kernels must never see.

Sketch (names to be settled during implementation):

```java
ChunkFold fold = ChunkFold.builder()
        .filter("category", Predicate.eq(7L))         // stages are DATA: column + Predicate
        .filter("price", Predicate.gt(500.0))
        .aggregate("measure", Fold.SUM, Fold.MIN)      // one or many folds, one or many columns
        .aggregate("volume", Fold.SUM)
        .build();                                      // lowered/validated once

FilteredAggregate measure = fold.apply(chunk).column("measure");
```

Design rules, in force from the first commit:

- **Stages are data, never lambdas.** A filter stage is a `(column, Predicate)` pair — the same
  `Predicate` vocabulary as `RowFilter` and the zone maps. An aggregate stage is a `(column, fold
  kind)` pair. Because stages are inspectable, the terminal compile step can route each filter to
  its best lane (dict code-scan, primitive typed-accessor, generic boxing) exactly as
  `FusedFilterSum` does today — and future lanes (a `RunEnd` run-scan, a `Constant` short-circuit)
  drop in without touching the API.
- **Terminal compilation, single pass.** `apply(chunk)` lowers every stage once (reusing
  `PrimitiveFilter` / `DictFilter` lowering — the single source of truth), chooses the driving lane,
  and folds everything in one scan with no intermediate bitmap or materialized selection. The
  n-ary `AND` of filter stages keeps the existing three-valued-logic semantics bit-identical to
  `filteredAggregate`.
- **The existing `Compute` statics become thin wrappers** over one-stage pipelines and are kept:
  they are shipped API and the right call for the simple cases. The kernels stay package-private.
- **Scope: fold-shaped terminals only.** Filters + aggregate folds (`SUM` / `MIN` / `MAX` /
  `COUNT`), matching what the Calcite boundary tier consumes. A row-materializing terminal
  (project/collect) is explicitly out of scope until a concrete consumer exists — it is where
  "façade" would start sliding toward "query engine".

The Calcite `VortexAggregatePushDownRule` boundary tier is the first consumer: it currently loops
aggregate columns one `filteredAggregate` call per column; a multi-aggregate pipeline folds them in
one scan of the filter column.

### Baseline (measured 2026-07-03, `ComputeKernelBenchmark`, 100M rows)

The two levers, pinned as benchmarks the implementation must beat — expressed the only way the
current API allows:

| Workload | Today | ms/op |
| --- | --- | --- |
| 1 dict leaf × 2 aggregates (`fusedFilteredAggregateTwoAggregates`) | 2 kernel calls, filter re-scanned per aggregate | 137.3 ± 0.1 |
| 2-leaf `AND` × 2 aggregates (`fusedFilteredAggregateMulti`, this ADR's example) | multi-leaf declines the dict lane → per-row `RowPredicate` path, × 2 calls | 2269.1 ± 19.8 |

For scale: the single-leaf single-aggregate dict lane runs the same scan in 36.2 ms and the raw
code scan in ~25 ms. The pipeline's compile step attacks both levers at once — the dict leaf drives
the scan, the residual leaf is tested only on code matches, and every aggregate folds from that one
pass — so the example workload's target is tens of milliseconds, not seconds.

## Consequences

### Positive

- Composition without new entry points: k filters × m aggregates is one pipeline, one scan, one
  API — not `k×m` static methods.
- Encoding-aware lanes keep working and keep winning: the declarative stages preserve exactly the
  information (`Predicate` + column shape) that made the 30× dict lane possible.
- The Calcite boundary tier stops re-scanning the filter column once per aggregate column.
- One lowering path shared with `RowFilter` / zone maps — no semantic drift between pruning and
  row-level evaluation.

### Negative

- A builder API is more surface than two statics; it must be documented and versioned as public
  API (the "small public APIs" rule says: start minimal, grow only on demonstrated need).
- Multi-aggregate folds widen the kernel's inner loop (several accumulators per selected row);
  the single-aggregate fast shape must not regress — benchmark before accepting.

### Risks to manage

- **Lambda creep.** The moment a stage accepts a user lambda, lane dispatch dies (megamorphic
  per-row calls, the exact failure ADR 0013 measured). The API must not offer one, even as a
  convenience overload.
- **Query-engine scope creep.** `GROUP BY`, joins, expressions stay in Calcite. The pipeline's
  vocabulary is bounded by what the kernels can fuse in one pass.
- **Premature generality.** Ship with the Calcite boundary tier as the proving consumer; grow the
  vocabulary only when a second concrete consumer needs it.

## Alternatives considered

- **`java.util.stream.Stream` over rows.** Idiomatic, but value-level: per-row boxing and
  megamorphic per-element calls — the measured 30× loss on encoded columns — and no way to see the
  predicate's description for lane dispatch or zone pruning.
- **Clojure-style function transducers** (composed `(acc, row) -> acc` reducers). Same per-element
  dispatch problem; composition happens at the wrong level (values, not columns).
- **Keep growing `Compute` statics.** No composition; every filter/aggregate combination is a new
  method and a new hand-fused kernel; the Calcite rule accumulates call-site switches. This is the
  status quo the façade replaces.
- **Expose the kernels directly.** Couples callers to loop shapes that ADR 0013 already replaced
  once (Mask → fused); the package-private kernel rule exists to keep that freedom.

## References

- [ADR 0013](0013-compute-primitives.md) — the fused kernels, the removed `Mask` design, and the
  measured dict code-scan results this façade must preserve.
- [ADR 0018](0018-calcite-sql-adapter.md) — the aggregate push-down boundary tier, the first
  consumer.
- `reader.compute.DictFilter` — the encoding-aware lane whose dispatch model the stage-as-data rule
  protects.
