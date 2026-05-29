# vortex-java

> **Experimental** — not production-ready. APIs will change without notice.

Pure-Java reader/writer for the [Vortex](https://github.com/spiraldb/vortex) columnar file format.

## Performance

`RustVsJavaReadBenchmark` — 10M OHLC rows, JMH throughput (higher = better), Apple M-series, Java 25:

| Column           | Encoding          | vortex-jni  | vortex-java  | ratio         |
|------------------|-------------------|-------------|--------------|---------------|
| `volume` (I64)   | primitive         | 51.9 ops/s  | 120.7 ops/s  | **Java 2.3×** |
| `close` (F64)    | ALP               | 62.6 ops/s  | 118.0 ops/s  | **Java 1.9×** |
| `symbol` (UTF-8) | constant (varbin) | 10.9 ops/s  | 27.8 ops/s   | **Java 2.5×** |

Reproduce: `./benchmark.sh RustVsJavaReadBenchmark`. Hardware / JDK build / commit SHA used to produce
this snapshot should be captured alongside any update (see TODO #10c).

`RustWritesJavaReadsBigFileBenchmark` — 3 GB file, 4 × I64 columns of random data (defeats
bit-packing so segments stay large), projection on `c0`, JMH throughput:

| Reader      | Throughput     | ms/op | Decode rate |
|-------------|----------------|-------|-------------|
| vortex-jni  | 5.9 ops/s      | ~170  | ~4.7 GB/s   |
| vortex-java | **19.7 ops/s** | ~51   | ~15.7 GB/s — **Java 3.4×** |

Memory-bandwidth bound on Apple M-series. Reproduce: `./benchmark.sh RustWritesJavaReadsBigFileBenchmark`
(adds a ~30 s fixture build for the JNI write). Pass `-Dvortex.bench.bigfile=<path>` to reuse an existing
fixture between runs.

All columns decoded faster in pure Java than via JNI + Apache Arrow. Key optimisations:
`static final` ValueLayout constants (JIT constant-folding), aligned arena allocation
(`allocate(n, alignment)`), `getAtIndex()`/`setAtIndex()` (clearer stride for the
auto-vectoriser), and O(1) bytes allocation for constant strings (alternating offsets
into a single shared byte buffer).

## Motivation

The official Vortex ecosystem provides JVM bindings via JNI (bundled native `.so`/`.dylib`).
JNI bindings are fast but add deployment friction: platform-specific artifacts, native build
toolchains, and crash-domain coupling between the JVM and native code.

This library takes a different approach — 100% Java, no JNI, no `sun.misc.Unsafe`.
It uses the Java FFM API (`MemorySegment` / `Arena`, Java 22+) for zero-copy memory-mapped
reads, making it easier to:

- embed in any JVM project without native-library management
- build and test on any platform with a standard JDK
- debug and profile with standard JVM tooling

### Why fewer layers = faster

```
  vortex-jni                              vortex-java
  ──────────────────────────────          ──────────────────────────
  ┌──────────────────────────┐            ┌──────────────────────┐
  │  Java App                │            │  Java App            │
  │  (BigIntVector.get(i))   │            │  (buffer.getAtIndex) │
  └────────────┬─────────────┘            └──────────┬───────────┘
               │ Arrow Java API                      │ FFM API
  ┌────────────▼─────────────┐                      │ (MemorySegment,
  │  Apache Arrow (Java)     │                      │  zero-copy slice)
  │  VectorSchemaRoot,       │                      │
  │  BigIntVector, …         │                      │
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

## Why Vortex instead of Parquet

|                        | Parquet                                | Vortex                             |
|------------------------|----------------------------------------|------------------------------------|
| Max column-chunk size  | 2 GB (`int32` page offsets)            | 4 GB per segment (`uint32` length) |
| Max file offset        | 2 GB (`int32` on some implementations) | 16 EB (`uint64` in footer)         |
| Encoding extensibility | Fixed codec set                        | Plugin registry, any encoding      |

Files larger than 2 GB are a practical problem with Parquet: the `int32` data-page size field in the page header caps
individual column chunks at 2 GB.
Vortex uses `uint64` offsets throughout the footer, and this library maps files with `MemorySegment` (Java FFM API),
which has no per-mapping size limit — unlike the legacy `FileChannel.map()` API that caps each mapping at 2 GB.

## Prior art and inspiration

| Project                                                     | Language | Notes                                                               |
|-------------------------------------------------------------|----------|---------------------------------------------------------------------|
| [spiraldb/vortex](https://github.com/spiraldb/vortex)       | Rust     | Reference implementation + JNI bindings                             |
| [spiraldb/vortex-go](https://github.com/spiraldb/vortex-go) | Go       | Pure-language port; primary inspiration for this project's approach |

### Further reading

- [FFM vs Unsafe](https://inside.java/2025/06/12/ffm-vs-unsafe/) — Maurizio Cimadamore's deep-dive on why FFM (`MemorySegment`/`Arena`) supersedes `sun.misc.Unsafe`: safety, performance, and the JVM's path forward

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
