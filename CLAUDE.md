# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

Build prerequisites: `brew install flatbuffers protobuf` (flatc + protoc must be on PATH).
Never use `mvn install` or `./mvwn install`.

```bash
# Build all modules
./mvnw verify

# Build without tests
./mvnw verify -DskipTests

# Run all tests
./mvnw test

# Run tests in one module
./mvnw test -pl reader

# Run a single test class
./mvnw test -pl reader -Dtest=MyTest

# Run a single test method
./mvnw test -pl reader -Dtest=MyTest#myMethod

# Build benchmark fat jar (slow: ~20s, use for final runs)
./mvnw package -pl performance -am -DskipTests

# Run all benchmarks
java -jar performance/target/benchmarks.jar

# Run specific benchmark class
java -jar performance/target/benchmarks.jar RustVsJavaReadBenchmark

# Run specific benchmark method (always use ClassName.methodName filter)
java -jar performance/target/benchmarks.jar RustVsJavaReadBenchmark.javaReadVolume

# Fast iteration (no shade, ~2s compile): compile then exec:java
./mvnw compile -pl performance -am -DskipTests
./mvnw exec:java -pl performance -Dexec.args="RustVsJavaReadBenchmark.javaReadVolume"
```

## Architecture

Java 25 native implementation of the [Vortex](https://github.com/spiraldb/vortex) columnar file format. Uses FFM (
`MemorySegment`/`Arena`) instead of JNI or `sun.misc.Unsafe`.

### Module dependency chain

```
core → reader
     → writer
```

| Module   | Responsibility                                                                                                                                                                                       |
|----------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `core`   | Logical types (`DType`, `PType`), file-structure model (`Layout`, `Footer`, `SegmentSpec`, `CompressionScheme`), encoding layer (`Array`, `Decoder`/`DecoderRegistry`, `ArrayNode`, `DecodeContext`) |
| `reader` | `VortexFile` (memory-mapped file handle), `PostscriptParser`, `ScanIterator` (chunk-by-chunk reads), `ScanOptions`, `RowFilter` (zone-map predicate tree)                                            |
| `writer` | `VortexWriter` (encodes and writes chunks), `WriteOptions`                                                                                                                                           |

### File format

8-byte trailer at EOF: `version(u16 LE) | postscriptLen(u16 LE) | magic(VTXF)`. The postscript is a FlatBuffer blob
immediately before the trailer; it points (offset+length) to the Footer (FlatBuffer), DType (Protobuf), and Layout (
FlatBuffer) blobs elsewhere in the file.

### Layout tree

```
Struct → Zoned(Stats) → Chunked → [Flat, Flat, ...]
```

- **Flat**: single encoded segment on disk
- **Chunked**: sequence of Flat children
- **Zoned** (`vortex.stats`): wraps a child with per-chunk min/max statistics used for zone-map pruning
- **Struct**: one child per column

Encoding IDs are strings (e.g. `"vortex.flat"`, `"fastlanes.bitpacked"`). `DecoderRegistry` maps IDs → `Decoder` impls
via `ServiceLoader`; register custom decoders with `registry.register(decoder)`.

### Memory model

`VortexFile` memory-maps the entire file into one `MemorySegment` (confined `Arena`). All `Array` buffers returned
during scan are zero-copy slices of that segment — their lifetime is tied to the `VortexFile`. Close the file to release
the mapped region.

**Encoding output allocation rule:** never allocate `byte[]` + wrap with `MemorySegment.ofArray()` for decode output.
Always allocate from `ctx.arena()`:
```java
// WRONG — heap allocation, GC pressure, extra copy
byte[] outBytes = new byte[(int) (n * elemBytes)];
MemorySegment out = MemorySegment.ofArray(outBytes);

// CORRECT — off-heap, zero GC, same lifetime as the scan chunk
MemorySegment out = ctx.arena().allocate(n * elemBytes);
```
If the allocation is in a private static helper that doesn't receive `DecodeContext`, add an `Arena arena` parameter
and pass `ctx.arena()` from the `decode()` call site.

### Implementation status

Core read/write path is functional. See `TODO.md` for open work and roadmap.

## Reference implementation

When stuck on encoding/decoding behavior, consult the Rust reference implementation at
`https://github.com/spiraldb/vortex` (via `gh api repos/spiraldb/vortex/contents/<path>`).

Key paths:

- `encodings/fastlanes/src/bitpacking/` — `fastlanes.bitpacked` wire format and algorithm
- `encodings/fastlanes/src/for/` — `fastlanes.for` (frame-of-reference)
- `encodings/sparse/src/` — `vortex.sparse`
- `encodings/alp/src/alp/` — `vortex.alp`
- `https://github.com/spiraldb/fastlanes-rs` — FastLanes bit-packing algorithm (`src/bitpacking.rs`, `src/macros.rs`)

Never reverse-engineer wire formats by probing byte patterns. Read the vtable `serialize`/`deserialize`
methods in the Rust source to get the exact protobuf schema, then implement from spec.

## Code style

- Java 25. No Kotlin, no Gradle.
- Zero SonarQube bugs/smells policy.
- No `sun.misc.Unsafe` or internal JDK APIs.
- Prefer explicit over clever. Fail fast on unhandled cases.
- Always use braces for `if`/`else`/`for`/`while` bodies, even single-liners:
  ```java
  // WRONG
  if (cond) return a;

  // CORRECT
  if (cond) {
      return a;
  }
  ```

## Testing

- All features covered by unit tests. Always check the happy path at minimum.
- JUnit 5 + Mockito (BDDMockito) + AssertJ.
- Every test has `// Given` / `// When` / `// Then` sections.
- Class under test is always named `sut`.
- Use `BDDMockito` exclusively: `given(mock.method()).willReturn(value)`. Never the reverse form. Only static-import
  `given` and `then` — not `willReturn`/`willThrow`.
- Prefer `@ParameterizedTest` over copy-pasting tests. Use `@ValueSource` when possible; `@ArgumentsSource` when more
  structure needed (test case must have a name).
- Acceptance tests run the built jar end-to-end with hosh scripts.

## Property-Based Testing (jqwik)

jqwik 1.9.3 works with JUnit Jupiter 5.11.x (JUnit Platform 1.x). Use `@Property` + `@ForAll` for parameters,
`@Provide` for custom arbitraries, `Assume.that(...)` for preconditions.

Keep `tries` low (10–20) for integration tests that involve file I/O or JNI; unit-level properties can use the
default (100).
