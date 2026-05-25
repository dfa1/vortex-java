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

## Prior art and inspiration

| Project | Language | Notes |
|---------|----------|-------|
| [spiraldb/vortex](https://github.com/spiraldb/vortex) | Rust | Reference implementation + JNI bindings |
| [spiraldb/vortex-go](https://github.com/spiraldb/vortex-go) | Go | Pure-language port; primary inspiration for this project's approach |

## Status

| Component | Status |
|-----------|--------|
| Trailer + postscript parsing | Working |
| FlatBuffer footer / layout / dtype | Working |
| Zone-map predicate pruning | Not started |
| Scan iterator (chunked reads) | Not started |
| Writer | Not started |

## Requirements

- Java 25+
- `flatc` and `protoc` on `PATH` (build-time only: `brew install flatbuffers protobuf`)

## Build

```bash
./mvnw verify
```

## License

Apache 2.0
