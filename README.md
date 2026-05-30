# vortex-java

[![CI](https://github.com/dfa1/vortex-java/actions/workflows/ci.yml/badge.svg)](https://github.com/dfa1/vortex-java/actions)

> **Alpha** — not production-ready. APIs will change without notice.

Pure-Java reader/writer for the [Vortex](https://github.com/spiraldb/vortex) columnar file format.

Vortex is a shared open format with multiple independent implementations (Rust, Go, Java).
Files written by any implementation are readable by all others — no vendor lock-in, no
format translation at the boundary.

## Status

- Pure-Java reader for primitive, sequence, ALP, dict, FSST (stable)
- Local (mmap) or Remote (HTTPS, single read of last 65K) (stable)
- Writer: in progress
- Benchmark vs Rust+JNI: Java beats JNI 1.5×–11.5× across read/write workloads (see Benchmarks)
- **File size trade-off:** Java-written files are larger than Rust-written files (up to ~2×
  with `cascading(3)`). The Rust writer applies more compression passes; the Java writer
  covers ALP, bitpacking, and FSST but not the full Rust encoding set yet. Files are still
  significantly smaller than CSV. Cross-implementation verified by `FileSizeComparisonIntegrationTest`.
- Full encoding coverage: in progress
- Vectorized decode paths (Panama Vector API): planned
- Iceberg/Spark/Flink integration: not available yet

## Motivation

The official Vortex ecosystem provides JVM bindings via JNI (bundled native `.so`/`.dylib`).
JNI bindings are fast but add deployment friction: platform-specific artifacts, native build
toolchains, and crash-domain coupling between the JVM and native code.

This library takes a different approach — 100% Java, no JNI, no `sun.misc.Unsafe`.
It uses the Java FFM API (`MemorySegment` / `Arena`, Java 25+) for zero-copy memory-mapped reads, making it easier to:

- embed in any JVM project without native-library management
- build and test on any platform with a standard JDK
- debug and profile with standard JVM tooling

## Who is this for?

- JVM analytics engines
- JVM-based OLAP systems
- Anyone who wants mmap‑backed, zero‑copy columnar reads without first decompressing
  the whole file (or row chunk)

### Why fewer layers = faster

```
  vortex-jni                              vortex-java
  ──────────────────────────────          ──────────────────────────
  ┌──────────────────────────┐            ┌──────────────────────┐
  │  Java App                │            │  Java App            │
  │  (BigIntVector.get(i))   │            │  (buffer.getAtIndex) │
  └────────────┬─────────────┘            └──────────┬───────────┘
               │ Arrow Java API                      │ FFM API
  ┌────────────▼─────────────┐                       │ (MemorySegment,
  │  Apache Arrow (Java)     │                       │  zero-copy slice)
  │  VectorSchemaRoot,       │                       │
  │  BigIntVector, …         │                       │
  └────────────┬─────────────┘            ┌──────────▼───────────┐
               │ Arrow C Data Interface   │  OS mmap region      │
               │ (ArrowArray/ArrowSchema) │  (file on disk)      │
               │ + JNI boundary crossing  └──────────────────────┘
  ┌────────────▼─────────────┐
  │  Native lib              │
  │  (.so / .dylib)          │
  │  Rust decode             │
  └────────────┬─────────────┘
               │ mmap / read
  ┌────────────▼─────────────┐
  │  OS mmap region          │
  │  (file on disk)          │
  └──────────────────────────┘

  4 layers, 1 JNI crossing,              2 layers, 0 boundary crossings,
  Arrow C Data Interface overhead         no intermediate format
```

The JNI path pays three costs per batch: (1) a JNI boundary crossing to call into native
code, (2) the Arrow C Data Interface handshake to pass decoded buffers back to the JVM as
`ArrowArray`/`ArrowSchema` structs, and (3) materialising the result into Apache Arrow
`VectorSchemaRoot` objects before the application can read a single value. The JIT cannot
inline or optimise across the JNI boundary.

`vortex-java` eliminates all of that. The FFM API (`MemorySegment`) gives Java code a
typed, bounds-checked view directly into the OS mmap region — the same physical memory the
file occupies. Decoding reads bytes directly from that view with no copies, no intermediate
Arrow format, and no boundary crossings. The JIT sees the full decode path as ordinary Java
bytecode.

## Benchmarks

JMH throughput (ops/s = full-file scans per second). Higher is better.

**Environment:** Apple M5, OpenJDK 27-jep401ea3 (Valhalla EA), 3 warmup × 3 s, 5 measurement × 5 s, fork 1.

### OHLC read — 10 M rows, 58.9 MB (Rust-written file, single-column projection)

| Benchmark      | Java (ops/s)     | JNI/Rust (ops/s) | Java speedup |
|----------------|------------------|------------------|--------------|
| close (F64/ALP)| 76.7 ± 0.3       | 50.4 ± 2.8       | **1.5×**     |
| volume (I64)   | 127.9 ± 2.3      | 52.9 ± 0.6       | **2.4×**     |
| symbol (varbin)| 110.4 ± 0.4      | 9.6 ± 0.9        | **11.5×**    |

### OHLC write — 10 M rows

| Benchmark | Java (ops/s) | JNI/Rust (ops/s) | Java speedup |
|-----------|--------------|------------------|--------------|
| write     | 4.4 ± 1.1    | 0.7 ± 0.1        | **6.4×**     |

### Big-file scan — 100 M rows × 4 I64 columns, ~3 GB (Rust-written file, all columns)

| Benchmark | Java (ops/s) | JNI/Rust (ops/s) | Java speedup |
|-----------|--------------|------------------|--------------|
| scan      | 20.4 ± 0.9   | 5.7 ± 0.6        | **3.6×**     |

## Design principles

- Zero-copy everywhere
- No JNI
- No Unsafe -- [FFM vs Unsafe](https://inside.java/2025/06/12/ffm-vs-unsafe/) — Maurizio Cimadamore's deep-dive on why FFM (`MemorySegment`/`Arena`) supersedes `sun.misc.Unsafe`: safety, performance, and the JVM's path forward
- Align with vortex-rust and Vortex-go semantics
- Make the JIT happy (constant layouts, predictable strides, no virtual dispatch in hot loops)
- Prepare for the Vector API / Valhalla
- Rigorous testing: unit tests + property-based testing + cross-language integration tests

### Testing strategy

Unit tests verify internal correctness (encoding round-trips, edge cases), but the format has no
formal specification — the Rust implementation is the ground truth. Unit tests alone miss
cross-language wire-format bugs: Java can round-trip a value internally while writing bytes that
another implementation cannot decode.

The `integration` module addresses this by using the Rust JNI reader as a **test oracle**:
Java writes a file, the Rust reader decodes it, and the values are compared exactly.
[Property-based testing](https://jqwik.net/) (jqwik) generates large, diverse inputs automatically,
covering edge cases no hand-written test would anticipate.

This combination caught two real bugs in ALP floating-point encoding:
- Java selected exponents outside the range Rust's decoder accepts (silent data corruption)
- Java's encode round-trip check used a different floating-point associativity than Rust's decode
  (`encoded * (F10[f] * IF10[e])` vs `(encoded * F10[f]) * IF10[e]`), passing values that Rust
  decoded differently

Both bugs were invisible to pure-Java tests and would have shipped undetected without the
cross-language oracle.

## Implementations

| Project                                                     | Language | Notes                                                               |
|-------------------------------------------------------------|----------|---------------------------------------------------------------------|
| [spiraldb/vortex](https://github.com/spiraldb/vortex)       | Rust     | Reference implementation + JNI bindings                             |
| [spiraldb/vortex-go](https://github.com/spiraldb/vortex-go) | Go       | Pure-language port                                                  |
| [dfa1/vortex-java](https://github.com/dfa1/vortex-java)     | Java     | This project — FFM-based, no JNI, no Unsafe                        |

All three implementations share the same binary format and can read each other's files.


## Serialization formats

The format uses two serialization libraries for different roles:

| Format          | Used for                             | Why                                                                                    |
|-----------------|--------------------------------------|----------------------------------------------------------------------------------------|
| **FlatBuffers** | Footer, Layout, Array structure      | Zero-copy random access — fields read directly from memory-mapped bytes, no allocation |
| **Protobuf**    | Codec metadata, DType, Scalar values | Schema evolution and cross-language compatibility for small blobs                      |

FlatBuffers suit the file-structure layer: the footer is parsed once at open and the layout tree is traversed during
scan — both benefit from direct field access on mapped memory. Protobuf suits codec metadata: tiny blobs parsed once per
chunk, where schema evolution matters more than zero-copy speed.

Replacing protobuf with FlatBuffers is not viable — existing `.vortex` files produced by the Rust reference
implementation embed protobuf bytes in codec metadata blobs, and wire compatibility requires matching the format
exactly.

## Quickstart

Add the library to your build (example, Maven):

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
import io.github.dfa1.vortex.io.VortexReader;
import io.github.dfa1.vortex.scan.ScanOptions;
import io.github.dfa1.vortex.core.array.LongArray;

try (VortexReader vf = VortexReader.open(Path.of("data/example.vortex"));
     var iter = vf.scan(ScanOptions.all())) {
    while (iter.hasNext()) {
        var chunk = iter.next();
        // access a typed column
        LongArray ts = chunk.column("timestamp");
        for (long i = 0; i < ts.length(); i++) {
            System.out.println(ts.getLong(i));
        }
        // or get all columns as a map
        chunk.columns().forEach((name, arr) ->
            System.out.printf("%s: %d rows%n", name, arr.length()));
    }
}
```

> **Note:** `iter.hasNext()` closes the previous chunk's arena. Access all column data
> before calling `hasNext()` again.

### Write a Vortex file

```java
import io.github.dfa1.vortex.writer.VortexWriter;
import io.github.dfa1.vortex.writer.WriteOptions;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;

var schema = new DType.Struct(
    List.of("timestamp", "value"),
    List.of(new DType.Primitive(PType.I64, false),
            new DType.Primitive(PType.F64, false)),
    false);

long[]   timestamps = {1_700_000_000L, 1_700_000_001L, 1_700_000_002L};
double[] values     = {1.23, 4.56, 7.89};

try (var ch = FileChannel.open(Path.of("out.vortex"), CREATE, WRITE);
     var writer = VortexWriter.create(ch, schema, WriteOptions.defaults())) {
    writer.writeChunk(Map.of("timestamp", timestamps, "value", values));
}
```

## CLI

The `cli` module ships a fat jar with subcommands for inspecting, converting, and querying Vortex files without writing any code.

**Build:**

```bash
./mvnw package -pl cli -am -DskipTests
# produces cli/target/vortex.jar
```

**Run:**

```bash
java -jar cli/target/vortex.jar <subcommand> [args]
```

### Subcommands

| Subcommand | Syntax | Description |
|---|---|---|
| `inspect` | `inspect <file.vortex>` | Print file structure (layout tree, encodings, row counts) |
| `schema` | `schema <file.vortex>` | Print column types in machine-readable form |
| `count` | `count <file.vortex>` | Print total row count |
| `stats` | `stats <file.vortex>` | Print per-column min/max statistics |
| `export` | `export <file.vortex>` | Write all columns to CSV on stdout |
| `select` | `select <file.vortex> <col> [col2 ...]` | Project specific columns to CSV on stdout |
| `filter` | `filter <file.vortex> <expr>` | Filter rows to CSV (e.g. `"price >= 100"`) |
| `import` | `import <file.csv> [out.vortex]` | Convert CSV to Vortex (output defaults to `<input>.vortex`) |

Filter operators: `>`, `>=`, `<`, `<=`, `=`, `==`. Values are parsed as integer, double, boolean, or string.

### Exit codes

| Code | Meaning |
|---|---|
| 0 | Success |
| 1 | Usage error |
| 2 | File not found |
| 3 | Decode / I/O error |

### Examples

```bash
# inspect encoding layout
java -jar cli/target/vortex.jar inspect data/ohlc.vortex

# print schema
java -jar cli/target/vortex.jar schema data/ohlc.vortex
# → struct<symbol: utf8, open: F64, close: F64, volume: I64>

# row count
java -jar cli/target/vortex.jar count data/ohlc.vortex
# → 10000000

# per-column stats
java -jar cli/target/vortex.jar stats data/ohlc.vortex

# export to CSV
java -jar cli/target/vortex.jar export data/ohlc.vortex > out.csv

# project two columns
java -jar cli/target/vortex.jar select data/ohlc.vortex symbol close > prices.csv

# filter rows
java -jar cli/target/vortex.jar filter data/ohlc.vortex "volume >= 1000000" > large_trades.csv

# convert CSV to Vortex
java -jar cli/target/vortex.jar import data/trades.csv
# writes data/trades.vortex, prints size savings
```

## Requirements

- Java 25+
- `flatc` and `protoc` on `PATH` (build-time only: `brew install flatbuffers protobuf`)

Java 25 is the minimum because the FFM API (`MemorySegment`, `Arena`) was finalized as a
standard API in JDK 22 (JEP 454) — it was preview/incubator in JDK 19–21 and required
`--enable-preview` flags. Java 25 is the first LTS release to ship FFM as stable, so
requiring it means no preview flags, no upgrade risk, and a supported LTS for users.

## Build

```bash
./mvnw verify
```

## Running benchmarks

```bash
# Fast iteration — compile only (~2 s, no shade step)
./mvnw compile -pl performance -am -DskipTests
./mvnw exec:java -pl performance -Dexec.args="RustVsJavaReadBenchmark.javaReadVolume"

# Full fat jar — use for final/published numbers (~20 s)
./mvnw package -pl performance -am -DskipTests
java -jar performance/target/benchmarks.jar RustVsJavaReadBenchmark.javaReadVolume
java -jar performance/target/benchmarks.jar RustVsJavaWriteBenchmark.javaWrite

# Run all benchmarks
java -jar performance/target/benchmarks.jar
```

## License

Apache 2.0
