# TODO

## Encodings

- [ ] the classes are very long of complex, most likely we should group the impl detail in private static inner classes Encoder/Decoder

## Performance

- [ ] **#10b Java vs JNI write benchmark** (`performance/` module, `-Pperformance`)
    - Add `RustVsJavaWriteBenchmark` mirroring read side: same 10M-row OHLC fixture, JMH throughput, both writers.
    - Old `WriteBenchmark.java` (Java-only) removed; rewrite from scratch using JNI bindings already on classpath
      (`dev.vortex:vortex-jni:0.72.0`).

- [ ] **#10c Publish reproducible perf artifacts**
    - Capture JMH JSON + JFR profile alongside README table; cite hardware (CPU model), JDK build (`java -version`),
      and benchmark commit SHA so numbers don't rot silently.

- [ ] **#13 Cascading compressor — remaining follow-ups**
    - [ ] **DeltaEncoding wire-format fix**: refactor to emit `(bases child, deltas child)` matching
      Rust's 2-child wire format (currently 1-buffer flat — incompatible with Rust reader, see commit 09685a2).
      Unblocks cross-compat for delta cascades.
    - [ ] **Cross-compat test**: `JavaWritesRustReadsIntegrationTest` with `allowedCascading=3`
      confirms Rust JNI reader decodes the same OHLC data (gates DeltaEncoding wire-format fix).
    - [ ] **Benchmark**: extend `RustVsJavaWriteBenchmark` with cascading-off vs cascading-on variants;
      capture compressed-bytes ratio (Java cascade / JNI), write throughput, decode throughput.

## Tooling

- [ ] **#14 CSV import/export + CLI tool** (new modules `csv` + `cli`)

    ### `csv` module

    - `CsvExporter` — reads a `VortexFile` via `ScanIterator` and writes rows to a `Writer` / `OutputStream`.
      Emit header row from `DType.Struct` field names; one row per element; quote strings with commas.
    - `CsvImporter` — parses a CSV file, infers column types (long, double, boolean, string fallback),
      writes a `.vortex` file via `VortexWriter`. Schema override via `ImportOptions.withSchema(DType)`.

    ### `cli` module

    Uber-jar built with Maven Shade Plugin (`cli/pom.xml`, classifier `executable`).
    Main class `io.github.dfa1.vortex.cli.VortexCli` dispatches subcommands:

    | Subcommand | Description |
    |------------|-------------|
    | `inspect <file>` | Print version, file size, dtype, layout tree, row count, segment count |
    | `export <file>` | Write CSV to stdout (uses `CsvExporter`) |
    | `import <csv> <out.vortex>` | Write vortex from CSV (uses `CsvImporter`) |
    | `schema <file>` | Print dtype only (machine-readable) |

    **Exit codes**: 0 = success, 1 = usage error, 2 = file not found, 3 = decode error.

    **Sub-tasks**

    - [ ] **a. `csv` module** — `CsvExporter` + `CsvImporter` + unit tests
    - [ ] **b. `cli` module** — `VortexCli` dispatcher + subcommand classes
    - [ ] **c. Shade plugin** — fat jar with `Main-Class` manifest entry; exclude `module-info.class` conflicts
    - [ ] **d. Tests** — `CliIT` acceptance test: write CSV → import → export → diff original CSV

## Large-file support

- [ ] **#12 Test read/write of files > 2 GB**
    - [ ] Parquet baseline for comparison: same data should fail or require splitting when any
      column chunk exceeds 2 GB.

## Array API

- [ ] Optional `vortex-arrow` bridge module for Arrow ecosystem interop
    - Primary API stays `ArrayLong`/`ArrayDouble` (zero-copy, no deps, no Unsafe)
    - Bridge wraps typed views into Arrow `BigIntVector`, `Float8Vector`, etc. for users who need
      Arrow Flight / DuckDB ADBC / pandas interop
    - Conversion involves a copy (MemorySegment → Arrow off-heap buffer) — cost is explicit and opt-in
    - Arrow JVM uses `sun.misc.Unsafe` / Netty internally; keeping it in a separate module means
      the core library stays Unsafe-free

## Project

- [ ] Move project to a dedicated organization
- [ ] Create website
- [ ] Publish benchmarks
- [ ] Build something like hardwood.dev but for vortex files

## JDK

- [ ] use Java Modules
- [ ] wait for Vector API lands on stable to improve read performance
