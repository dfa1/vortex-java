window.BENCHMARK_DATA = {
  "lastUpdate": 1781299701602,
  "repoUrl": "https://github.com/dfa1/vortex-java",
  "entries": {
    "Benchmark": [
      {
        "commit": {
          "author": {
            "name": "Davide Angelocola",
            "username": "dfa1",
            "email": "davide.angelocola@gmail.com"
          },
          "committer": {
            "name": "Davide Angelocola",
            "username": "dfa1",
            "email": "davide.angelocola@gmail.com"
          },
          "id": "a16e919b909af4c29af8b7abcb2627e77ca87c54",
          "message": "fix(ci): limit benchmark scope and iterations in CI\n\nPass JMH args through bench script. In CI, run only RustVsJava\nbenchmarks (skip 3GB BigFile setup) with -wi 1 -i 3 -f 1 to\nkeep runtime under 5 minutes.\n\nCo-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>",
          "timestamp": "2026-06-04T05:07:35Z",
          "url": "https://github.com/dfa1/vortex-java/commit/a16e919b909af4c29af8b7abcb2627e77ca87c54"
        },
        "date": 1780551396785,
        "tool": "jmh",
        "benches": [
          {
            "name": "io.github.dfa1.vortex.performance.RustVsJavaReadBenchmark.javaReadCascading",
            "value": 37.07964506311847,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "io.github.dfa1.vortex.performance.RustVsJavaReadBenchmark.javaReadClose",
            "value": 23.62549601208856,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "io.github.dfa1.vortex.performance.RustVsJavaReadBenchmark.javaReadSymbol",
            "value": 10.773279199525296,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "io.github.dfa1.vortex.performance.RustVsJavaReadBenchmark.javaReadVolume",
            "value": 37.42012482178311,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "io.github.dfa1.vortex.performance.RustVsJavaReadBenchmark.jniReadClose",
            "value": 1.999072223630875,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "io.github.dfa1.vortex.performance.RustVsJavaReadBenchmark.jniReadSymbol",
            "value": 0.6645766567296644,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "io.github.dfa1.vortex.performance.RustVsJavaReadBenchmark.jniReadVolume",
            "value": 3.964346070290889,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "io.github.dfa1.vortex.performance.RustVsJavaWriteBenchmark.javaWrite",
            "value": 0.41637285115797257,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "io.github.dfa1.vortex.performance.RustVsJavaWriteBenchmark.javaWriteCascading",
            "value": 0.33923809206624284,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "io.github.dfa1.vortex.performance.RustVsJavaWriteBenchmark.jniWrite",
            "value": 0.013067648023707664,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "name": "Davide Angelocola",
            "username": "dfa1",
            "email": "davide.angelocola@gmail.com"
          },
          "committer": {
            "name": "Davide Angelocola",
            "username": "dfa1",
            "email": "davide.angelocola@gmail.com"
          },
          "id": "4253ea5ba83e55270250a653ca42451b80dfdb87",
          "message": "docs: remove completed JDK 26 build matrix TODO\n\nCo-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>",
          "timestamp": "2026-06-04T21:30:30Z",
          "url": "https://github.com/dfa1/vortex-java/commit/4253ea5ba83e55270250a653ca42451b80dfdb87"
        },
        "date": 1780609267895,
        "tool": "jmh",
        "benches": [
          {
            "name": "io.github.dfa1.vortex.performance.RustVsJavaReadBenchmark.javaReadCascading",
            "value": 34.73412673017375,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "io.github.dfa1.vortex.performance.RustVsJavaReadBenchmark.javaReadClose",
            "value": 23.213366666917082,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "io.github.dfa1.vortex.performance.RustVsJavaReadBenchmark.javaReadVolume",
            "value": 34.739552051152124,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "io.github.dfa1.vortex.performance.RustVsJavaReadBenchmark.jniReadClose",
            "value": 1.826788113082755,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "io.github.dfa1.vortex.performance.RustVsJavaReadBenchmark.jniReadSymbol",
            "value": 0.6157357455992578,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "io.github.dfa1.vortex.performance.RustVsJavaReadBenchmark.jniReadVolume",
            "value": 3.5239271090875484,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "io.github.dfa1.vortex.performance.RustVsJavaWriteBenchmark.javaWrite",
            "value": 0.7345223414023522,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "io.github.dfa1.vortex.performance.RustVsJavaWriteBenchmark.javaWriteCascading",
            "value": 0.3751139782900637,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "io.github.dfa1.vortex.performance.RustVsJavaWriteBenchmark.jniWrite",
            "value": 0.012917204484283023,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "name": "Davide Angelocola",
            "username": "dfa1",
            "email": "davide.angelocola@gmail.com"
          },
          "committer": {
            "name": "Davide Angelocola",
            "username": "dfa1",
            "email": "davide.angelocola@gmail.com"
          },
          "id": "801633b171a0c52e70cb285a1f967264d2108134",
          "message": "[maven-release-plugin] prepare for next development iteration",
          "timestamp": "2026-06-05T21:21:00Z",
          "url": "https://github.com/dfa1/vortex-java/commit/801633b171a0c52e70cb285a1f967264d2108134"
        },
        "date": 1780695328390,
        "tool": "jmh",
        "benches": [
          {
            "name": "io.github.dfa1.vortex.performance.RustVsJavaReadBenchmark.javaReadCascading",
            "value": 28.908963732082018,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "io.github.dfa1.vortex.performance.RustVsJavaReadBenchmark.javaReadClose",
            "value": 24.033752498112403,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "io.github.dfa1.vortex.performance.RustVsJavaReadBenchmark.javaReadVolume",
            "value": 35.60017254176457,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "io.github.dfa1.vortex.performance.RustVsJavaReadBenchmark.jniReadClose",
            "value": 1.8191862506274543,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "io.github.dfa1.vortex.performance.RustVsJavaReadBenchmark.jniReadSymbol",
            "value": 0.6149197709205404,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "io.github.dfa1.vortex.performance.RustVsJavaReadBenchmark.jniReadVolume",
            "value": 3.3779569748272884,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "io.github.dfa1.vortex.performance.RustVsJavaWriteBenchmark.javaWrite",
            "value": 0.7348825431386921,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "io.github.dfa1.vortex.performance.RustVsJavaWriteBenchmark.javaWriteCascading",
            "value": 0.257446797431972,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "io.github.dfa1.vortex.performance.RustVsJavaWriteBenchmark.jniWrite",
            "value": 0.012840988644632963,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "name": "Davide Angelocola",
            "username": "dfa1",
            "email": "davide.angelocola@gmail.com"
          },
          "committer": {
            "name": "Davide Angelocola",
            "username": "dfa1",
            "email": "davide.angelocola@gmail.com"
          },
          "id": "10a7776e5cdc842806377bc31071aa1d18b4ccb3",
          "message": "fix(security): protect against zip-bomb attacks via inflated row_count\n\nTwo independent OOM vectors, both exploitable with files under 200 bytes\nclaiming 10⁹ rows:\n\n1. ConstantEncoding.Decoder stored n copies of the constant value.\n   A ~130-byte file triggers an 8 GB allocation in decode() before\n   touching any actual data.\n   Fix: store one element only; array reports length=n with O(1) buffer.\n   Callers that need all n values replicate from index 0.\n\n2. ScanIterator.expandDictPrimitive pre-allocated n × elemBytes from\n   the attacker-controlled codesLayout.rowCount() before reading a\n   single code byte. PrimitiveEncoding wraps the mmap'd segment without\n   allocating, making the rowCount inflation invisible at decode time.\n   Fix: validate bufferCodes >= n in decodeDictLayout before expansion;\n   the mmap-bounded buffer makes inflation detectable for direct-mapped\n   encodings, and full-decode encodings (bitpacked etc.) already fill\n   their buffer to n × elemBytes during decode.\n\nRegression tests in ZipBombTest use row counts safe for CI: 10M for\nattack 1 (fails with AssertionError if reverted, not OOM) and 100 for\nattack 2 (wrong exception type → clean failure if check is removed).\n\nCo-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>",
          "timestamp": "2026-06-06T20:06:28Z",
          "url": "https://github.com/dfa1/vortex-java/commit/10a7776e5cdc842806377bc31071aa1d18b4ccb3"
        },
        "date": 1780779507164,
        "tool": "jmh",
        "benches": [
          {
            "name": "io.github.dfa1.vortex.performance.RustVsJavaReadBenchmark.javaReadCascading",
            "value": 31.66280988605688,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "io.github.dfa1.vortex.performance.RustVsJavaReadBenchmark.javaReadSymbol",
            "value": 22.925515267200524,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "io.github.dfa1.vortex.performance.RustVsJavaReadBenchmark.javaReadVolume",
            "value": 39.74299151265897,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "io.github.dfa1.vortex.performance.RustVsJavaReadBenchmark.jniReadClose",
            "value": 24.37365981525212,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "io.github.dfa1.vortex.performance.RustVsJavaReadBenchmark.jniReadSymbol",
            "value": 4.62324597463762,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "io.github.dfa1.vortex.performance.RustVsJavaReadBenchmark.jniReadVolume",
            "value": 25.70404600814548,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "io.github.dfa1.vortex.performance.RustVsJavaWriteBenchmark.javaWrite",
            "value": 0.5982789953346004,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "io.github.dfa1.vortex.performance.RustVsJavaWriteBenchmark.javaWriteCascading",
            "value": 0.23862717264647415,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "io.github.dfa1.vortex.performance.RustVsJavaWriteBenchmark.jniWrite",
            "value": 0.3253265982372833,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "name": "Davide Angelocola",
            "username": "dfa1",
            "email": "davide.angelocola@gmail.com"
          },
          "committer": {
            "name": "Davide Angelocola",
            "username": "dfa1",
            "email": "davide.angelocola@gmail.com"
          },
          "id": "cfcc6d0dca9f74a37bf0911d0bf17bd1fa024bf7",
          "message": "docs: bump install snippets to vortex 0.4.0\n\nREADME and tutorial both showed the previous v0.3.2 Maven coordinates;\n0.4.0 is now live on Maven Central.\n\nCo-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>",
          "timestamp": "2026-06-07T20:53:30Z",
          "url": "https://github.com/dfa1/vortex-java/commit/cfcc6d0dca9f74a37bf0911d0bf17bd1fa024bf7"
        },
        "date": 1780866233468,
        "tool": "jmh",
        "benches": [
          {
            "name": "io.github.dfa1.vortex.performance.RustVsJavaReadBenchmark.javaReadCascading",
            "value": 18.7684813140592,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "io.github.dfa1.vortex.performance.RustVsJavaReadBenchmark.javaReadSymbol",
            "value": 22.854885131345167,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "io.github.dfa1.vortex.performance.RustVsJavaReadBenchmark.javaReadVolume",
            "value": 21.60710935244504,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "io.github.dfa1.vortex.performance.RustVsJavaReadBenchmark.jniReadClose",
            "value": 23.868488785538048,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "io.github.dfa1.vortex.performance.RustVsJavaReadBenchmark.jniReadSymbol",
            "value": 4.537651162912478,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "io.github.dfa1.vortex.performance.RustVsJavaReadBenchmark.jniReadVolume",
            "value": 25.34088027611037,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "io.github.dfa1.vortex.performance.RustVsJavaWriteBenchmark.javaWrite",
            "value": 0.5944295589452299,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "io.github.dfa1.vortex.performance.RustVsJavaWriteBenchmark.javaWriteCascading",
            "value": 0.23130996162591824,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "io.github.dfa1.vortex.performance.RustVsJavaWriteBenchmark.jniWrite",
            "value": 0.31563548376065786,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "name": "Davide Angelocola",
            "username": "dfa1",
            "email": "davide.angelocola@gmail.com"
          },
          "committer": {
            "name": "Davide Angelocola",
            "username": "dfa1",
            "email": "davide.angelocola@gmail.com"
          },
          "id": "ab3ca3fb9082018827669f54d48ebb4cf3435a58",
          "message": "perf(bitpacked): hoist per-row bookkeeping out of unpackLoop64 block loop\n\nInner row body computed seven row-dependent quantities (currWord, nextWord,\nshift, remainingBits, currentBits, loMask, hiMask, plus FL_ORDER lookup)\nfrom `row` and `bitWidth` on every iteration. `bitWidth` is constant for\nthe duration of unpackLoop64, so these only need to be computed once.\n\nFor a 10M-row I64 column at bitWidth ~20, this drops 7 × 64 × ~9750 blocks\n≈ 4.4M ops out of the hot path. JFR shows BitpackedEncoding$Decoder.unpackLoop64\nas the largest visible Java frame (31% of RUNNABLE time on\nRustVsJavaReadBenchmark.javaReadVolume).\n\nPre-compute eight 64-entry int[]/long[] tables at method entry, look up\nper row inside the block loop. Inner lane loop body is unchanged\n(buf.get / >>> / & / out.set).\n\nBench (M5, JDK 25, 5 warmup × 3s + 10 measurement × 5s, fork 1):\n\n  RustVsJavaReadBenchmark.javaReadVolume\n    before: 109.319 ± 1.042 ops/s\n    after:  113.526 ± 1.033 ops/s   (+3.8 %)\n\nError bars don't overlap so the gain is real, just modest. Vector API\nrewrite of the inner lane loop (TODO line ~) would be the next material\nstep; this is a no-risk micro-cleanup pending that work.\n\nCo-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>",
          "timestamp": "2026-06-08T21:44:44Z",
          "url": "https://github.com/dfa1/vortex-java/commit/ab3ca3fb9082018827669f54d48ebb4cf3435a58"
        },
        "date": 1780955198968,
        "tool": "jmh",
        "benches": [
          {
            "name": "io.github.dfa1.vortex.performance.RustVsJavaReadBenchmark.javaReadCascading",
            "value": 33.31412861587643,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "io.github.dfa1.vortex.performance.RustVsJavaReadBenchmark.javaReadClose",
            "value": 28.03234639559291,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "io.github.dfa1.vortex.performance.RustVsJavaReadBenchmark.javaReadSymbol",
            "value": 39.265354990617,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "io.github.dfa1.vortex.performance.RustVsJavaReadBenchmark.javaReadVolume",
            "value": 42.37086578959571,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "io.github.dfa1.vortex.performance.RustVsJavaReadBenchmark.jniReadClose",
            "value": 25.986498988974603,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "io.github.dfa1.vortex.performance.RustVsJavaReadBenchmark.jniReadSymbol",
            "value": 4.595693055019596,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "io.github.dfa1.vortex.performance.RustVsJavaReadBenchmark.jniReadVolume",
            "value": 26.655738328332063,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "io.github.dfa1.vortex.performance.RustVsJavaWriteBenchmark.javaWrite",
            "value": 0.6439435691730853,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "io.github.dfa1.vortex.performance.RustVsJavaWriteBenchmark.javaWriteCascading",
            "value": 0.22850632349248337,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "io.github.dfa1.vortex.performance.RustVsJavaWriteBenchmark.jniWrite",
            "value": 0.2965529220075181,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "name": "Davide Angelocola",
            "username": "dfa1",
            "email": "davide.angelocola@gmail.com"
          },
          "committer": {
            "name": "Davide Angelocola",
            "username": "dfa1",
            "email": "davide.angelocola@gmail.com"
          },
          "id": "57a5a3871cea3c07a8d3da766b308d807c3c30fc",
          "message": "fix(cli): silence dev.hardwood VectorSupport INFO log at startup\n\nHardwood emits a JUL INFO line on every cold start telling the user\nSIMD is disabled. Useful in benchmarks, noise in the CLI. Raise the\npackage logger to WARNING in VortexCli's static init.\n\nCo-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>",
          "timestamp": "2026-06-09T16:53:06Z",
          "url": "https://github.com/dfa1/vortex-java/commit/57a5a3871cea3c07a8d3da766b308d807c3c30fc"
        },
        "date": 1781040861323,
        "tool": "jmh",
        "benches": [
          {
            "name": "io.github.dfa1.vortex.performance.RustVsJavaReadBenchmark.javaReadCascading",
            "value": 36.585899235977514,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "io.github.dfa1.vortex.performance.RustVsJavaReadBenchmark.javaReadClose",
            "value": 29.383638499535817,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "io.github.dfa1.vortex.performance.RustVsJavaReadBenchmark.javaReadSymbol",
            "value": 40.216640120194114,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "io.github.dfa1.vortex.performance.RustVsJavaReadBenchmark.javaReadVolume",
            "value": 48.59925109184558,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "io.github.dfa1.vortex.performance.RustVsJavaReadBenchmark.jniReadClose",
            "value": 26.194535380252365,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "io.github.dfa1.vortex.performance.RustVsJavaReadBenchmark.jniReadSymbol",
            "value": 4.60466406335174,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "io.github.dfa1.vortex.performance.RustVsJavaReadBenchmark.jniReadVolume",
            "value": 27.678471271653905,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "io.github.dfa1.vortex.performance.RustVsJavaWriteBenchmark.javaWrite",
            "value": 0.6411708402890054,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "io.github.dfa1.vortex.performance.RustVsJavaWriteBenchmark.javaWriteCascading",
            "value": 0.22297761408906502,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "io.github.dfa1.vortex.performance.RustVsJavaWriteBenchmark.jniWrite",
            "value": 0.31130107428340703,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "name": "Davide Angelocola",
            "username": "dfa1",
            "email": "davide.angelocola@gmail.com"
          },
          "committer": {
            "name": "Davide Angelocola",
            "username": "dfa1",
            "email": "davide.angelocola@gmail.com"
          },
          "id": "0714ca3a108b73758a2409ab9254fc7f08689fd2",
          "message": "docs(inspector): drop stale Lanterna mention from pom description\n\nLanterna was replaced by the FFM ANSI driver in 0.5.0; the pom\ndescription still claimed \"Lanterna-based TUI renderers\".\n\nCo-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>",
          "timestamp": "2026-06-10T19:55:58Z",
          "url": "https://github.com/dfa1/vortex-java/commit/0714ca3a108b73758a2409ab9254fc7f08689fd2"
        },
        "date": 1781128534234,
        "tool": "jmh",
        "benches": [
          {
            "name": "io.github.dfa1.vortex.performance.RustVsJavaReadBenchmark.javaReadCascading",
            "value": 36.77864391228895,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "io.github.dfa1.vortex.performance.RustVsJavaReadBenchmark.javaReadClose",
            "value": 27.36840051417585,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "io.github.dfa1.vortex.performance.RustVsJavaReadBenchmark.javaReadSymbol",
            "value": 37.77603067484855,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "io.github.dfa1.vortex.performance.RustVsJavaReadBenchmark.javaReadVolume",
            "value": 47.93634875604145,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "io.github.dfa1.vortex.performance.RustVsJavaReadBenchmark.jniReadClose",
            "value": 23.64510738035385,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "io.github.dfa1.vortex.performance.RustVsJavaReadBenchmark.jniReadSymbol",
            "value": 4.395982664995027,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "io.github.dfa1.vortex.performance.RustVsJavaReadBenchmark.jniReadVolume",
            "value": 24.395717144984204,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "io.github.dfa1.vortex.performance.RustVsJavaWriteBenchmark.javaWrite",
            "value": 0.6406131252920014,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "io.github.dfa1.vortex.performance.RustVsJavaWriteBenchmark.javaWriteCascading",
            "value": 0.21033230434305597,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "io.github.dfa1.vortex.performance.RustVsJavaWriteBenchmark.jniWrite",
            "value": 0.311918309662669,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "name": "Davide Angelocola",
            "username": "dfa1",
            "email": "davide.angelocola@gmail.com"
          },
          "committer": {
            "name": "Davide Angelocola",
            "username": "dfa1",
            "email": "davide.angelocola@gmail.com"
          },
          "id": "711e1aebbc1cb476574ef4c39345197a8d8a1682",
          "message": "perf(fsst): cascade-compress FSST child arrays\n\nFSST emits two int[] sidecars per chunk: uncompressed_lengths and\ncodes_offsets. They were written as leaf primitive nodes — for a\n50k-row chunk, that's 400 KB of raw integers per column even when\nthe lengths are constant and the offsets monotonic.\n\nSwitch FsstEncoding to publish those arrays as open ChildSlots in\nits encodeCascade step so the cascading compressor picks Constant\nfor the lengths and FoR -> Bitpacked for the offsets.\n\nSurfaced a pre-existing proto3 elision bug along the way: when\nBitPackedMetadata's fields all hold their defaults (bit_width=0 from\nconstant residuals), proto3 omits them and the encoded payload is\nzero bytes; the writer then skips the empty metadata vector and\ndecode would throw \"missing metadata\". BitpackedEncoding.Decoder\nnow treats null/empty metadata as BitPackedMetadata(0, 0, null).\n\nImpact on FileSizeComparisonIntegrationTest#highCardinalityUtf8:\n50k random 6-char strings drop from 781 KB to 500 KB; Java/JNI ratio\n1.41x (was 2.20x). Bound tightened to 2x in the test.\n\nCo-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>",
          "timestamp": "2026-06-11T11:41:33Z",
          "url": "https://github.com/dfa1/vortex-java/commit/711e1aebbc1cb476574ef4c39345197a8d8a1682"
        },
        "date": 1781215123716,
        "tool": "jmh",
        "benches": [
          {
            "name": "io.github.dfa1.vortex.performance.RustVsJavaReadBenchmark.javaReadCascading",
            "value": 36.19592752769805,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "io.github.dfa1.vortex.performance.RustVsJavaReadBenchmark.javaReadClose",
            "value": 29.01920327565506,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "io.github.dfa1.vortex.performance.RustVsJavaReadBenchmark.javaReadSymbol",
            "value": 39.816864655259586,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "io.github.dfa1.vortex.performance.RustVsJavaReadBenchmark.javaReadVolume",
            "value": 46.58701460922288,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "io.github.dfa1.vortex.performance.RustVsJavaReadBenchmark.jniReadClose",
            "value": 25.931666211875307,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "io.github.dfa1.vortex.performance.RustVsJavaReadBenchmark.jniReadSymbol",
            "value": 4.629287370806197,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "io.github.dfa1.vortex.performance.RustVsJavaReadBenchmark.jniReadVolume",
            "value": 27.35812285459915,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "io.github.dfa1.vortex.performance.RustVsJavaWriteBenchmark.javaWrite",
            "value": 0.6368457268607083,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "io.github.dfa1.vortex.performance.RustVsJavaWriteBenchmark.javaWriteCascading",
            "value": 0.2182624363665954,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "io.github.dfa1.vortex.performance.RustVsJavaWriteBenchmark.jniWrite",
            "value": 0.30496755632095424,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "name": "Davide Angelocola",
            "username": "dfa1",
            "email": "davide.angelocola@gmail.com"
          },
          "committer": {
            "name": "Davide Angelocola",
            "username": "dfa1",
            "email": "davide.angelocola@gmail.com"
          },
          "id": "ea0f3f5b0b5ae377858a1a83e7e9d3e186af098d",
          "message": "docs(claude): add module overview, fix stale refs\n\nAdd Module structure section with dep rule. Fix VortexFile →\nVortexReader. Correct ExtensionDecoder registration: no service\nfile, must register explicitly via ReadRegistry.builder().\n\nCo-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>",
          "timestamp": "2026-06-12T19:45:32Z",
          "url": "https://github.com/dfa1/vortex-java/commit/ea0f3f5b0b5ae377858a1a83e7e9d3e186af098d"
        },
        "date": 1781299701236,
        "tool": "jmh",
        "benches": [
          {
            "name": "io.github.dfa1.vortex.performance.RustVsJavaReadBenchmark.javaReadCascading",
            "value": 36.7361221090079,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "io.github.dfa1.vortex.performance.RustVsJavaReadBenchmark.javaReadClose",
            "value": 27.958695422290436,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "io.github.dfa1.vortex.performance.RustVsJavaReadBenchmark.javaReadSymbol",
            "value": 37.94329599258365,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "io.github.dfa1.vortex.performance.RustVsJavaReadBenchmark.javaReadVolume",
            "value": 47.78527245272887,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "io.github.dfa1.vortex.performance.RustVsJavaReadBenchmark.jniReadClose",
            "value": 23.807398684523637,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "io.github.dfa1.vortex.performance.RustVsJavaReadBenchmark.jniReadSymbol",
            "value": 4.569361601582023,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "io.github.dfa1.vortex.performance.RustVsJavaReadBenchmark.jniReadVolume",
            "value": 25.40521108802461,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "io.github.dfa1.vortex.performance.RustVsJavaWriteBenchmark.javaWrite",
            "value": 0.6354021559419278,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "io.github.dfa1.vortex.performance.RustVsJavaWriteBenchmark.javaWriteCascading",
            "value": 0.21199241705142577,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          },
          {
            "name": "io.github.dfa1.vortex.performance.RustVsJavaWriteBenchmark.jniWrite",
            "value": 0.319404847995715,
            "unit": "ops/s",
            "extra": "iterations: 3\nforks: 1\nthreads: 1"
          }
        ]
      }
    ]
  }
}