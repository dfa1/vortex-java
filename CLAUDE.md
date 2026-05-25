# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

Build prerequisites: `brew install flatbuffers protobuf` (flatc + protoc must be on PATH).

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
```

## Architecture

Java 25 native implementation of the [Vortex](https://github.com/spiraldb/vortex) columnar file format. Uses FFM (`MemorySegment`/`Arena`) instead of JNI or `sun.misc.Unsafe`.

### Module dependency chain

```
core → reader
     → writer
```

| Module   | Responsibility |
|----------|----------------|
| `core`   | Logical types (`DType`, `PType`), file-structure model (`Layout`, `Footer`, `SegmentSpec`, `CompressionScheme`), encoding layer (`Array`, `Decoder`/`DecoderRegistry`, `ArrayNode`, `DecodeContext`) |
| `reader` | `VortexFile` (memory-mapped file handle), `PostscriptParser`, `ScanIterator` (chunk-by-chunk reads), `ScanOptions`, `RowFilter` (zone-map predicate tree) |
| `writer` | `VortexWriter` (encodes and writes chunks), `WriteOptions` |

### File format

8-byte trailer at EOF: `version(u16 LE) | postscriptLen(u16 LE) | magic(VTXF)`. The postscript is a FlatBuffer blob immediately before the trailer; it points (offset+length) to the Footer (FlatBuffer), DType (Protobuf), and Layout (FlatBuffer) blobs elsewhere in the file.

### Layout tree

```
Struct → Zoned(Stats) → Chunked → [Flat, Flat, ...]
```

- **Flat**: single encoded segment on disk
- **Chunked**: sequence of Flat children
- **Zoned** (`vortex.stats`): wraps a child with per-chunk min/max statistics used for zone-map pruning
- **Struct**: one child per column

Encoding IDs are strings (e.g. `"vortex.flat"`, `"fastlanes.bitpacked"`). `DecoderRegistry` maps IDs → `Decoder` impls via `ServiceLoader`; register custom decoders with `registry.register(decoder)`.

### Memory model

`VortexFile` memory-maps the entire file into one `MemorySegment` (confined `Arena`). All `Array` buffers returned during scan are zero-copy slices of that segment — their lifetime is tied to the `VortexFile`. Close the file to release the mapped region.

### Implementation status

Several entry points are stubs that throw `UnsupportedOperationException`:
- `PostscriptParser.parse()` — needs FlatBuffer generated sources
- `ScanIterator.hasNext()` — needs layout-tree traversal + zone-map pruning
- `VortexWriter.writeChunk()` / `close()` — needs encoding + footer serialization

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
- Use `BDDMockito` exclusively: `given(mock.method()).willReturn(value)`. Never the reverse form. Only static-import `given` and `then` — not `willReturn`/`willThrow`.
- Prefer `@ParameterizedTest` over copy-pasting tests. Use `@ValueSource` when possible; `@ArgumentsSource` when more structure needed (test case must have a name).
- Acceptance tests run the built jar end-to-end with hosh scripts.

## Property-Based Testing (jqwik)

**Known issue:** jqwik 1.9.3 targets JUnit Platform 1.x; project uses JUnit 6 (Platform 6.x). `@Property` tests compile and are structurally correct but the jqwik engine does not execute them at runtime. Track https://github.com/jqwik-team/jqwik/issues for jqwik 2.x.

Write property tests: `@Property` + `@ForAll` for parameters, `@Provide` for custom arbitraries, `Assume.that(...)` for preconditions.
