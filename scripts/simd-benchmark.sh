#!/usr/bin/env bash
# A/B of the auto-vectorized SIMD kernels against the Vector API ones (SimdVectorApiBenchmark),
# one group per set of valid (kernel, ptype) pairs, then a Markdown table of how many times faster
# the Vector API is than the auto-vectorized loop (above 1 = the Vector API wins).
#
# Each score carries JMH's 99.9% confidence interval. The speedup range divides the intervals the
# conservative way (auto low / Vector API high, auto high / Vector API low), and a row is a win or a
# loss only when that whole range is clear of 1: otherwise it is within noise and says so.
#
#   scripts/simd-benchmark.sh [output-dir]
#
# Environment (the defaults give intervals tight enough to call most rows; a quick smoke run can use
# FORKS=1 WARMUP=1 ITERATIONS=1 SIZE=4096):
#   JAR         benchmarks jar (default performance/target/benchmarks.jar; build it with
#               ./mvnw package -DskipTests -DskipShade=false -pl performance -am)
#   SIZE        elements per op (default 262144)
#   FORKS, WARMUP, ITERATIONS   JMH forks, warmup and measurement iterations (1 s each)
set -euo pipefail

OUT=${1:-performance/target/simd-benchmark}
JAR=${JAR:-performance/target/benchmarks.jar}
SIZE=${SIZE:-262144}
FORKS=${FORKS:-3}
WARMUP=${WARMUP:-3}
ITERATIONS=${ITERATIONS:-5}

mkdir -p "$OUT"

run() { # name, benchmark regex, ptypes
  java --add-opens java.base/java.nio=ALL-UNNAMED --enable-native-access=ALL-UNNAMED \
    --sun-misc-unsafe-memory-access=allow -jar "$JAR" "$2" \
    -p "size=$SIZE" -p "ptype=$3" -f "$FORKS" -wi "$WARMUP" -w 1 -i "$ITERATIONS" -r 1 \
    -rf json -rff "$OUT/$1.json" > "$OUT/$1.log" 2>&1
}

B='SimdVectorApiBenchmark\.'
run general   "${B}(runs_noRuns|allEqual_constant|minMax|widenArray|widenSegment|narrowSegment)\$" I8,I16,I32,I64,F64
run narrow    "${B}narrowArray\$" I8,I16,I32,I64,F64
run maxu      "${B}maxUnsigned\$" I8,I16,I32,I64
run sum       "${B}sum\$" I8,I16,I32,I64
run sumf      "${B}sumFloating\$" F32,F64
run bools     "${B}allEqual_booleans\$" I8
run fastlanes "${B}(undelta|delta|pack)\$" I8,I16,I32,I64

echo "| kernel | type | auto-vectorized (us) | Vector API (us) | Vector API is | |"
echo "|---|---|---:|---:|---:|---|"
jq -s -r '
  def f: . * 100 | round / 100;
  [.[][] | {b: (.benchmark | split(".") | last), p: .params.ptype, i: .params.impl,
            s: .primaryMetric.score, e: .primaryMetric.scoreError,
            lo: .primaryMetric.scoreConfidence[0], hi: .primaryMetric.scoreConfidence[1]}]
  | group_by([.b, .p])[]
  | select(length == 2)
  | (map(select(.i == "auto"))[0]) as $a
  | (map(select(.i == "vector"))[0]) as $v
  | ($a.s / $v.s) as $x
  | (if ($a.lo | type) == "number" and ($v.hi | type) == "number" then ($a.lo / $v.hi) else null end) as $worst
  | (if ($a.hi | type) == "number" and ($v.lo | type) == "number" then ($a.hi / $v.lo) else null end) as $best
  | (if $worst == null or $best == null then "n/a (one iteration)"
     elif $worst > 1 then "wins" elif $best < 1 then "loses" else "within noise" end) as $verdict
  | (if $worst == null then "" else " (\($worst | f)–\($best | f))" end) as $range
  | (if ($a.e | type) == "number" then " ± \($a.e | f)" else "" end) as $ae
  | (if ($v.e | type) == "number" then " ± \($v.e | f)" else "" end) as $ve
  | "| \(.[0].b) | \(.[0].p) | \($a.s | f)\($ae) | \($v.s | f)\($ve) | \($x | f)x\($range) | \($verdict) |"
' "$OUT"/*.json
