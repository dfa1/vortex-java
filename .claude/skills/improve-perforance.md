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

Check if the required part is covered by existing performance tests under the performance module.
If anything is missing, fix it before proceeding.

---

## Step 2 — Create the benchmark script

Create `benchmark.sh` in the project root if it does not exist:

```bash
#!/bin/bash
set -e

BENCHMARK_FILTER="${1:-}"
JFR_EVENTS="${2:-jdk.GarbageCollection,jdk.ObjectAllocationInNewTLAB,jdk.TLBMiss,jdk.CPULoad,jdk.GCHeapSummary}"

echo "=== Building ==="
mvn package -DskipTests -q

echo "=== Running benchmarks ==="
java -jar target/benchmarks.jar \
  ${BENCHMARK_FILTER} \
  -f 1 -wi 3 -i 5 \
  -rf json -rff target/jmh-results.json \
  -jvmArgs "-XX:StartFlightRecording=filename=target/recording.jfr,settings=profile"

echo "=== Extracting JFR events ==="
jfr print --json \
  --events "${JFR_EVENTS}" \
  target/recording.jfr > target/recording-filtered.json

echo "=== Done ==="
echo "JMH results : target/jmh-results.json"
echo "JFR profile : target/recording-filtered.json"
```

Make it executable: `chmod +x benchmark.sh`

---

## Step 3 — Run the baseline

```bash
./benchmark.sh
```

Read both output files:

- `target/jmh-results.json` — throughput / latency per benchmark
- `target/recording-filtered.json` — JFR events

Store the baseline score. You will compare every subsequent run against it.

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

1. **Reduce allocations** — reuse objects, use primitives, avoid boxing, use `StringBuilder`
2. **Improve data locality** — colocate fields accessed together, prefer arrays over linked structures
3. **Avoid synchronization on hot paths** — use `VarHandle`, `AtomicLong`, lock-free structures
4. **Reduce GC pressure** — pool expensive objects, avoid finalizers, use off-heap where justified
5. **Enable huge pages** — add `-XX:+UseTransparentHugePages` to `jvmArgs` in benchmark for comparison

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
