# vortex-java

[![CI](https://github.com/dfa1/vortex-java/actions/workflows/ci.yml/badge.svg)](https://github.com/dfa1/vortex-java/actions)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.dfa1.vortex/vortex-reader.svg)](https://central.sonatype.com/artifact/io.github.dfa1.vortex/vortex-reader)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/license/Apache-2.0)

Pure-Java reader/writer for the [Vortex](https://github.com/vortex-data/vortex) columnar file format.
100% Java, no JNI, no `sun.misc.Unsafe`. Uses the FFM API (`MemorySegment`/`Arena`, Java 25+)
for zero-copy memory-mapped reads.
This has good performance out of the box, without using native dependencies.
JMH read throughput vs the Rust JNI reference implementation.
10 M rows of OHLC trade data, single-column projection, Apple M5, Zulu JDK 25.0.2.

| Column | Type | Java (ops/s) | JNI/Rust (ops/s) | Speedup |
|--------|------|-------------|-----------------|---------|
| volume | I64 / bitpacked | 118.0 | 51.3 | **2.3×** |
| close  | F64 / ALP       | 69.2  | 50.6 | **1.4×** |
| symbol | Utf8 / varbin   | 106.5 | 9.8  | **10.9×** |

ops/s = complete file scans per second; higher is better.
Measured 2026-06-13, commit `fea3aa29`. See [docs/explanation.md](docs/explanation.md#benchmarks) for full tables and methodology.

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
  <version>0.6.0</version>
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

For more examples — writing, projection, filtering, custom encodings, and the CLI —
see the documentation below.

## Documentation

Docs follow the [Diátaxis](https://diataxis.fr/) framework.

| Document                                       | Mode        | Contents                                                                |
|------------------------------------------------|-------------|-------------------------------------------------------------------------|
| [docs/tutorial.md](docs/tutorial.md)           | Tutorial    | Step-by-step: write and read your first Vortex file                     |
| [docs/how-to.md](docs/how-to.md)               | How-to      | Recipes: count rows, convert Parquet, filter, project, custom encodings |
| [docs/reference.md](docs/reference.md)         | Reference   | API surface, CLI subcommands, operator tables, file-format trailer      |
| [docs/compatibility.md](docs/compatibility.md) | Reference   | Encoding support table, S3 fixture status                               |
| [docs/explanation.md](docs/explanation.md)     | Explanation | Design rationale, memory model, testing strategy, benchmarks            |

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
