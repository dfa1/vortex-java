window.BENCHMARK_DATA = {
  "lastUpdate": 1780551397446,
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
      }
    ]
  }
}