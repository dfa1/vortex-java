# vortex-java

[![CI](https://github.com/dfa1/vortex-java/actions/workflows/ci.yml/badge.svg)](https://github.com/dfa1/vortex-java/actions)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/license/Apache-2.0)

> **Alpha** — not production-ready. APIs will change without notice.

Pure-Java reader/writer for the [Vortex](https://github.com/spiraldb/vortex) columnar file format.
100% Java, no JNI, no `sun.misc.Unsafe`. Uses the FFM API (`MemorySegment`/`Arena`, Java 25+)
for zero-copy memory-mapped reads.

| Project | Language | Notes |
|---|---|---|
| [spiraldb/vortex](https://github.com/spiraldb/vortex) | Rust | Reference implementation + JNI bindings |
| [LaurieRhodes/vortex-go](https://github.com/LaurieRhodes/vortex-go) | Go | Pure-language port |
| **dfa1/vortex-java** | **Java** | **This library** |

## Who is this for

- JVM analytics engines and OLAP systems
- Anyone who wants mmap-backed, zero-copy columnar reads without native-library management

## Quickstart

```xml
<!-- TODO: replace with released coordinates once published to Maven Central -->
<dependency>
  <groupId>io.github.dfa1</groupId>
  <artifactId>vortex-java</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

### Read a Vortex file

```java
try (VortexReader vf = VortexReader.open(Path.of("data/example.vortex"));
     var iter = vf.scan(ScanOptions.all())) {
    while (iter.hasNext()) {
        var chunk = iter.next();
        LongArray ts = chunk.column("timestamp");
        for (long i = 0; i < ts.length(); i++) {
            System.out.println(ts.getLong(i));
        }
    }
}
```

> **Note:** `iter.hasNext()` closes the previous chunk's arena. Access all column data
> before calling `hasNext()` again.

### Write a Vortex file

```java
var schema = new DType.Struct(
    List.of("timestamp", "value"),
    List.of(new DType.Primitive(PType.I64, false),
            new DType.Primitive(PType.F64, false)),
    false);

try (var ch = FileChannel.open(Path.of("out.vortex"), CREATE, WRITE);
     var writer = VortexWriter.create(ch, schema, WriteOptions.defaults())) {
    writer.writeChunk(Map.of(
        "timestamp", new long[]  {1_700_000_000L, 1_700_000_001L},
        "value",     new double[] {1.23, 4.56}
    ));
}
```

### Scan with options

```java
// project columns + limit rows
ScanOptions opts = ScanOptions.all()
    .withColumns("timestamp", "value")
    .withLimit(1_000);

// add a row filter
ScanOptions filtered = ScanOptions.all()
    .withFilter(new RowFilter.Gte("price", 100))
    .withLimit(50);
```

### Handle unknown encodings

Files containing unrecognised encoding IDs throw `VortexException` by default.
Opt in to passthrough mode to read such files without failing:

```java
EncodingRegistry registry = EncodingRegistry.loadAll().allowUnknown();
try (VortexReader vf = VortexReader.open(path, registry)) {
    // columns with unknown encodings are returned as UnknownArray
}
```

## CLI

The `cli` module ships a fat jar with subcommands for inspecting and querying Vortex files.

```bash
./mvnw package -pl cli -am -DskipTests
java -jar cli/target/vortex.jar <subcommand> [args]
```

| Subcommand | Syntax | Description |
|---|---|---|
| `inspect` | `inspect <file.vortex>` | Print file structure (layout tree, encodings, row counts) |
| `schema`  | `schema <file.vortex>`  | Print column types |
| `count`   | `count <file.vortex>`   | Print total row count |
| `stats`   | `stats <file.vortex>`   | Print per-column min/max statistics |
| `export`  | `export <file.vortex>`  | Write all columns to CSV on stdout |
| `select`  | `select <file.vortex> <col> [col2 ...]` | Project specific columns to CSV |
| `filter`  | `filter <file.vortex> <expr>` | Filter rows to CSV (e.g. `"price >= 100"`) |
| `import`  | `import <file.csv\|file.parquet> [out.vortex]` | Convert CSV or Parquet to Vortex |

## Documentation

Docs follow the [Diátaxis](https://diataxis.fr/) framework (tutorial, how-to, reference, explanation).

| Document | Contents |
|---|---|
| [docs/tutorial.md](docs/tutorial.md) | Step-by-step: write and read your first Vortex file |
| [docs/how-to.md](docs/how-to.md) | Recipes: count rows, convert Parquet, filter, project, custom encodings |
| [docs/compatibility.md](docs/compatibility.md) | Encoding support table, S3 fixture status |
| [docs/explanation.md](docs/explanation.md) | Design rationale, memory model, testing strategy, benchmarks |

## Development

**Requirements:** Java 25+

Generated sources (`fbs`/`proto` → Java) are committed. Normal builds need no external tools.

```bash
./mvnw verify          # build + tests
./mvnw verify -DskipTests

# run integration tests
./mvnw verify -pl integration -am

# benchmarks (always pass ClassName.methodName filter)
./bench RustVsJavaReadBenchmark.javaReadVolume
```

To regenerate schemas after editing `.fbs`/`.proto`:

```bash
brew install flatbuffers protobuf
./mvnw generate-sources -pl core -P regenerate-sources
```

## Contributing

Forks and contributions welcome. Include tests and update documentation where applicable
(see CLAUDE.md for guidelines).

This project uses [Claude Code](https://claude.ai/code) for implementation work.
Architecture, API design, and all decisions are human-driven.
