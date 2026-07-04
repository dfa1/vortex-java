# ADR 0006: Benchmark publishing strategy

- **Status:** Accepted — CI workflow deleted; `bench-publish` script pending
- **Date:** 2026-06-13
- **Deciders:** project maintainer
- **Related:** [TODO.md §Performance](../TODO.md),
  `.github/workflows/benchmark.yml`

## Context

The project has a nightly CI benchmark workflow (`.github/workflows/benchmark.yml`)
that runs `./bench JavaVsJni` on a GitHub Actions `ubuntu-latest` runner and pushes
results to `gh-pages/dev/bench` via `benchmark-action/github-action-benchmark`.

### Why CI benchmarks are not the source of truth

GitHub Actions shared runners share physical hosts with other tenants. JMH
benchmarks are sensitive to CPU frequency scaling, SMT contention, and OS
scheduler noise. Typical variance on shared runners is **20–40%** per run —
larger than the signal for a 10–15% decode optimization. A number published
from a shared runner cannot be cited, compared across commits, or used to
claim a performance target is met.

The current workflow does carry a regression threshold (`alert-threshold: 130%`)
with `comment-on-alert: true`. That is a **2.3 σ** guard relative to the noise
floor — it catches catastrophic regressions (5–10×) but misses 10–30%
regressions, which are the ones that actually matter during encoder
optimization work.

### The alternative: local-run publish

The developer has consistent access to one Apple M5 machine. A `bench-publish`
script that:
1. Runs `./bench` locally
2. Captures JMH JSON + JFR profile + hardware metadata + JDK version + commit SHA
3. Pushes a dated file to `gh-pages`

produces **reproducible**, **citeable** numbers. Two runs from the same commit
on the same machine within minutes of each other should show < 3% variance on
the hot loops in scope.

## Decision

**Replace CI benchmark publishing with local-run publish.** Drop
`.github/workflows/benchmark.yml`. Add a `bench-publish` script.

### What the `bench-publish` script does

```bash
#!/usr/bin/env bash
# bench-publish [filter]  — run benchmarks locally and push results to gh-pages
set -euo pipefail

FILTER="${1:-.+}"
DATE=$(date +%Y-%m-%d)
COMMIT=$(git rev-parse --short HEAD)
OUT="performance/target/benchmark-result.json"
META="performance/target/benchmark-meta.json"

# 1. Run benchmarks
./bench "$FILTER"

# 2. Capture metadata alongside JMH JSON
cat > "$META" <<EOF
{
  "date":   "$DATE",
  "commit": "$COMMIT",
  "jdk":    "$(java -version 2>&1 | head -1)",
  "cpu":    "$(sysctl -n machdep.cpu.brand_string 2>/dev/null || lscpu | grep 'Model name' | cut -d: -f2 | xargs)",
  "os":     "$(uname -srm)"
}
EOF

# 3. Push to gh-pages under a dated path
DEST="benchmark-results/${DATE}-${COMMIT}"
git worktree add /tmp/gh-pages gh-pages
cp "$OUT"  "/tmp/gh-pages/${DEST}.json"
cp "$META" "/tmp/gh-pages/${DEST}-meta.json"
# Keep a stable pointer for the jmh.morethan.io default URL
cp "$OUT"  /tmp/gh-pages/benchmark-result.json
cd /tmp/gh-pages
git add "${DEST}.json" "${DEST}-meta.json" benchmark-result.json
git commit -m "bench: ${DATE} ${COMMIT}"
git push origin gh-pages
git worktree remove /tmp/gh-pages --force
cd -
```

### Viewing results

- **Latest run**: `https://jmh.morethan.io/?source=https://dfa1.github.io/vortex-java/benchmark-result.json`
- **Two-commit comparison**: append `?source=url1,url2` with dated file paths

### Regression detection without CI

Without the CI workflow, regressions are caught by:

1. **Running `bench-publish` before and after an optimization PR.** The
   commit SHAs in the filenames make A/B comparison mechanical.
2. **Adding a JMH regression test** (`@BenchmarkMode(Throughput)` with an
   `assert` or a baseline comparison in the performance module) — not
   automated today but a future option.
3. **The integration test suite** — correctness regressions surface
   immediately; performance regressions are caught when a reviewer asks
   for a benchmark.

A self-hosted runner on the M5 (or a future dedicated CI machine) would
restore automated regression detection. Defer until a second contributor
joins or the project gets a dedicated runner.

### What to include in each published run

| Artifact | Why |
|----------|-----|
| JMH JSON | Machine-readable; jmh.morethan.io input |
| `benchmark-meta.json` | CPU, JDK, OS, commit — numbers rot without provenance |
| JFR profile (optional) | Add `-prof jfr` to `./bench` when diagnosing; do not publish routinely (large) |

README table is updated manually after each significant result. The table
cites the commit SHA and hardware so the numbers can be reproduced.

### What happens to `.github/workflows/benchmark.yml`

Deleted (commit `eb6a2247` + follow-up). The `benchmark-action/github-action-benchmark`
tool is removed from the project. The `gh-pages` branch retains its history;
the `dev/bench` directory of CI-produced results is left in place but no
longer updated.

## Consequences

### Positive
- Published numbers are reproducible and citeable.
- No shared-runner noise. A 5% speedup is visible; a 5% regression is visible.
- Hardware context is captured alongside each result.
- jmh.morethan.io supports multi-result comparison natively; no bespoke
  dashboard code needed.

### Negative
- No automated regression detection on PR. Regressions caught only if
  a reviewer asks for a benchmark or the author runs `bench-publish`.
- `bench-publish` requires local Java + Maven build; not runnable from a
  mobile device / tablet.
- Numbers accumulate only when the developer actively publishes. Long
  gaps between optimization cycles leave stale README tables.

### Risk
- If a second contributor joins and cannot reproduce numbers on different
  hardware, the single-machine baseline becomes a coordination problem.
  Mitigation: `benchmark-meta.json` documents the reference hardware;
  normalize by throughput ratio (new hardware / reference) rather than
  absolute scores.

## References

- [jmh.morethan.io](https://jmh.morethan.io) — JMH JSON viewer with
  multi-source comparison
- [benchmark-action/github-action-benchmark](https://github.com/benchmark-action/github-action-benchmark)
  — the tool being retired
- [TODO.md §Publish benchmarks](../TODO.md)
- [TODO.md §Publish reproducible perf artifacts](../TODO.md)
