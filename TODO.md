# TODO

## Project

- [ ] Move project to a dedicated organization
- [ ] Create website
- [ ] Publish benchmarks
- [ ] Build something like hardwood.dev but for vortex files
- [ ] Publish to Maven Central (OSSRH/SONATYPE setup, GPG signing, coordinates, CI release pipeline)

## Performance

- [ ] **#10c Publish reproducible perf artifacts**
    - Capture JMH JSON + JFR profile alongside README table; cite hardware (CPU model), JDK build (`java -version`),
      and benchmark commit SHA so numbers don't rot silently.

- [ ] **#13 Cascading compressor — remaining follow-ups**
    - [ ] **Cross-compat test**: Add `JavaWritesRustReadsIntegrationTest` in the `integration` module.
      Write OHLC data with `WriteOptions.cascading(3)` via Java writer; read it back with the Rust JNI
      reader and assert values round-trip correctly. Same OHLC schema / dataset as `RustVsJavaWriteBenchmark`.
    - [ ] **Benchmark — cascading write variants**: In `RustVsJavaWriteBenchmark`, add:
        - `javaWriteCascading()` — same body as `javaWrite()` but use `WriteOptions.cascading(3)` instead of `WriteOptions.defaults()`
        - After both `javaWrite` and `javaWriteCascading` run, log the compressed-bytes ratio
          (`javaWriteCascading file size / javaWrite file size`) via a JMH `AuxCounters` or a
          `@TearDown` print so it appears in the benchmark output.
    - [ ] **Benchmark — cascading read variants**: In `RustVsJavaReadBenchmark`, add a
      `javaReadCascading()` method that reads a file written with `WriteOptions.cascading(3)`.
      Warm up the file in `@Setup`; measure decode throughput (rows/s). Compare against `javaRead()`
      (no cascading) and `jniRead()` to show the cascading cost/benefit at read time.

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

## Encodings

- [ ] the classes are very long of complex, most likely we should group the impl detail in private static inner classes Encoder/Decoder

- [ ] fix this comment in Array
     /// Not declared `sealed` because the project does not declare a JPMS module —
     /// JLS only allows cross-package `permits` inside the same named module. The
     /// effective hierarchy is still closed: only types in this package should implement
     /// this interface.
     public interface Array {
