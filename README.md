# vortex-java

> **Experimental** — not production-ready. APIs will change without notice.

Pure-Java reader/writer for the [Vortex](https://github.com/spiraldb/vortex) columnar file format.

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

## Build

```bash
./mvnw verify
```

## License

Apache 2.0
