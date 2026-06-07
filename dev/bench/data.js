window.BENCHMARK_DATA = {
  "lastUpdate": 1780866233933,
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
      }
    ]
  }
}