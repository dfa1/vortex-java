# vortex-java

[![CI](https://github.com/dfa1/vortex-java/actions/workflows/ci.yml/badge.svg)](https://github.com/dfa1/vortex-java/actions)

> **Alpha** — not production-ready. APIs will change without notice.

Pure-Java reader/writer for the [Vortex](https://github.com/spiraldb/vortex) columnar file format.

## Status

- Pure-Java reader for primitive, sequence, ALP, dict, FSST (stable)
- Local (mmap) or Remote (HTTPS, single read of last 65K) (stable)
- Writer: in progress
- Benchmark vs Rust+JNI: Java beats JNI 1.5×–6.5× across read/write workloads (see Benchmarks)
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
| close (F64/ALP)| 76.4 ± 1.6       | 50.8 ± 2.2       | **1.5×**     |
| volume (I64)   | 128.6 ± 1.5      | 52.3 ± 1.1       | **2.5×**     |
| symbol (varbin)| 63.4 ± 21.6      | 9.8 ± 1.4        | **6.5×**     |

### OHLC write — 10 M rows

| Benchmark | Java (ops/s) | JNI/Rust (ops/s) | Java speedup |
|-----------|--------------|------------------|--------------|
| write     | 4.6 ± 0.6    | 0.7 ± 0.0        | **6.5×**     |

### Big-file scan — 100 M rows × 4 I64 columns, ~3 GB (Rust-written file, all columns)

| Benchmark | Java (ops/s) | JNI/Rust (ops/s) | Java speedup |
|-----------|--------------|------------------|--------------|
| scan      | 20.3 ± 1.0   | 5.7 ± 0.2        | **3.6×**     |

## Design principles

- Zero-copy everywhere
- No JNI
- No Unsafe -- [FFM vs Unsafe](https://inside.java/2025/06/12/ffm-vs-unsafe/) — Maurizio Cimadamore's deep-dive on why FFM (`MemorySegment`/`Arena`) supersedes `sun.misc.Unsafe`: safety, performance, and the JVM's path forward
- Align with vortex-rust and Vortex-go semantics
- Make the JIT happy (constant layouts, predictable strides, no virtual dispatch in hot loops)
- Prepare for the Vector API / Valhalla

## Prior art and inspiration

| Project                                                     | Language | Notes                                                               |
|-------------------------------------------------------------|----------|---------------------------------------------------------------------|
| [spiraldb/vortex](https://github.com/spiraldb/vortex)       | Rust     | Reference implementation + JNI bindings                             |
| [spiraldb/vortex-go](https://github.com/spiraldb/vortex-go) | Go       | Pure-language port; primary inspiration for this project's approach |


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
<!-- TODO: replace with released coordinates -->
<dependency>
  <groupId>io.github.dfa1</groupId>
  <artifactId>vortex-java</artifactId>
  <version>0.0.0-SNAPSHOT</version>
</dependency>
```

Open and read a Vortex file:

```java
import io.github.dfa1.vortex.reader.VortexFile;
import io.github.dfa1.vortex.reader.ScanIterator;

try (VortexFile vf = VortexFile.open(Path.of("data/example.vortex"))) {
    try (ScanIterator it = vf.scan()) {
        while (it.hasNext()) {
            var row = it.next();
            // process row
        }
    }
}
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

## License

Apache 2.0
