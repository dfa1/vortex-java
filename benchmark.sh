#!/bin/bash
set -e

BENCHMARK_FILTER="${1:-}"
JFR_EVENTS="${2:-jdk.GarbageCollection,jdk.ObjectAllocationInNewTLAB,jdk.TLBMiss,jdk.CPULoad,jdk.GCHeapSummary}"

echo "=== Building ==="
./mvnw package -DskipTests -q -pl performance --also-make

echo "=== Running benchmarks ==="
java -jar performance/target/benchmarks.jar \
  ${BENCHMARK_FILTER} \
  -f 1 -wi 3 -i 5 \
  -rf json -rff performance/target/jmh-results.json \
  -jvmArgs "-XX:StartFlightRecording=filename=performance/target/recording.jfr,settings=profile"

echo "=== Extracting JFR events ==="
jfr print --json \
  --events "${JFR_EVENTS}" \
  performance/target/recording.jfr > performance/target/recording-filtered.json

echo "=== Done ==="
echo "JMH results : performance/target/jmh-results.json"
echo "JFR profile : performance/target/recording-filtered.json"
