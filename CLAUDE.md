# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What it is

Java 25 native implementation of the [Vortex](https://github.com/vortex-data/vortex) columnar file format. Uses FFM (
`MemorySegment`/`Arena`) instead of JNI or `sun.misc.Unsafe`.

## Branching strategy

Trunk-based development. PRs are fine but always squash or rebase — no merge commits.
Keep commits small and `main` always green.

## Commands

Never use `mvn install` or `./mvwn install`.

Generated sources (`fbs`/`proto` → Java) are committed under `core/src/main/java`.
Normal builds need no external tools.
To regenerate after editing `.fbs` or `.proto` schemas:

```bash
brew install flatbuffers protobuf
./mvnw generate-sources -pl core -P regenerate-sources
# then commit the updated files
```

Any `flatc` version works — the profile strips the version guard automatically.

```bash
# Build all modules
./mvnw verify

# Build without tests
./mvnw verify -DskipTests

# Run all tests (unit only — excludes *IntegrationTest)
./mvnw test

# Run tests in one module
./mvnw test -pl reader

# Run a single test class
./mvnw test -pl reader -Dtest=MyTest

# Run a single test method
./mvnw test -pl reader -Dtest=MyTest#myMethod

# Run integration tests (use verify + failsafe, NOT test + surefire)
./mvnw verify -pl integration -am

# Run a single integration test class
./mvnw verify -pl integration -am -Dit.test=RustWritesJavaReadsIntegrationTest

# Run a single integration test method
./mvnw verify -pl integration -am -Dit.test="RustWritesJavaReadsIntegrationTest#s3_pcoVortex_javaDecodeMatchesJni"

# Run all benchmarks
./bench

# Run specific benchmark class or method (always use ClassName.methodName filter)
./bench RustVsJavaReadBenchmark
./bench RustVsJavaReadBenchmark.javaReadVolume
```

### Javadoc

Every public method must have complete Javadoc. The build enforces this via
`failOnError=true` + `failOnWarnings=true` in the `maven-javadoc-plugin`.

Rules:

- Every public method needs a main description, `@param` for each parameter, and `@return` (unless `void`).
- Every public record needs `@param` entries on the class-level doc (one per component).
- Cross-references use `[ClassName#method(ParamType)]` — verify the target exists before writing it. Wrong references
  are **errors**, not warnings.
- `@see`-only Javadoc counts as "no main description" — always add a prose sentence.

**Check:** `./mvnw javadoc:javadoc -pl core` — must produce zero output.

### Releasing

```bash
./mvnw --batch-mode release:clean release:prepare \
    -DreleaseVersion=<version> \
    -DdevelopmentVersion=<next>-SNAPSHOT
git push && git push --tags
```

GitHub Actions picks up the tag and deploys to Maven Central.

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

Encoding IDs are strings (e.g. `"vortex.flat"`, `"fastlanes.bitpacked"`). `EncodingRegistry` maps IDs → `Encoding` impls
via `ServiceLoader`. The registry is immutable after construction — register custom encodings on the builder:
`EncodingRegistry.builder().registerServiceLoaded().register(myEncoding).build()`.

**Adding a new encoding:** three touch-points, always all three:

1. `EncodingId.java` — add enum constant `VORTEX_FOO("vortex.foo")`
2. `AlpRdEncoding.java` (or `FooEncoding.java`) — implement `Encoding`
3. `core/src/main/resources/META-INF/services/io.github.dfa1.vortex.encoding.Encoding` — add the fully-qualified class
   name

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

## POM dependency ordering

In every module `pom.xml`, dependencies are grouped with comments:

```xml
<!-- production -->
<!-- project-internal deps (io.github.dfa1.vortex:*) first, then external -->

<!-- testing -->
<!-- project-internal test deps first, then external -->
```

Omit a section if empty (e.g. integration module has no production deps; performance has no test deps).

## API design

- Keep public interfaces as small as possible.
- Don't expose internals. When in doubt, leave it out or make it private.

## Code style

- indents are 4 spaces, enforced by checkstyle
- Zero SonarQube bugs/smells policy.
- No `sun.misc.Unsafe` or internal JDK APIs.
- Prefer explicit over clever. Fail fast on unhandled cases.
- Always prefer idiomatic modern Java. Reuse the standard library and language
  features the JDK already provides — e.g. override `Iterator.forEachRemaining`
  instead of inventing a parallel `forEachChunk`; use `Optional`, records,
  sealed types, pattern switches, virtual threads, FFM — over hand-rolled
  equivalents. New APIs should look and feel like JDK APIs Java developers
  already know.
- Always use braces for `if`/`else`/`for`/`while` bodies, even single-liners:
  ```java
  // WRONG
  if (cond) return a;

  // CORRECT
  if (cond) {
      return a;
  }
  ```

## Encoding class structure

Encoding classes with non-trivial encode **and** decode paths must separate them into
private static inner classes named `Encoder` and `Decoder`. Shared low-level helpers
(buffer math, proto serialization) live in the side that owns them or in a third inner
class if genuinely shared by both.

```java
public final class FooEncoding implements Encoding {

    @Override
    public EncodeResult encode(DType dtype, Object data) {
        return Encoder.encode(dtype, data);
    }

    @Override
    public Array decode(DecodeContext ctx) {
        return Decoder.decode(ctx);
    }

    private static final class Encoder {
        static EncodeResult encode(DType dtype, Object data) { ...}
    }

    private static final class Decoder {
        static Array decode(DecodeContext ctx) { ...}
    }
}
```

Simple encodings (≤ ~80 lines total, e.g. `NullEncoding`, `BoolEncoding`) are exempt.

### Metadata-only encodings

Some encodings store all data in protobuf metadata — no buffers, no children (e.g. `SequenceEncoding`).
Their `EncodeResult` uses an `EncodeNode` with `metadata` set and an empty `bufferIndices` array:

```java
ByteBuffer metaBuf = ByteBuffer.wrap(meta.toByteArray());
EncodeNode node = new EncodeNode(encodingId, metaBuf, new EncodeNode[0], new int[]{});
return new

EncodeResult(node, List.of(), null,null);
```

The decoder reads back via `ctx.metadata()`, not `ctx.buffer(n)`.

## Testing

- Every feature needs unit tests covering: happy path, negative cases (invalid input, error conditions), and corner
  cases (empty, zero, max values, boundary conditions).
- Unit tests must be fast — no file I/O, no network, no sleep. Mock or use in-memory data.
- Integration tests are critical: there is no formal spec, so interoperability with the Rust reference implementation is
  the ground truth. Write integration tests for every encoding round-trip and file format boundary.
- JUnit 5 + Mockito (BDDMockito) + AssertJ.
- Every test has `// Given` / `// When` / `// Then` sections.
- Class under test is always named `sut`.
- Use `BDDMockito` exclusively: `given(mock.method()).willReturn(value)`. Never the reverse form. Only static-import
  `given` and `then` — not `willReturn`/`willThrow`.
- Prefer `@ParameterizedTest` over copy-pasting tests. Use `@ValueSource` when possible; `@ArgumentsSource` when more
  structure needed (test case must have a name).
- Use `@ParameterizedTest` with seeded random generators for encoding/decoding logic where input space is large — they
  find corner cases that example tests miss.
- Acceptance tests run the built jar end-to-end with hosh scripts.
- Use `@Nested` to group related tests by scenario or feature within a test class:
  ```java
  class FooEncodingTest {
      @Nested class Encode { @Test void roundTrips() { ... } }
      @Nested class Decode { @Test void handlesEmpty() { ... } }
  }
  ```
  `@BeforeEach` inside a `@Nested` class applies only to that group. Private helpers go
  at the end of the class they serve, after all `@Test` methods.

## Random-data parameterized tests

Use `@ParameterizedTest` + `@MethodSource` for random-input coverage. Put generators in `RandomArrays` (integration
module)
or a similar utility class. Static provider methods in the test class delegate to the generator:

```java
static Stream<long[]> i64ArrayProvider() {
    return RandomArrays.i64Arrays(30);
}

@ParameterizedTest
@MethodSource("i64ArrayProvider")
void roundTrips(long[] data) { ...}
```

Keep counts low (10–30) for integration tests that involve file I/O or JNI.
