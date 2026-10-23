# vortex-java

[![CI](https://github.com/dfa1/vortex-java/actions/workflows/ci.yml/badge.svg)](https://github.com/dfa1/vortex-java/actions)
[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=dfa1_vortex-java&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=dfa1_vortex-java)
[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=dfa1_vortex-java&metric=coverage)](https://sonarcloud.io/summary/new_code?id=dfa1_vortex-java)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.dfa1.vortex/vortex-reader.svg)](https://central.sonatype.com/artifact/io.github.dfa1.vortex/vortex-reader)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/license/Apache-2.0)

Pure-Java reader/writer for the [Vortex](https://github.com/vortex-data/vortex) columnar file format.
100% Java, no JNI, no `sun.misc.Unsafe`. Uses the FFM API (`MemorySegment`/`Arena`, Java 25+)
for zero-copy memory-mapped reads — good performance out of the box, without native dependencies.

Checked against the Rust reference in both directions (Rust writes, Java reads and vice versa),
and against 172 real-world [Raincloud](docs/compatibility.md#real-world-conformance-the-raincloud-corpus) datasets, each matching its Parquet copy value for value.

## Who is this for

- JVM analytics engines and OLAP systems
- Anyone who wants mmap-backed, zero-copy columnar reads without native-library management
- Anyone who wants to query `.vortex` files with SQL from IntelliJ, DBeaver or another JDBC tool
- **Small footprint.** The Rust reference's JNI bindings (`vortex-jni`) are a 75 MB jar
  (native binaries for four platforms) that pulls in Arrow, Netty, Guava and RoaringBitmap.
  vortex-java's reader is ~800 KB of plain Java (`vortex-reader` + `vortex-core` +
  `vortex-fsst`) with no other required dependencies, and runs on any platform with a JDK.
  CI builds the full reactor on Linux + macOS + Windows × JDK 25 + 27.

## Quickstart

```xml
<dependency>
  <groupId>io.github.dfa1.vortex</groupId>
  <artifactId>vortex-reader</artifactId>
  <version>0.18.1</version>
</dependency>
```

### Minimal read example

```java
try (VortexReader vf = VortexReader.open(Path.of("data/example.vortex"));
     var iter = vf.scan(ScanOptions.all())) {
    while (iter.hasNext()) {
        try (Chunk chunk = iter.next()) {
            LongArray ts = chunk.column(ColumnName.of("timestamp"));
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
  <version>0.18.1</version>
</dependency>
```

```java
DType.Struct schema = DType.structBuilder()
        .field(ColumnName.of("timestamp"), DType.I64)
        .field(ColumnName.of("symbol"),    DType.UTF8)
        .field(ColumnName.of("price"),     DType.F64)
        .field(ColumnName.of("volume"),    DType.I64.asNullable())     // boxed Long[] → nullable
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

### Query with SQL from IntelliJ or DBeaver

`vortex-jdbc-driver` is a read-only JDBC driver in one self-contained jar, so SQL tools can browse and
query `.vortex` files with no code. Download `vortex-jdbc-driver-0.18.1-all.jar` from
[Maven Central](https://central.sonatype.com/artifact/io.github.dfa1.vortex/vortex-jdbc-driver), add
it as a driver with class `io.github.dfa1.vortex.jdbc.driver.VortexDriver`, and connect to a directory:

```
jdbc:vortex:/data/vortex
```

Every `*.vortex` file in it is a table of schema `vtx`, named after the file:

```sql
select symbol, count(*), max(high) from vtx.ohlc group by symbol order by 2 desc;
```

The tool must run on JDK 25 or later. Setup steps for IntelliJ and DBeaver, URL options and
troubleshooting are in the
[how-to](docs/how-to.md#query-vortex-files-from-an-sql-tool-intellij-dbeaver).

For more examples — projection, filtering, custom encodings, and the CLI — see
the [tutorial](docs/tutorial.md).

## For AI agents

If you are an AI agent asked to convert data to Vortex with this library, these are the facts you
would otherwise guess. Working *on* this repository instead? Read [AGENTS.md](AGENTS.md).

- **Java 25+.** The library uses FFM, not JNI: run with `--enable-native-access=ALL-UNNAMED`
  (the CLI jar's manifest already sets it).
- **No code needed for a one-off conversion.** Download `vortex-cli-0.18.1-all.jar` from
  [Maven Central](https://central.sonatype.com/artifact/io.github.dfa1.vortex/vortex-cli) and run
  `java -jar vortex-cli-0.18.1-all.jar import data.csv data.vortex`. Add `--delimiter ';'` for
  other separators and `--compact` for smaller files. Check the result with `schema`, `count`,
  `stats` or `select <file> <col> --where <expr>`, which print to stdout. Exit codes: `0` ok,
  `1` usage, `2` file not found, `3` error.
- **In code**, depend on `vortex-csv` (versions from `vortex-bom`) and call
  `CsvImporter.importCsv(csv, vortex, ImportOptions.defaults())`. It streams any file size in
  bounded memory; `ParquetImporter` and `JdbcImporter` do the same for Parquet and JDBC sources.
- **Column types are inferred from the first 65 536 rows** (long, then double, then boolean, else
  text). A later value that does not fit fails the import, so for a column that mixes types pass
  an explicit schema: `ImportOptions.defaults().withSchema(DType.structBuilder()...build())`.
  Empty cells become `0`, `false` or `""`; the first row is a header unless
  `withHeader(false)`; the delimiter must be an ASCII character.
- **Compact files from code**: start from the import's own write options, which keep the global
  dictionary off for streaming (`options.withWriteOptions(options.writeOptions().withCompact(true))`),
  and add the optional `io.github.dfa1.zstd:zstd` and `zstd-platform` dependencies.
- Full API and CLI reference: [docs/reference.md](docs/reference.md); recipes:
  [docs/how-to.md](docs/how-to.md).

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


## Contributing

**Requirements:** Java 25+. Build: `./mvnw verify`.

Forks welcome. See [CONTRIBUTING.md](CONTRIBUTING.md) for full build reference,
coding conventions, and how to add a new encoding.

This project uses [Claude Code](https://claude.ai/code) for implementation work.
Architecture, API design, and all decisions are human-driven.

## References

- [vortex.dev](https://vortex.dev) — the Vortex project's website
- [Vortex: A Columnar File Format for GPU Streaming](https://www.infoq.com/presentations/vortex-columnar-file-format-gpu-streaming/) — InfoQ presentation
- [Vortex: One Format for Any Shape](https://spiraldb.com/blog/vortex-one-format-for-any-shape) — SpiralDB blog post
- [BtrBlocks: The Compressor Behind Vortex](https://vortex.dev/blog/btrblocks-compressor) — the cascading, cost-based encoding selection this project's own cascading compressor is modeled on
- [A Tour of the Vortex Columnar Format with vortex-java](https://dfa1.github.io/articles/vortex-on-a-real-dataset.html) — walkthrough of the format on a real dataset

### Other Vortex implementations

| Project                                                             | Language | Notes                                   |
|---------------------------------------------------------------------|----------|-----------------------------------------|
| [vortex-data/vortex](https://github.com/vortex-data/vortex)         | Rust     | Reference implementation + JNI bindings |
| [LaurieRhodes/vortex-go](https://github.com/LaurieRhodes/vortex-go) | Go       | Pure-language port                      |
| [Evariops/Vorticity](https://github.com/Evariops/Vorticity)         | C#       | Pure .NET, dependency-free              |

## Acknowledgments

<a href="https://www.yourkit.com/java/profiler/"><img src="https://www.yourkit.com/images/yklogo.png" alt="YourKit" width="120"></a>

YourKit supports open source projects with innovative and intelligent tools
for monitoring and profiling Java and .NET applications.
YourKit is the creator of [YourKit Java Profiler](https://www.yourkit.com/java/profiler/),
[YourKit .NET Profiler](https://www.yourkit.com/dotnet-profiler/),
and [YourKit YouMonitor](https://www.yourkit.com/youmonitor/).
