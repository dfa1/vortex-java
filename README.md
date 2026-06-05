# vortex-java

[![CI](https://github.com/dfa1/vortex-java/actions/workflows/ci.yml/badge.svg)](https://github.com/dfa1/vortex-java/actions)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.dfa1.vortex/reader.svg)](https://central.sonatype.com/artifact/io.github.dfa1.vortex/reader)
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
<dependency>
  <groupId>io.github.dfa1.vortex</groupId>
  <artifactId>reader</artifactId>
  <version>0.2.0</version>
</dependency>
```

### Minimal read example

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
> before calling `hasNext()` again. See [docs/explanation.md#memory-model](docs/explanation.md#memory-model).

For more examples — writing, projection, filtering, custom encodings, and the CLI —
see the documentation below.

## Documentation

Docs follow the [Diátaxis](https://diataxis.fr/) framework.

| Document | Mode | Contents |
|---|---|---|
| [docs/tutorial.md](docs/tutorial.md) | Tutorial | Step-by-step: write and read your first Vortex file |
| [docs/how-to.md](docs/how-to.md) | How-to | Recipes: count rows, convert Parquet, filter, project, custom encodings |
| [docs/reference.md](docs/reference.md) | Reference | API surface, CLI subcommands, operator tables, file-format trailer |
| [docs/compatibility.md](docs/compatibility.md) | Reference | Encoding support table, S3 fixture status |
| [docs/explanation.md](docs/explanation.md) | Explanation | Design rationale, memory model, testing strategy, benchmarks |

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
