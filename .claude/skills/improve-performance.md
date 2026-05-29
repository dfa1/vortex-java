---
name: improve-performance
description: >
  Guide iterative Java performance optimization using JMH benchmarks and JFR profiling.
  Use this skill whenever the user wants to profile, benchmark, or optimize Java code performance,
  mentions JMH, JFR, GC pauses, allocation hotspots, TLB misses, throughput regression,
  or asks Claude Code to "make this faster", "find the bottleneck", "reduce allocations",
  or "profile this code". Also triggers for HugeTLB / large pages JVM investigations,
  or any request to run a benchmark and act on the results.
---

# JMH + JFR Performance Optimization Skill

A skill for running JMH benchmarks, collecting JFR profiles, and driving iterative
performance improvements in Java/Maven projects.

## Workflow Overview

```
setup → benchmark → profile → analyse → change → repeat
```

---

## Step 1 — Verify readiness

Confirm the workload under test is already covered by a JMH benchmark in the `performance/` module
(e.g. `RustVsJavaReadBenchmark`). If the column / codec / path is not benched, add it before profiling.
Always pass a fully-qualified JMH filter (`ClassName.methodName`) when invoking the harness — never
class-only or empty.

---

## Step 2 — Use the existing benchmark script

`./benchmark.sh` in the repo root drives JMH + JFR. It runs:

```bash
./mvnw package -DskipTests -q -pl performance --also-make
java -jar performance/target/benchmarks.jar \
  ${BENCHMARK_FILTER} \
  -f 1 -wi 3 -i 5 \
  -rf json -rff performance/target/jmh-results.json \
  -jvmArgs "-XX:StartFlightRecording=filename=performance/target/recording.jfr,settings=profile"
```

Never substitute `mvn` for `./mvnw` (project mandates the wrapper) and never run `mvn install`.

---

## Step 3 — Run the baseline

```bash
./benchmark.sh RustVsJavaReadBenchmark.javaReadVolume
```

Read both output files:

- `performance/target/jmh-results.json` — throughput / latency per benchmark
- `performance/target/recording-filtered.json` — JFR events

Store the baseline score. Compare every subsequent run against it.

---

## Step 4 — Analyse results

Key things to look for:

**In jmh-results.json:**

- `primaryMetric.score` — the main result (ops/s or ns/op depending on mode)
- `primaryMetric.scoreError` — high error = unstable benchmark, increase `-i`
- Compare `AverageTime` vs `Throughput` to understand the shape of the workload

**In recording-filtered.json:**

- `jdk.ObjectAllocationInNewTLAB` — allocation hotspots; look for large `allocationSize`
  or high-frequency small allocations in hot methods
- `jdk.GarbageCollection` — GC pause duration and frequency; long pauses = allocation pressure
- `jdk.TLBMiss` — high counts suggest memory access patterns not benefiting from huge pages
- `jdk.GCHeapSummary` — heap growth between events reveals live-set size

**Priority order for investigation:**

1. If GC pauses are long → reduce allocations first
2. If allocations are high but pauses are short → object pooling or value types
3. If TLB misses are high → evaluate huge pages or access pattern changes
4. If CPU is saturated with no GC/alloc issues → algorithmic / cache-locality problem

---

## Step 5 — Apply one change at a time

**Critical rule: change one thing per iteration.**

Do not batch multiple optimizations. Each iteration must produce a clear
before/after comparison so regressions are traceable.

Common optimizations to consider (in order of typical impact):

1. **Allocate decode output from `ctx.arena()`** — hard rule from `CLAUDE.md`. Never
   `new byte[n]` + `MemorySegment.ofArray()` for codec output: heap allocation, GC pressure,
   extra copy. Use `ctx.arena().allocate(n * elemBytes, alignment)` so the buffer lives on the
   confined arena tied to the `VortexFile`.
2. **Reduce allocations** elsewhere — reuse objects, use primitives, avoid boxing.
3. **Hoist `ValueLayout` constants** — declare `static final ValueLayout.OfXxx L = ...` so JIT
   constant-folds the stride / alignment / order. Inline `ValueLayout.JAVA_LONG_UNALIGNED` on each
   call defeats this.
4. **Use `getAtIndex` / `setAtIndex`** in tight loops over a `MemorySegment` — stride is implicit,
   bounds check hoists, and the auto-vectoriser reads the shape cleanly.
5. **Aligned arena allocation** — `arena.allocate(n, 64)` keeps SIMD-friendly addresses.
6. **Improve data locality** — colocate fields accessed together, prefer flat arrays / segments
   over linked structures.
7. **Avoid synchronization on hot paths** — `VarHandle`, `AtomicLong`, lock-free structures.
8. **Reduce GC pressure** — pool expensive objects, avoid finalizers, off-heap where justified.
9. **Enable huge pages** — add `-XX:+UseTransparentHugePages` to `jvmArgs` for comparison.

After each change, run `./benchmark.sh` and compare the new score against the stored baseline.

---

## Step 6 — Report progress

After each iteration, report:

```
Iteration N
  Change   : <one-line description of what changed>
  Before   : <score> ± <error> <unit>
  After    : <score> ± <error> <unit>
  Delta    : <+/- %>
  JFR note : <key observation from JFR events>
  Next     : <what to investigate next>
```

If performance regressed, revert the change immediately and explain why it likely hurt.

---

## Step 7 — Stop conditions

Stop iterating when any of the following is true:

- The goal stated by the user has been reached
- Three consecutive iterations produce < 2% improvement
- All obvious hotspots from JFR have been addressed
- The user says stop

Produce a final summary table of all iterations.

---

## Important constraints

- **Never modify the benchmark harness** (`@Benchmark`, `@Setup`, `@TearDown`) unless
  the user explicitly asks. Benchmark changes invalidate all prior comparisons.
- **Do not change JMH flags** (`-f`, `-wi`, `-i`) between iterations. Consistency is required
  for valid comparisons.
- **Keep JFR event filter consistent** across all runs.
- If `jmh-results.json` shows `scoreError` > 10% of `score`, warn the user the benchmark
  is noisy and suggest increasing `-i` before drawing conclusions.
