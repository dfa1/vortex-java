# vortex-java

[![CI](https://github.com/dfa1/vortex-java/actions/workflows/ci.yml/badge.svg)](https://github.com/dfa1/vortex-java/actions)
[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=dfa1_vortex-java&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=dfa1_vortex-java)
[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=dfa1_vortex-java&metric=coverage)](https://sonarcloud.io/summary/new_code?id=dfa1_vortex-java)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.dfa1.vortex/vortex-reader.svg)](https://central.sonatype.com/artifact/io.github.dfa1.vortex/vortex-reader)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/license/Apache-2.0)

Pure-Java reader/writer for the [Vortex](https://github.com/vortex-data/vortex) columnar file format.
100% Java, no JNI, no `sun.misc.Unsafe`. Uses the FFM API (`MemorySegment`/`Arena`, Java 25+)
for zero-copy memory-mapped reads.
This has good performance out of the box, without using native dependencies.
JMH read throughput: **vortex-java** (this project) vs **vortex-jni** (the Rust
reference implementation's JNI bindings).
80 M rows of OHLC trade data, single-column projection, Apple M5, Zulu JDK 25.0.2.

| Column | Type | vortex-java (ops/s) | vortex-jni (ops/s) | Speedup |
|--------|------|--------------------|-------------------|---------|
| volume | I64 / bitpacked | 13.7 | 6.4 | **2.1×** |
| close  | F64 / ALP       | 8.3  | 6.1 | **1.4×** |
| symbol | Utf8 / varbin   | 13.6 | 1.2 | **11.8×** |

ops/s = complete file scans per second; higher is better.

> **Naming:** `vortex-java` is this project; `vortex-jni` is the Vortex Rust reference's JNI
> bindings (its numbers include the JNI-boundary cost — it is not pure Rust). We use these two
> labels everywhere instead of "Rust vs Java".

**Top-N reads** on the same 80 M-row file, "volume" column, exit after N rows
(measures open + footer/layout decode + first-chunk overhead):

| N rows | vortex-java (ops/s) | vortex-jni (ops/s) | Speedup |
|--------|--------------------|-------------------|---------|
| 10     | 2,588       | 882             | **2.9×** |
| 100    | 2,538       | 887             | **2.9×** |

Measured 2026-06-25, commit `b78989fa`. See [docs/explanation.md](docs/explanation.md#benchmarks) for full tables and methodology.

**Compression** — NYC Yellow Taxi 2024-01, 2,964,624 rows × 19 columns, imported from the
same Parquet file (47.6 MB), cascading depth 3, Apple M5:

| Implementation | Output size | vs Parquet |
|----------------|-------------|------------|
| vortex-jni     | 47.0 MB     | −1.3%      |
| **vortex-java** | **40.7 MB** | **−14.5%** |

vortex-java produces a 13% smaller file than the Rust reference from identical input.
The gap comes from the global dictionary encoder that catches low-cardinality `F64`
columns (`mta_tax`, `Airport_fee`, `congestion_surcharge`) that Rust's compressor
leaves as plain ALP. Data integrity is verified by
`TaxiParquetOracleVsJavaIntegrationTest`: hardwood reads the Parquet file directly
to a CSV (oracle); `ParquetImporter` → `CsvExporter` produces a second CSV (SUT);
line-by-line diff is zero.

## Who is this for

- JVM analytics engines and OLAP systems
- Anyone who wants mmap-backed, zero-copy columnar reads without native-library management
- **Windows JVM users.** The Rust reference's JNI bindings (`vortex-jni`) ship Linux + macOS
  binaries only — vortex-java is the only Vortex implementation that runs on Windows JVMs out
  of the box. CI builds the full reactor on Linux + macOS + Windows × JDK 25 + 26.

## Quickstart

```xml
<dependency>
  <groupId>io.github.dfa1.vortex</groupId>
  <artifactId>vortex-reader</artifactId>
  <version>0.12.1</version>
</dependency>
```

### Minimal read example

```java
try (VortexReader vf = VortexReader.open(Path.of("data/example.vortex"));
     var iter = vf.scan(ScanOptions.all())) {
    while (iter.hasNext()) {
        try (Chunk chunk = iter.next()) {
            LongArray ts = chunk.column("timestamp");
            for (long i = 0; i < ts.length(); i++) {
                System.out.println(ts.getLong(i));
            }
        }
    }
}
```

> **Lifecycle.** `ScanIterator` implements `Iterator<Chunk>` and `Chunk` implements
> `AutoCloseable`. Each chunk owns a confined `Arena`; closing it releases the
> decoded buffers. Calling `iter.next()` while a prior chunk is still open throws
> `IllegalStateException`. Use try-with-resources, or
> `iter.forEachRemaining(c -> ...)` which closes each chunk for you. See
> [docs/explanation.md#memory-model](docs/explanation.md#memory-model).

### Minimal write example

```xml
<dependency>
  <groupId>io.github.dfa1.vortex</groupId>
  <artifactId>vortex-writer</artifactId>
  <version>0.12.1</version>
</dependency>
```

```java
DType.Struct schema = DType.structBuilder()
        .field("timestamp", DType.I64)
        .field("symbol",    DType.UTF8)
        .field("price",     DType.F64)
        .field("volume",    DType.I64.asNullable())     // boxed Long[] → nullable
        .build();

try (var ch = FileChannel.open(Path.of("data/example.vortex"),
                               StandardOpenOption.CREATE, StandardOpenOption.WRITE);
     var writer = VortexWriter.create(ch, schema, WriteOptions.cascading(3))) {
    writer.writeChunk(c -> c
            .put(ColumnName.of("timestamp"), new long[]   {1_700_000_000_000L, 1_700_000_001_000L})
            .put(ColumnName.of("symbol"),    new String[] {"AAPL", "AAPL"})
            .put(ColumnName.of("price"),     new double[] {189.95, 190.10})
            .put(ColumnName.of("volume"),    new Long[]   {100L, null}));  // null in nullable col
}
```

> Each `.put` is validated at the call site: unknown column names, mismatched
> array types, and boxed arrays for non-nullable columns throw
> `IllegalArgumentException` immediately. Missing columns surface as
> `IllegalStateException` when the lambda returns.

For more examples — projection, filtering, custom encodings, and the CLI — see
the [tutorial](docs/tutorial.md).

## Documentation

Docs follow the [Diátaxis](https://diataxis.fr/) framework.

| Document                                       | Mode        | Contents                                                                |
|------------------------------------------------|-------------|-------------------------------------------------------------------------|
| [docs/tutorial.md](docs/tutorial.md)           | Tutorial    | Step-by-step: write and read your first Vortex file                     |
| [docs/how-to.md](docs/how-to.md)               | How-to      | Recipes: count rows, convert Parquet, filter, project, custom encodings |
| [docs/reference.md](docs/reference.md)         | Reference   | API surface, CLI subcommands, operator tables, file-format trailer      |
| [docs/compatibility.md](docs/compatibility.md) | Reference   | Encoding support table, S3 fixture status                               |
| [docs/explanation.md](docs/explanation.md)     | Explanation | Design rationale, memory model, benchmarks                              |
| [docs/testing.md](docs/testing.md)             | Explanation | Test strategy: layers, counts per module, what each layer verifies      |

## Vortex implementations

| Project                                                             | Language | Notes                                   |
|---------------------------------------------------------------------|----------|-----------------------------------------|
| [vortex-data/vortex](https://github.com/vortex-data/vortex)         | Rust     | Reference implementation + JNI bindings |
| [LaurieRhodes/vortex-go](https://github.com/LaurieRhodes/vortex-go) | Go       | Pure-language port                      |
| **dfa1/vortex-java**                                                | **Java** | **This library**                        |

## Contributing

**Requirements:** Java 25+. Build: `./mvnw verify`.

Forks welcome. See [CONTRIBUTING.md](CONTRIBUTING.md) for full build reference,
coding conventions, and how to add a new encoding.

This project uses [Claude Code](https://claude.ai/code) for implementation work.
Architecture, API design, and all decisions are human-driven.
