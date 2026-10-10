# CLAUDE.md

Guidance for Claude Code working in this repository.

## What it is

Java 25 native implementation of the [Vortex](https://github.com/vortex-data/vortex) columnar
file format. Uses FFM (`MemorySegment`/`Arena`) — never JNI or `sun.misc.Unsafe`.

**Prime directive: behavioral parity with the Rust reference.** What we write, what we accept on
read, how the compressor picks and cascades encodings (schemes, children, exclusions), defaults and
error cases all mirror Rust (look it up per [Reference implementation](#reference-implementation)).
A size or speed win is never a reason to diverge; report it as information. A deliberate divergence
needs an explicit decision, recorded where it applies (e.g. the stricter field-name rule in
`docs/compatibility.md`).

### Naming convention (benchmarks & comparisons)

One vocabulary across all artifacts (tables, identifiers, prose):
- **`vortex-java`** / `java*` — this project.
- **`vortex-jni`** / `jni*` — the perf competitor: the Vortex Rust reference's JNI bindings.
  Numbers include JNI-boundary cost, so never label it `vortex-rust` (inaccurate + flame-bait).
- **`Rust`** — reserved for the *correctness* ground-truth only (oracle/interop tests like
  `RustWritesJavaReadsIntegrationTest`, "Rust-written file"). Not a perf label.

Benchmark classes follow this: `JavaVsJni{Read,Write,Filter}Benchmark`,
`JniWritesJavaReadsBigFileBenchmark`, methods `javaXxx`/`jniXxx`.

## Module structure

```
fsst    — io.github.dfa1.vortex.fsst: standalone FSST (Fast Static Symbol Table) string
          compression algorithm — Symbol, Compressor/CompressorBuilder, Decompressor/Matcher.
          Zero dependency on core/reader/writer/FFM-wire-concerns beyond the JDK's own
          java.lang.foreign; writer/reader depend on it, not the other way around.
core    — everything lives under `io.github.dfa1.vortex.core.*`:
          core.model    DType, PType, TimeUnit, EncodingId, LayoutId, ColumnName, ExtensionId, TimeDtype, TimestampDtype,
                        EditionId, Edition, EditionFamily, Editions, MemorySize
          core.io       IoBounds, PTypeIO, VortexFormat
          core.error    VortexException
          core.compute  FastLanes, PrimitiveArrays, Utf8Order
          core.simd     SimdOperations (kernels shared by reader+writer, all eleven ptypes),
                        AutoVectorizedSimdOperations (C2-shaped loops, the Rust-parity default),
                        VectorApiSimdOperations (opt-in via --add-modules jdk.incubator.vector; a few documented
                        differences from Rust, see docs/compatibility.md), SimdOperationsSupport#preferred() (picks one)
          core.fbs / core.proto — generated wire codecs + their runtimes
reader  — VortexReader, VortexHttpReader, VortexHandle, ReadRegistry, Chunk, ArrayStats, Zone,
          ScanOptions, RowFilter; file internals (Footer, Trailer, PostscriptParser, …)
          reader.array  — Array + all subtypes (decode outputs)
          reader.decode — EncodingDecoder, DecodeContext, ArrayNode + *EncodingDecoder impls
          reader.extension — ExtensionDecoder + Date/Time/Timestamp/Uuid impls
          reader.layout — Layout, LayoutDecoder, LayoutDecodeContext, LayoutRegistry
          + built-in *LayoutDecoder impls, ZonedStatsSchema
writer  — VortexWriter, WriteRegistry, WriteOptions, ColumnEncoding, ExtensionEncoder
          writer.encode — EncodingEncoder, EncodeContext, EncodeResult, EncodedBuffer, NullableData
          + *EncodingEncoder impls,
          extension encoders
```

Dependency rule: `writer → core`, `reader → core`. **Writer never depends on reader.**
`Array` and subtypes are decode outputs — they live in `reader.array`, not `core`.

## Branching

Trunk-based, `main` always green, small commits. The repo allows **rebase merges only** (squash
and merge commits are disabled): squash a PR's commits locally into one, force-push, then
`gh pr merge --rebase`.

## Commands

**Never `mvn install` / `./mvnw install`.** Normal builds need no external tools; generated
`fbs`/`proto` sources are committed under `core/src/main/java`.

```bash
./mvnw verify                              # build all
./mvnw verify -DskipTests                  # build, no tests
./mvnw test                                # unit only (excludes *IntegrationTest)
./mvnw test -pl reader -am                 # one module (-am: sibling jars are never installed)
./mvnw test -pl reader -am -Dtest=MyTest -Dsurefire.failIfNoSpecifiedTests=false     # one class
./mvnw test -pl reader -am -Dtest=MyTest#m -Dsurefire.failIfNoSpecifiedTests=false  # one method
./mvnw verify -pl integration -am          # integration (failsafe, NOT surefire)
./mvnw verify -pl integration -am -Dit.test="RustWritesJavaReadsIntegrationTest#method"
./bench JavaVsJniReadBenchmark.javaReadVolume   # benchmark — always ClassName.methodName filter
scripts/hydrate-raincloud-corpus.sh --max-mb 200   # hydrate real-world conformance corpus (#205), then:
./mvnw verify -pl integration -am -Dvortex.it.excludedGroups= -Dit.test="RaincloudConformanceIntegrationTest"
./mvnw test -pl fuzz -am -Dvortex.fuzz.excludedGroups=                # Jazzer regression mode (ADR 0020)
JAZZER_FUZZ=1 ./mvnw test -pl fuzz -am -Dvortex.fuzz.excludedGroups=  # actually fuzz (unbounded)
```

Tag-excluded from a routine local build, even when named with `-Dit.test`/`-Dtest`:
- **Slow integration tests** (`@Tag("slow")`, failsafe): seconds per test (big fixtures, S3
  downloads). The integration `ci` profile (auto-active when `CI` is set, as on GitHub Actions)
  runs them; locally clear the exclusion with `-Dvortex.it.excludedGroups=`. Tag any new
  integration test taking over ~1 s.
- **Raincloud corpus** (`@Tag("raincloud")`, failsafe): clear the exclusion with
  `-Dvortex.it.excludedGroups=` as shown above. Not run by the `ci` profile.
- **Jazzer fuzz** (`@Tag("fuzz")`, own `fuzz` module): clear it with `-Dvortex.fuzz.excludedGroups=`.
  Without `JAZZER_FUZZ=1` it only replays the saved corpus; with it, it fuzzes until stopped —
  never in CI or unattended. See [ADR 0020](adr/0020-jazzer-fuzz-infrastructure.md).

Regenerate after editing `.fbs`/`.proto` (both generators are in-house, no external tools):

```bash
./mvnw compile -pl fbs-gen,proto-gen                     # build the generators
./mvnw generate-sources -pl core -P regenerate-sources   # then commit
```

Both schema languages are compiled in-process to MemorySegment-native Java, with no
`flatc`/`protoc` and no `com.google.flatbuffers`/`protobuf-java` runtime (ADR 0017):
- **`.fbs` → `fbs-gen`** (`io.github.dfa1.vortex.fbsgen`): generates readers extending
  `FbsTable` (vtable-based) or `FbsMemorySegment` (fixed-offset inline) and builders over
  `FbsBuilder`, all in the same generated package `io.github.dfa1.vortex.core.fbs`. The
  runtime base classes `FbsTable`/`FbsMemorySegment` are package-private (only generated
  readers extend them); `FbsBuilder` is public because the writer module assembles FlatBuffers
  with it. Schema names with a trailing `_` (e.g. `Struct_`) have the underscore stripped in
  the generated Java class name (`FbsStruct`) — the upstream uses `_` to avoid C++ conflicts,
  which should not appear in Java.
- **`.proto` → `proto-gen`**: one record per message with static `decode(MemorySegment, long,
  long)` + `encode()` operating directly on a segment.

### Mutation testing

Opt-in [PIT](https://pitest.org) profile in `core` and `reader` (`-P pitest`), bound to the
`verify` phase and scoped to the bounds/parse classes via `<targetClasses>` in each module POM.
Used to harden the security-critical bounds guards (ADR 0003 Phase E).

```bash
./mvnw -pl reader -am -P pitest verify -DskipITs   # reader run (-am builds core; -DskipITs skips ITs)
./mvnw -pl core -P pitest verify                   # core run (IoBounds)
```

Report: `<module>/target/pit-reports/index.html` (+ `mutations.xml` for scripting). Widen a run by
adding `<param>` entries under `<targetClasses>` in the module's `pitest` profile.

Do not invoke the goal directly (`org.pitest:pitest-maven:mutationCoverage`) — it resolves the
latest plugin without the JUnit 5 engine and ignores the profile; always go through `-P pitest`.

Read survivors as a **simplify-first** signal, not only a test-gap signal: an equivalent mutant
often marks a clause that can never change the outcome (dead code) — delete it rather than writing
an unkillable test. Only add a test when the mutated bound is a genuine, independent edge.

### Releasing

```bash
./mvnw --batch-mode release:clean release:prepare \
    -DreleaseVersion=<version> -DdevelopmentVersion=<next>-SNAPSHOT
git push && git push --tags          # GitHub Actions deploys the tag to Maven Central
```

## File format

8-byte trailer at EOF: `version(u16 LE) | postscriptLen(u16 LE) | magic(VTXF)`. The postscript
(FlatBuffer, immediately before the trailer) points (offset+length) to the Footer (FlatBuffer),
DType (FlatBuffer), and Layout (FlatBuffer) blobs elsewhere in the file.

Layout tree: `Struct → Zoned(Stats) → Chunked → [Flat, Flat, ...]`
- **Flat** single encoded segment · **Chunked** sequence of Flats · **Struct** one child/column
- **Zoned** wraps a child with a per-zone stats table for zone-map pruning. By default the writer
  emits Rust's `vortex.zoned`: fixed 8192-row zones independent of `writeChunk` batches
  (`ZoneAccumulator`), Rust's per-dtype aggregate set, no zone sum (#447). Targets before
  `core2026.08.0` get the legacy `vortex.stats`: one zone per batch, whose declared length Rust
  reads as a stride, so it is the shared batch length or `0` when batches differ (#418)

Encoding IDs are strings (`"vortex.primitive"`, `"fastlanes.bitpacked"`). `ReadRegistry` maps IDs →
`EncodingDecoder`; immutable after construction, built-in decoders are registered explicitly by
`registerDefaults()` — register custom decoders on the builder:
`ReadRegistry.builder().registerDefaults().register(myDecoder).build()`.

### Adding an encoding

Add an `EncodingId.WellKnown` constant `VORTEX_FOO("vortex.foo")` (re-exported on the interface), then per side:
- **Decode:** `FooEncodingDecoder implements EncodingDecoder` in `reader.decode` + a
  `.register(new FooEncodingDecoder())` call in `ReadRegistry.Builder#registerDefaults()`
- **Encode:** `FooEncodingEncoder implements EncodingEncoder` in `writer.encode` + a
  `.register(new FooEncodingEncoder())` call in `WriteRegistry.Builder#registerDefaults()`.
  Every buffer is an `EncodedBuffer` declaring its **element** alignment
  (`EncodedBuffer.of(seg, ptype)`, `EncodedBuffer.bytes(seg)` for bitmaps/strings/opaque bytes).
  Rust holds a buffer to exactly that: less fails array construction, more aborts the JVM on the
  first row-range slice.

### Adding an extension type

Add `ExtensionId` constant, then per side:
- **Decode:** singleton `FooExtensionDecoder implements ExtensionDecoder` in `reader.extension` +
  a `case VORTEX_FOO` in `Chunk.as()` — not registry-managed.
- **Encode:** `FooExtensionEncoder implements ExtensionEncoder` in `writer` + a
  `.register(FooExtensionEncoder.INSTANCE)` call in `WriteRegistry.Builder#registerDefaults()`

## Memory model

`VortexReader` memory-maps the whole file into one confined-`Arena` `MemorySegment`. All `Array`
buffers returned during scan are zero-copy slices of it — lifetime tied to the reader; close to
release.

**Allocation rule — never `new byte[]` + `MemorySegment.ofArray()` for decode output.** Always
`ctx.arena().allocate(...)` (off-heap, zero GC, scan-chunk lifetime). If a private helper lacks
`DecodeContext`, pass an `Arena arena` param from the `decode()` call site.

```java
// WRONG: heap alloc, GC pressure, extra copy
MemorySegment out = MemorySegment.ofArray(new byte[(int) (n * elemBytes)]);
// CORRECT
MemorySegment out = ctx.arena().allocate(n * elemBytes);
```

**Hot-loop rule — no modulo/division/variable-target branch per element.** A single `i % cap` per
row blocks JIT auto-vectorization (C2 superword refuses `Op_ModL`/`Op_DivL`; no SIMD integer-divide
opcode) — and loop-invariant `cap` doesn't help (strength-reduction needs a *compile-time* constant
divisor). Scalar modulo is also 20–40 cycles vs ~1 for a load on Apple silicon. One modulo in a 1M-row
body has caused 5–10× regressions here (`ed658b7`→`051a794`→`442021f`). Same for bounds/validity-bit
checks and sign-extension switches — anything making the body non-uniform. For broadcast/clamp/mask,
**branch-split**: hoist the check once, gate two specialized loop bodies.

```java
long cap = SegmentBroadcast.capacity(src, 8);
if (cap == n) {                                  // fast path: zero modulos, vectorizes
    for (long i = 0; i < n; i++) { out.setAtIndex(LE_LONG, i, src.getAtIndex(LE_LONG, i)); }
} else {                                          // slow path: only ConstantEncoding broadcast
    for (long i = 0; i < n; i++) { out.setAtIndex(LE_LONG, i, src.getAtIndex(LE_LONG, i % cap)); }
}
```

Profile with JFR (`-prof stack:lines=10`); `idiv`/`sdiv`/arithmetic helpers as the hot frame is
almost always this.

## Security contract

The reader memory-maps and parses untrusted binary input. Every malformed input must throw
`VortexException`, never `ArrayIndexOutOfBoundsException`, `NegativeArraySizeException`,
`OutOfMemoryError`, `StackOverflowError`, a raw FlatBuffer runtime exception, or a Protobuf parser
exception. See the [`security` issues](https://github.com/dfa1/vortex-java/labels/security) for the current gap list (per-encoding adversarial
tests, resource caps, fuzz infra) and [ADR 0003](adr/0003-vortex-exception-sanitization.md) /
[ADR 0004](adr/0004-resource-caps-read-options.md) for the design.

## Reference implementation

When stuck on encode/decode behavior, consult **in this order**:

1. **Format spec** (primary, authoritative) — `https://github.com/vortex-data/vortex/tree/mp/spec/docs/specification`
   (via `gh api repos/vortex-data/vortex/contents/docs/specification?ref=mp/spec`):
   - `encoding-format.md` — per-encoding validity table (check this first for null/validity semantics)
   - `encoding-format/dict-runend-sparse.md` — Dict, RunEnd, Sparse layouts
   - `encoding-format/alp.md` — ALP and ALP-RD layouts
   - `encoding-format/misc.md` — DateTimeParts (`vortex.datetimeparts`) and other misc encodings

2. **Rust reference** (implementation detail) — `https://github.com/spiraldb/vortex`
   (via `gh api repos/spiraldb/vortex/contents/<path>`):
   `encodings/fastlanes/src/{bitpacking,for}/`, `encodings/sparse/src/`, `encodings/alp/src/alp/`, and
   `https://github.com/spiraldb/fastlanes-rs` (`src/bitpacking.rs`, `src/macros.rs`).

**Never reverse-engineer wire formats by probing bytes.** Read the spec first, then the Rust
`serialize`/`deserialize` vtable if the spec is ambiguous.

## Design decisions

- **DType is pluggable only via `Extension`.** `DType` is a sealed interface; downstream code must
  not add variants. Use `new DType.Extension("ip.address", new DType.Primitive(PType.I32, false),
  null, false)` and register decoders/encoders on the registries.
  Mirrors Rust (`vortex.date`, `vortex.uuid`, …). No SPI for DType variants planned.
- **Encoding and layout decode are both pluggable via builder-only registries** — no
  `ServiceLoader`. Encodings register on `ReadRegistry`/`WriteRegistry`
  (`ReadRegistry.builder().registerDefaults().register(custom).build()`); layouts register on
  `LayoutRegistry` (`LayoutRegistry.builder().registerDefaults().register(custom).build()`, pass to
  `VortexReader.open(path, readRegistry, layoutRegistry)`). Builder-registered only, by decision:
  the Rust reference registers encodings and layouts explicitly on the session (no auto-discovery
  exists there), and a classpath jar must not silently change decode/scan behavior — registration
  stays visible at the `open()`/`create()` call site. Unknown encodings can be opted into an
  allow-unknown passthrough (`ReadRegistry.Builder#allowUnknown()`); unknown layouts always fail
  loudly (`VortexException`, Rust default; no allowUnknown for layouts). Scope: the layout SPI covers
  full-column subtree decode; zone-map pruning, filtered scans, and chunk planning recognize the
  built-in layouts only.
- **Options types are plain final classes with a private constructor** (`WriteOptions`, `ScanOptions`,
  and the CSV/Parquet/JDBC import and export options): a `defaults()` or named factory returns the
  starting point and `withXxx` methods return copies. Never a record and never a public
  constructor — they hold callbacks or executors with no meaningful `equals`, and a constructor
  freezes the field list as API.
- **The Vector API implementation may differ from Rust in a few reductions** (decision recorded in
  `docs/compatibility.md` and `VectorApiSimdOperations`): lane-wise float sums and per-lane `I64`/`U64`
  overflow detection, instead of forcing scalar loops to keep Rust's exact order. It is opt-in
  (`--add-modules jdk.incubator.vector`); the default `AutoVectorizedSimdOperations` stays Rust-parity.
- **Small public APIs.** Don't expose internals — when in doubt, leave it out or make it private.
- **POM deps** grouped with comments: `<!-- production -->` then `<!-- testing -->`, each with
  project-internal (`io.github.dfa1.vortex:*`) deps first, then external. Omit empty sections.
- **Editions are a client-side write/read policy, not part of the wire format** (ADR 0023).
  `WriteOptions#editions()` gates which encodings a write may emit and is checked/enforced entirely
  at write time; nothing about a targeted edition is ever persisted into a `.vortex` file — the
  compatibility guarantee is always re-derivable from the encoding ids already in the footer plus
  the shared `Editions` catalog. `EditionFamily` is a closed enum (`CORE`/`PREVIEW`/`ZSTD`, Rust's families), unlike
  `EncodingId`/`LayoutId`'s sealed-interface-plus-`Custom` shape: a private edition family would
  carry no real cross-implementation guarantee, so there is no legitimate use case for one.

## Documentation is part of every change

Living docs ship in the same commit/PR as the change they describe — never as a follow-up
sweep. A change touching public API, module structure, wire behavior, or policy updates
whichever apply: `docs/reference.md`, `docs/compatibility.md`, the CLAUDE.md module map /
design decisions, and the CHANGELOG entry for the change. This includes the CHANGELOG itself —
add the `## [Unreleased]` entry (terse, per the `changelog` skill's style) in the same commit as
the fix/feature, not a separate trailing `docs:` commit. Historical records (`adr/`) are exempt —
they describe the past. Docs drift is a bug (2026-07-04: a single audit found phantom APIs, dead
service files, and pre-refactor FQNs across four files).

### Changelog entries

One sentence + a link to the PR/issue/commit, nothing else (the `changelog` skill has the full
procedure):

```
- Bit-packing encodes about 45% faster. ([#475](https://github.com/dfa1/vortex-java/issues/475))
```

- A headline figure ("by 10%") is fine. Rationale, benchmark tables and before/after numbers belong
  in the commit message and code, never the changelog.
- No `Highlights` prose, and no tests/CI/build/docs-only entries.
- Release notes are the version's CHANGELOG section verbatim; after rewriting a released section,
  regenerate its GitHub release with `gh release edit vX.Y.Z --notes-file`.

## Code style

- 4-space indent, **zero SonarQube bugs/smells**, no `sun.misc.Unsafe` or internal JDK APIs.
- **American English everywhere** (javadoc, comments, identifiers):
  `recognize`/`optimize`/`finalize`/`serialize`/`normalize`/`behavior`/`color` — never
  `-ise`/`-isation`/`-our`. Matches the JDK (`Object.finalize`, `Serializable`).
- Prefer explicit over clever; fail fast on unhandled cases.
- Idiomatic modern Java: reuse the JDK (override `Iterator.forEachRemaining`, don't invent
  `forEachChunk`; use `Optional`, records, sealed types, pattern switches, virtual threads, FFM).
  New APIs should feel like JDK APIs.
- Always braces for `if`/`else`/`for`/`while`, even one-liners (`if (c) { return a; }`).
- **Time quantities use `java.time.Duration`, never `long`** (no `long timeoutMs`/`delayNanos`).
  Exception: low-level JDK interop taking `long ns` (`Thread.sleep`, `LockSupport.parkNanos`,
  `System.nanoTime` math) — convert at the call site via `duration.toNanos()`/`toMillis()`.

### Javadoc

Run by `javadoc-no-fork` bound to `verify` (~3s across the reactor), so `./mvnw test` skips it
and `./mvnw verify` enforces it. Two enforcement levels:

- **Everywhere: `failOnError`.** A dangling `[Type#member]` reference fails the build. This is
  the class of rot that actually accumulates — a refactor renames something and the docs keep
  pointing at the old name.
- **`core` and `fsst` only: `failOnWarnings`.** Both are warning-free; their POMs raise the bar
  to keep them there. The other modules carry pre-existing warnings (missing `@param`,
  undocumented default constructors), so warnings stay non-fatal there until someone clears a
  module and adds the same override to its POM.

Style rules (aspirational in the un-ratcheted modules, enforced in `core`/`fsst`):

- Every public method: main prose description, `@param` per parameter, `@return` (unless `void`).
  Every public record: `@param` per component on the class doc. `@see`-only counts as no description.
- All `///` Markdown — **no HTML** (checkstyle `RegexpSingleline` blocks `<p>`,`<ul>`,`<li>`,
  `<strong>`,`<pre>`,`<table>`, …). Use blank `///` for paragraphs, `- ` lists, ` ```java ``` `,
  `**bold**`. Cross-refs `[ClassName#method(ParamType)]` — verify the target exists (wrong refs are
  **errors**). A target the module cannot see is also an error: writer must not link to reader
  types, and `fbs-gen` must not link to the `core.fbs` classes it generates — name those in
  backticks instead.
- Ad-hoc check: `./mvnw package -DskipTests javadoc:javadoc -fae`. The bare
  `./mvnw javadoc:javadoc` fails on dependency resolution, not javadoc — the goal runs no
  lifecycle phase, so the reactor jars do not exist and `install` is forbidden.

### Encoding class structure

Decode and encode are separate classes in separate modules (writer never depends on reader) —
there is no unified class implementing both directions. A non-trivial `FooEncodingDecoder`/
`FooEncodingEncoder` factors its logic into a single private static inner class named after its
own direction (shared low-level helpers live with their owner):

```java
public final class FooEncodingEncoder implements EncodingEncoder {
    @Override public EncodeResult encode(DType dtype, Object data, EncodeContext ctx) {
        return Encoder.encode(dtype, data, ctx);
    }
    private static final class Encoder { static EncodeResult encode(DType dtype, Object data, EncodeContext ctx) { ... } }
}
```

Simple encodings (≤ ~30 lines, e.g. `NullEncodingDecoder`, `NullEncodingEncoder`) are exempt and
put their logic straight in the class body — no inner helper class.

**Metadata-only encodings** (all data in proto3 metadata, no buffers/children, e.g.
`SequenceEncodingEncoder`/`SequenceEncodingDecoder`):
`EncodeResult` uses an `EncodeNode` with `metadata` set and empty `bufferIndices`; the decoder reads
`ctx.metadata()` (not `ctx.buffer(n)`):

```java
EncodeNode node = new EncodeNode(encodingId, MemorySegment.ofArray(meta.encode()), new EncodeNode[0], new int[]{});
// decode:
MemorySegment metaSeg = ctx.metadata();
FooMetadata meta = FooMetadata.decode(metaSeg, 0, metaSeg.byteSize());
```

Generated proto records live in `io.github.dfa1.vortex.core.proto`; the runtime (`ProtoReader`,
`ProtoWriter`) is package-private. For oneof messages (e.g. `ScalarValue`) prefer the static
`ofXxxValue(v)` factory over the multi-arg constructor.

## Testing

- Cover happy path, negative cases (invalid input / errors), and corners (empty, zero, max,
  boundaries). Unit tests must be fast — no file I/O, network, or sleep; mock or use in-memory data.
- **Integration tests are ground truth** (no formal spec): interop with the Rust reference. Write
  one for every encoding round-trip and file-format boundary. Include **filtered and row-range**
  vortex-jni reads, not just full scans: a full scan never prunes or slices, which hid a JVM abort
  (buffer alignment) and silently empty filtered results (zone stride) until 0.15.x.
- JUnit 5 + Mockito (BDDMockito) + AssertJ. Class under test named `sut`. Every test has
  `// Given` / `// When` / `// Then`. BDDMockito only: `given(mock.m()).willReturn(v)` /
  `then(...)` (static-import only `given`/`then`, never `willReturn`/`willThrow`).
- Prefer `@ParameterizedTest` over copy-paste (`@ValueSource`, else `@ArgumentsSource`/named cases).
  For large input spaces use seeded-random `@MethodSource` generators — they find corners examples
  miss. Put generators in `RandomArrays` (integration) or a similar util; keep counts low (10–30)
  when the test does file I/O or JNI.
- `@Nested` groups related scenarios (`@BeforeEach` in a nested class applies only to it). Private
  helpers go after all `@Test` methods.
