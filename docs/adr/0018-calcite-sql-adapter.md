# ADR 0018: Apache Calcite SQL adapter — be a push-down source, not an engine

- **Status:** Accepted — Phases 0–2 (schema/scan, filter + projection push-down, and
  MIN/MAX/COUNT/SUM/AVG aggregate push-down) implemented in the `calcite/` module; the rule
  auto-registers over a bare `jdbc:calcite:` connection
- **Date:** 2026-06-24
- **Deciders:** project maintainer
- **Supersedes:** —
- **Superseded by:** —
- **Related:** [ADR 0013 — Compute primitives](0013-compute-primitives.md),
  [ADR 0016 — vortex-arrow bridge](0016-vortex-arrow-bridge.md),
  [ADR 0005 — Vector API adoption](0005-vector-api-adoption.md)

## Context

The reader exposes typed zero-copy columns and (ADR 0013) a path to filter/aggregate
push-down over the encoded form and the zone-map stats table. What it does *not* have is a
query language. A recurring ask is "can I run SQL over a Vortex file?" — `SELECT … WHERE …
GROUP BY …` rather than hand-written scan loops.

Two ways to answer that:

1. **Build a SQL engine.** Parser, type system, logical plan, cost-based optimiser, join
   algorithms, aggregation/spilling, NULL semantics. Person-years, none of it
   Vortex-specific — it is solved, generic, and not where this project's advantage lies.
2. **Adapt to an existing engine.** Plug the Vortex scan into a mature SQL front-end and let
   it own parsing/planning/joins. The project keeps owning the one thing only it can do well:
   a fast columnar scan with push-down into the encoding and the stats table.

The project's advantage is the **scan**, not the engine. An external engine only ever sees
*decoded* values, so it has already paid the cost Vortex could have skipped — chunk-skipping
via zone maps, encoded-domain comparison (`compare(ALPArray, scalar)` without materialising
doubles), and answering `MIN`/`MAX`/`COUNT`/`SUM` straight from the stats table. That asymmetry
only exists *inside* Vortex. So the goal is to expose the scan + push-down to an engine, not to
reimplement the engine.

Apache Calcite is the natural JVM host: a pure-Java SQL parser + relational optimiser +
pluggable adapter framework (the substrate under Drill, Flink-SQL, Beam-SQL). It does the
generic work; the adapter contributes a table that knows how to push work down.

### Prototype (this branch)

A `calcite` module (`vortex-calcite`, Calcite 1.40.0) was built to de-risk and to anchor this
ADR in working code rather than speculation:

- **JDK 25 / Janino gate.** `CalciteSmokeTest` runs a query that forces Calcite's Enumerable
  convention to generate Java and compile it with Janino. It **passes on JDK 25** — Calcite's
  runtime codegen works on this project's target, so the interpreter fallback is not needed.
  This was the one real unknown (Janino has historically lagged on new class-file versions —
  the same pressure that produced the in-house codegen of ADR 0017).
- **`VortexTable implements ScannableTable`** — derives the SQL row type from the file's
  `DType.Struct` and decodes chunks into `Object[]` rows. Baseline only: every full-scan row
  crosses the `Object[]` boundary.
- **`VortexSchema`** — maps a SQL table name to a `.vortex` file.
- **`VortexAggregates`** — the push-down core, deliberately built *beside* Calcite (not yet as
  a planner rule): `MIN`/`MAX`/`COUNT` are read from footer zone-map stats with no data decode;
  `SUM`/`AVG` fall back to a streaming scan because the writer emits no per-zone `SUM` stat yet.
- **`OhlcSqlDemoTest`** — 120 000-row OHLC file, computes the five aggregates via Calcite (full
  scan, ground truth) and via push-down, asserts they agree.

Measured on the demo (120 000 rows, 12 zones):

```
AGGREGATE     VALUE           SOURCE
MIN(low)      3.65            ZONE_STATS_PUSHDOWN
MAX(high)     1048.1          ZONE_STATS_PUSHDOWN
COUNT(*)      120000          ZONE_STATS_PUSHDOWN
SUM(volume)   120023864741    FULL_SCAN
AVG(volume)   1000198.87      FULL_SCAN
full scan (Calcite): ~577 ms | push-down (min/max/count from stats): ~5.9 ms   (~100×)
```

Two SQL-semantics facts surfaced and must be carried forward by the production adapter:

- `date` is a SQL reserved word — a column of that name must be quoted, or queries avoid it.
- Calcite's `AVG` over a `BIGINT` column does **integer** division; a true mean needs
  `AVG(CAST(col AS DOUBLE))`. The adapter's own aggregate path must match whichever semantics
  Calcite's planner applies, so a rule-based push-down stays bit-compatible with the fallback.

## Decision

**Ship a `vortex-calcite` adapter that makes Vortex a push-down SQL source for Apache Calcite.
Do not build a SQL engine. Keep the prototype's structure; productionise it in phases.** The
heavy Calcite dependency tree (Avatica, Guava, Janino) is **quarantined in this module** —
`core`/`reader`/`writer` stay dependency-light, the same isolation rule applied to the planned
`vortex-arrow` bridge (ADR 0016).

Phased productionisation:

- **Phase 0 — queryable (prototype, done).** `ScannableTable` + `VortexSchema`; `SELECT *` and
  any aggregate work via full scan. Proves the wiring and the JDK 25 codegen path.
- **Phase 1 — filter + project push-down.** Implement `ProjectableFilterableTable`: projection
  prunes columns (decode only what's asked); filters translate Calcite `RexNode`s into the
  ADR 0013 `Predicate` vocabulary and push into the scan's `RowFilter` (zone-map chunk-skip +
  encoded-domain compare). Unsupported `RexNode`s are left for Calcite — push-down is
  best-effort, never a parse failure.
- **Phase 2 — aggregate push-down (the payoff).** A `RelOptRule` matching `Aggregate(TableScan)`
  for `MIN`/`MAX`/`SUM`/`COUNT` rewrites to a single-row `Values` folded from the zone-map stats
  table — promoting today's `VortexAggregates` from a side helper to a planner rule. `AVG` rides
  the same path once `AggregateReduceFunctionsRule` reduces it to `SUM`/`COUNT`. All of
  `MIN`/`MAX`/`COUNT`/`SUM`/`AVG` work now (ADR 0013 §6 — the increment that emitted the per-zone
  `SUM` + `NULL_COUNT` stats the fold needs).

### Implementation status (landed in the `calcite/` module)

Phases 0–2 are implemented and tested:

- **Phase 1 landed.** `VortexTable` is a `ProjectableFilterableTable`: projection prunes columns;
  `=, <>, <, <=, >, >=, AND, BETWEEN, IN` translate to a reader `RowFilter` for zone-map chunk
  skipping. Filters are pushed but **not consumed** (whole-chunk pruning is approximate, so
  Calcite still row-filters). Demo: a `date` range over 1M rows decodes **1 of 100 chunks** (99%
  pruned), exact result, and `EXPLAIN` shows `filters`/`projects` folded into `BindableTableScan`.
- **Phase 2 landed (MIN/MAX/COUNT/SUM/AVG).** `VortexAggregatePushDownRule` rewrites a whole-table
  `MIN`/`MAX`/`COUNT`/`SUM` (no `GROUP BY`, numeric columns) into a single-row `LogicalValues` from
  the stats — the optimized plan has **no scan and no aggregate**. `SUM` folds the per-zone `SUM`
  rows and answers an all-null column as SQL `NULL`; `AVG` reduces to `SUM`/`COUNT`
  (`AggregateReduceFunctionsRule`) and rides the same path. The rule **auto-registers**: a
  `VortexTable` translates to a `VortexTableScan` whose `register()` installs the rules when the
  planner first sees the node, so a plain `jdbc:calcite:` connection rewrites these aggregates with
  no caller wiring. The scan immediately expands to a stock `LogicalTableScan`, leaving the
  Bindable filter/projection push-down untouched.
- **WHERE-filtered push-down (clean partition).** A `WHERE` no longer blanket-abandons the rewrite.
  When the pushed predicate translates fully to a `RowFilter` and partitions the zones cleanly —
  every chunk either entirely matches or entirely fails it, with no boundary chunk it partially
  selects — the rule folds `SUM`/`COUNT`/`MIN`/`MAX` from only the kept zones' statistics, still a
  scan-free `LogicalValues`. Classification applies SQL three-valued logic (a `NULL` in a compared
  column does not match, so a zone is fully selected by a comparison only if it also provably
  carries no nulls). The moment one zone is a boundary, the rewrite is abandoned to the scan — so
  the common selective-range case (one or two boundary chunks) still scans for now; see below.

Gotchas found and recorded for the production adapter:

- **Reserved words.** Column names that are SQL keywords (`close`/`open`, `value`, `year`, …)
  break unquoted queries under the stock parser. Resolved by `VortexCalcite.connect`, which wires
  the Babel parser (`calcite-babel`) so reserved words parse as identifiers unquoted — the adapter
  emits no SQL of its own, so there is nothing for it to quote; the lever is the parser, owned by
  the connection. The residual exceptions are the typed-literal keywords (`date`, `time`,
  `timestamp`, `interval`), which stay reserved even under Babel and still need back-tick quoting.
- **Integer stats are `Long`.** Zone-map integer stats decode as `Long`, floats as `Double`,
  regardless of column width. A filter literal boxed at the column's natural width (`Integer` for
  `I32`) silently disables pruning because `ScanIterator.compareValues` swallows the resulting
  `ClassCastException` and returns `0` (= "cannot prune"). The adapter coerces integer literals to
  `Long`; the underlying reader trap is filed as issue #159.
- **`BETWEEN`/`IN` are `SEARCH(Sarg)`**, not `AND(>=,<=)`/`OR` — expand with `RexUtil.expandSearch`
  before translating.
- **Calcite 1.40 removed `RelRule.Config.EMPTY`.** The modern `RelRule.Config` path needs the
  Immutables annotation processor; the deprecated `RelOptRule` operand constructor is lighter for
  a single adapter rule (localized `@SuppressWarnings("deprecation")`).

The Phase-2 rule now auto-registers over the bare `jdbc:calcite:` planner via
`VortexTableScan.register()` (a `TranslatableTable` translating to a custom scan that expands back
to `LogicalTableScan`), so SQL over JDBC is rewritten with no caller wiring — the former main
productionisation gap. The unit tests still drive the rule through a `HepPlanner` directly for
focused branch coverage. A `WHERE` whose predicate partitions the zones cleanly is now answered
from the kept zones' stats (above); the remaining Phase-2+ work is the **boundary** tier (ADR 0013
§6): folding the fully-selected zones **and streaming only the boundary zones** a selective range
partially selects. That needs a reader primitive the current API lacks — the ability to decode a
chosen subset of chunks (so the fully-covered zones are not decoded) plus row-level predicate
evaluation — without which a boundary-bearing filter decodes the same chunks as a plain scan and so
gains nothing. Until then a filter with any boundary chunk abandons to the (zone-map-pruned) scan.

**Two doors, chosen by query shape.** Calcite is the right tool for *reducing* queries (filter
/ aggregate / group-by), where push-down shrinks the result and the `Object[]` boundary
amortises to near-nothing. It is the wrong tool for *bulk columnar extract* (`SELECT *` over a
large file, weak/no filter): every row boxes. That shape should bypass Calcite via the Arrow
C-Data export of ADR 0016 (Option B), columnar and zero-boxing. Calcite is never Vortex's
*execution* engine — the moment many rows flow *through* it, the wrong door was used. The
adapter's job is to push filter/project/aggregate down hard so what reaches `Object[]` is small
by construction.

## Consequences

### Positive

- SQL over Vortex with no engine to build or maintain; Calcite owns parse/plan/optimise/join.
- The push-down surface is exactly the ADR 0013 primitive set — the adapter is the first real
  consumer of that vocabulary, validating it against a real planner.
- Dependency isolation: only consumers who add `vortex-calcite` pay the Calcite/Janino/Guava
  cost; the core stays clean and (per ADR 0017) JPMS-viable.
- Aggregate push-down gives the headline result — `MIN`/`MAX`/`COUNT` answered from a tiny stats
  table, ~100× faster than a full scan on the prototype, exact against ground truth.
- Concrete motivation for the writer-side `SUM`/`NULL_COUNT` zone-stat increment: once emitted,
  `SUM`/`AVG`/`COUNT` join the no-decode tier.

### Negative

- Calcite's Enumerable execution is row-at-a-time `Object[]` with autoboxing. Acceptable for a
  *source* (push-down does the heavy work before rows materialise), but it caps throughput on
  any query that emits many rows — which is why bulk extract is pushed to the Arrow door, not
  Calcite.
- A second public surface (SQL) with its own semantics gotchas (reserved words, `AVG` integer
  division, three-valued logic) the adapter must honour exactly to stay consistent with the
  push-down path.
- The `RelOptRule` for aggregate push-down is non-trivial Calcite-internal work; the kernel
  matrix (Array variant × Predicate variant) from ADR 0013 still has to exist underneath.

### Risks to manage

- **Push-down/fallback divergence.** A pushed-down aggregate must produce bit-identical results
  to Calcite's own computation over the scanned rows (NULL handling, integer vs double `AVG`,
  overflow). Test every pushed aggregate against the full-scan ground truth, as the prototype
  does.
- **Calcite version churn.** Calcite's adapter SPI and Avatica move between releases; pin the
  version and keep the smoke test as the JDK-compatibility tripwire (re-run on JDK upgrades).
- **Temptation to make Calcite columnar.** Bridging Vortex into a vectorised execution engine is
  the rabbit hole; that is DuckDB/DataFusion territory (native — the `vortex-jni` world). Resist
  it. Keep Calcite as front-end + planner only.
- **Lifetime.** `Object[]` rows hold decoded values, but any zero-copy path (future) must keep
  the reader's `Arena` alive while the `Enumerator` is open — the same release-callback hazard
  flagged for the Arrow C-Data bridge (ADR 0016).

## Alternatives considered

### A. Build a small bespoke SQL engine

A hand-rolled parser + executor over the ADR 0013 kernels (single table, `WHERE`, simple
aggregates, no joins). Attractive as a *demo* of push-down, but it dies at the first join or
optimiser requirement and re-implements solved, generic machinery. Rejected as a product
direction; the prototype's value is the adapter + push-down helper, not a query language.

### B. Hand off entirely to DuckDB / DataFusion via Arrow

Export decoded columns through the Arrow C-Data interface (ADR 0016 Option B) and let a mature
native engine run all SQL. Least code, fastest *execution* — but the engine receives *decoded*
Arrow, so encoded-domain push-down (Vortex's whole edge) is lost; only zone-map chunk-skip
survives, and only if Vortex pre-filters before export. This is the right path for *bulk
extract* and for native consumers, and it coexists with this ADR as the "second door" — not a
replacement for JVM SQL with push-down.

### C. Apache Calcite with full custom physical convention

Implement a complete `Convention` with vectorised Vortex operators so execution stays columnar
end-to-end. Maximum performance, but it means building a vectorised execution engine inside
Calcite — most of option A's cost plus Calcite-internal complexity. Deferred indefinitely; the
`Object[]` Enumerable path plus aggressive push-down is sufficient for the source role, and bulk
throughput belongs to the Arrow door.

## References

- Prototype: `feat/vortex-calcite-demo` — `calcite/` module (`VortexTable`, `VortexSchema`,
  `VortexAggregates`, `CalciteSmokeTest`, `OhlcSqlDemoTest`)
- [ADR 0013 — Compute primitives](0013-compute-primitives.md) §6 (aggregate push-down via
  zone-map stats; `SUM`/`NULL_COUNT` writer increment)
- [ADR 0016 — vortex-arrow bridge](0016-vortex-arrow-bridge.md) (the "bulk extract" door)
- [ADR 0017 — in-house FlatBuffers codegen](0017-in-house-flatbuffers-codegen.md) (JDK 25 /
  generated-code pressure that motivated the Janino smoke test)
- Apache Calcite adapter SPI — https://calcite.apache.org/docs/adapter.html
- [Proposal: Adding Vortex as an Iceberg File Format](https://docs.google.com/document/d/1g0EJDr3DkSmfAce8C94zA2JyWRj2SeNeAeiYOotnSww/edit)
  — Iceberg's File Format API (1.11.0) integration plan; this adapter is the working
  demonstration of the projection/filter/aggregate push-down it relies on (push-down source,
  metrics from zone-map statistics)
