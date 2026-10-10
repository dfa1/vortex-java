#!/usr/bin/env bash
# A/B of the auto-vectorized SIMD kernels against the Vector API ones (SimdVectorApiBenchmark),
# one group per set of valid (kernel, ptype) pairs, then a Markdown table of how many times faster
# the Vector API is than the auto-vectorized loop (above 1 = the Vector API wins).
#
#   scripts/simd-benchmark.sh [output-dir]
#
# Environment (defaults suit a CI smoke run; use FORKS=3 ITERATIONS=5 for numbers worth recording):
#   JAR         benchmarks jar (default performance/target/benchmarks.jar; build it with
#               ./mvnw package -DskipTests -DskipShade=false -pl performance -am)
#   SIZE        elements per op (default 262144)
#   FORKS, WARMUP, ITERATIONS   JMH forks, warmup and measurement iterations (1 s each)
set -euo pipefail

OUT=${1:-performance/target/simd-benchmark}
JAR=${JAR:-performance/target/benchmarks.jar}
SIZE=${SIZE:-262144}
FORKS=${FORKS:-1}
WARMUP=${WARMUP:-2}
ITERATIONS=${ITERATIONS:-3}

mkdir -p "$OUT"

run() { # name, benchmark regex, ptypes
  java --add-opens java.base/java.nio=ALL-UNNAMED --enable-native-access=ALL-UNNAMED \
    --sun-misc-unsafe-memory-access=allow -jar "$JAR" "$2" \
    -p "size=$SIZE" -p "ptype=$3" -f "$FORKS" -wi "$WARMUP" -w 1 -i "$ITERATIONS" -r 1 \
    -rf json -rff "$OUT/$1.json" > "$OUT/$1.log" 2>&1
}

B='SimdVectorApiBenchmark\.'
run general   "${B}(runs_noRuns|allEqual_constant|minMax|widenArray|widenSegment|narrowSegment)\$" I8,I16,I32,I64,F32,F64
run narrow    "${B}narrowArray\$" I8,I16,I32,I64,F32,F64
run maxu      "${B}maxUnsigned\$" I8,I16,I32,I64
run sum       "${B}sum\$" I8,I16,I32,I64
run sumf      "${B}sumFloating\$" F32,F64
run bools     "${B}allEqual_booleans\$" I8
run fastlanes "${B}(undelta|delta|pack)\$" I8,I16,I32,I64

echo "| kernel | type | auto-vectorized (us) | Vector API (us) | Vector API is |"
echo "|---|---|---:|---:|---:|"
jq -s -r '
  [.[][] | {b: (.benchmark | split(".") | last), p: .params.ptype, i: .params.impl, s: .primaryMetric.score}]
  | group_by([.b, .p])[]
  | select(length == 2)
  | (map(select(.i == "auto"))[0].s) as $a
  | (map(select(.i == "vector"))[0].s) as $v
  | "| \(.[0].b) | \(.[0].p) | \($a * 100 | round / 100) | \($v * 100 | round / 100) | \(($a / $v * 100 | round / 100))x |"
' "$OUT"/*.json
