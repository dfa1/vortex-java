# Contributing to vortex-java

Thanks for your interest. This guide covers everything needed to contribute code.

## Prerequisites

- Java 25+ (build and tests require it — FFM API is finalized in JDK 22, Java 25 is the first LTS)
- Maven wrapper (`./mvnw`) included — no separate Maven install needed
- No other tooling required for normal builds (generated sources are committed)

## Build and test

```bash
# Full build + unit tests
./mvnw verify

# Skip tests (faster iteration)
./mvnw verify -DskipTests

# Single module
./mvnw test -pl reader

# Single test class
./mvnw test -pl reader -Dtest=MyTest

# Single test method
./mvnw test -pl reader -Dtest=MyTest#myMethod

# Integration tests (use verify + failsafe, NOT test + surefire)
./mvnw verify -pl integration -am

# Single integration test class
./mvnw verify -pl integration -am -Dit.test=RustWritesJavaReadsIntegrationTest

# Benchmarks (always pass ClassName.methodName — never run without a filter)
./bench JavaVsJniReadBenchmark.javaReadVolume
```

CI runs `./mvnw verify` on Java 25 and 26. Both must pass before merge.

## Module layout

| Module        | Purpose                                              |
|---------------|------------------------------------------------------|
| `fsst`        | Standalone FSST string compression algorithm         |
| `core`        | DTypes, encodings, memory model, protobuf/flatbuf    |
| `reader`      | `VortexReader`, scan API, layout decoders            |
| `writer`      | `VortexWriter`, cascading compressor                 |
| `integration` | Cross-language tests (Java ↔ Rust JNI oracle)        |
| `fuzz`        | Jazzer fuzz targets (opt-in, `JAZZER_FUZZ=1`)         |
| `performance` | JMH benchmarks                                       |
| `cli`         | Fat-jar CLI (`count`, `schema`, `inspect`, `import`) |
| `inspector`   | Layout-tree introspection (TUI + `VortexInspector`)  |
| `parquet`     | Parquet ↔ Vortex converter                           |
| `csv`         | CSV ↔ Vortex converter                               |
| `jdbc`        | JDBC driver (experimental)                           |
| `calcite`     | Apache Calcite SQL adapter (filter/project/aggregate push-down) |

## Finding work

[TODO.md](TODO.md) is the canonical work list. Items are grouped by area. Good starting points
for a first contribution:

- **Testing** — add adversarial/fuzz tests for the reader (see the Security review section in
  TODO.md); self-contained, no format knowledge required
- **Docs** — format specification diagrams (TODO.md → Documentation); improves everyone's
  understanding while building your own
- **`vortex.zstd` nullable encode** — bounded scope, well-specified in TODO.md, mirrors what
  other encodings already do

More complex items (global dict, pco encode, Vector API) have detailed design notes in
TODO.md — read those before starting.

## Making a change

1. Fork and create a branch off `main`.
2. Keep commits small. `main` must always be green.
3. PR merges are **squash or rebase only** — no merge commits.
4. Include tests (see [Testing](#testing)).
5. Update docs if the change affects public API or user-visible behavior.

Open a GitHub issue first for anything larger than a bug fix or a small feature — it saves
time if the design needs discussion.

## Code style

- 4-space indents (enforced by Checkstyle)
- **Always use braces** for `if`/`else`/`for`/`while`, even single-liners
- No `sun.misc.Unsafe` or internal JDK APIs
- Prefer explicit over clever; fail fast on unhandled cases
- Zero SonarQube bugs/smells policy

### Encoding output allocation

Never allocate `byte[]` and wrap with `MemorySegment.ofArray()` for decode output.
Always allocate from `ctx.arena()`:

```java
// WRONG
byte[] outBytes = new byte[(int) (n * elemBytes)];
MemorySegment out = MemorySegment.ofArray(outBytes);

// CORRECT
MemorySegment out = ctx.arena().allocate(n * elemBytes);
```

### Encoding structure

Decode and encode live in **separate classes, in separate modules** — the writer module never
depends on the reader module, so there is no single class that implements both directions.
A non-trivial decoder or encoder factors its logic into private static helper methods on the
class itself (see `AlpEncodingDecoder`, `DictEncodingEncoder` for examples):

```java
public final class FooEncodingDecoder implements EncodingDecoder {

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_FOO;
    }

    @Override
    public Array decode(DecodeContext ctx) {
        return decodeSomething(ctx);
    }

    private static Array decodeSomething(DecodeContext ctx) { ... }
}
```

## Adding a new encoding

Two touch-points, one per side:

1. **`EncodingId.java`** — add a `WellKnown` constant `VORTEX_FOO("vortex.foo")`.
2. **Decode** — `FooEncodingDecoder implements EncodingDecoder` in `reader`'s
   `io.github.dfa1.vortex.reader.decode` package, registered via
   `.register(new FooEncodingDecoder())` in `ReadRegistry.Builder#registerDefaults()`.
3. **Encode** — `FooEncodingEncoder implements EncodingEncoder` in `writer`'s
   `io.github.dfa1.vortex.writer.encode` package, registered via
   `.register(new FooEncodingEncoder())` in `WriteRegistry.Builder#registerDefaults()`.

Registration is builder-only, by design — no `ServiceLoader`/`META-INF/services`: a classpath
jar must not silently change decode/scan behavior, so registration stays visible at the
`ReadRegistry.builder()...build()` / `WriteRegistry.builder()...build()` call site. Missing a
registration call means the encoding works in isolation but is invisible to
`VortexReader.open`/`VortexWriter.create` when they use the default registries.

When unsure about the wire format, read the Rust reference implementation — do not
reverse-engineer byte patterns:

```bash
# Read Rust source for an encoding
gh api repos/spiraldb/vortex/contents/encodings/fastlanes/src/bitpacking/
```

Key paths: `encodings/fastlanes/src/` (bitpacked, for), `encodings/alp/src/alp/`,
`encodings/sparse/src/`.

## Testing

Every change needs:

- **Happy path** — basic round-trip
- **Negative cases** — invalid input, error conditions
- **Corner cases** — empty arrays, zero values, max values, boundary conditions

Test conventions:

```java
class FooEncodingTest {
    @Nested class Encode {
        @Test
        void roundTrips() {
            // Given
            ...
            // When
            ...
            // Then
            ...
        }
    }
    @Nested class Decode { ... }
}
```

- Class under test is always named `sut`
- Use `BDDMockito` exclusively: `given(mock.method()).willReturn(value)`
- Use `@ParameterizedTest` over copy-pasting; use seeded random generators for encoding
  logic where the input space is large
- Unit tests: no file I/O, no network, no sleep

**Integration tests are mandatory for new encodings.** The Rust JNI reader is the test
oracle — Java writes a file, Rust reads it, values must match exactly. This catches
wire-format bugs that pure Java round-trips miss.

## Javadoc

Every public method needs a main description, `@param` for each parameter, and `@return`
(unless `void`). All `///` Markdown — no HTML. Cross-references use Markdown links,
`[ClassName#method(ParamType)]` — verify the target exists before writing it. Wrong
references are build errors.

Check: `./mvnw package -DskipTests javadoc:javadoc -fae` (the bare `./mvnw javadoc:javadoc`
fails on dependency resolution, not javadoc, since the goal runs no lifecycle phase).

## Documentation

Update docs alongside code. Each file has a defined scope — touch the right one:

| File                       | When to update                                     |
|----------------------------|----------------------------------------------------|
| `docs/tutorial.md`         | New write/read capability that changes the flow    |
| `docs/how-to.md`           | New recipe (filter, convert, CLI subcommand, etc.) |
| `docs/reference.md`        | New API surface, CLI flags, operator table entries |
| `docs/compatibility.md`    | New encoding support or S3 fixture status change   |
| `docs/explanation.md`      | Design decisions, architecture changes, benchmarks |
| `docs/testing.md`          | New test layer, or a module's test counts/scope changed |

## Regenerating generated sources

Only needed after editing `.fbs` or `.proto` schemas. Both schema languages are compiled
in-process by in-house generators (`fbs-gen`, `proto-gen`) — no `flatc`/`protoc` install needed:

```bash
./mvnw compile -pl fbs-gen,proto-gen                     # build the generators
./mvnw generate-sources -pl core -P regenerate-sources   # then commit the updated files
```

Generated sources under `core/src/main/java` are committed — normal builds need no
external tools.
