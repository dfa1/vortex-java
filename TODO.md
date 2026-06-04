# TODO

## Project

- [ ] Move project to a dedicated organization
- [ ] Create website
- [ ] Publish benchmarks — run `./bench` locally, push JMH JSON to gh-pages via `bench-publish` script, view at `https://jmh.morethan.io/?source=https://dfa1.github.io/vortex-java/benchmark-result.json`; dated files for history comparison via `?source=url1,url2`; then drop `.github/workflows/benchmark.yml`
- [ ] Build something like hardwood.dev but for vortex files
- [ ] Publish to Maven Central (OSSRH/SONATYPE setup, GPG signing, coordinates, CI release pipeline)

## Performance

- [ ] Publish reproducible perf artifacts
    - Capture JMH JSON + JFR profile alongside README table; cite hardware (CPU model), JDK build (`java -version`),
      and benchmark commit SHA so numbers don't rot silently.
- [ ] performance tests must be peer reviewed
- [ ] run performance tests on other machines (I have access only to Apple M5)
- [ ] minimize `ctx.arena().allocate(...)` calls — prefer in-place decode when child buffer is writable (already done in ALP); audit all decoders for unnecessary off-heap allocs
- [ ] **Evaluate Vector API (JEP 469+) for hot decode loops** — candidates: FastLanes bitpacked unpack,
  FrameOfReference add-base, ZigZag decode, ALP F64 reconstruction, future pco offset+base loop. Measure
  vs scalar baseline with JMH; only adopt where speedup is material and code stays readable. Pin against
  a specific JDK build since Vector API is incubating until Valhalla lands.

## Testing

- [ ] **Security review + adversarial tests** — the reader parses untrusted binary input (file
  trailer, FlatBuffers, Protobuf, per-segment data). Attack surface:
    - Malformed trailer: wrong magic, negative lengths, offsets past EOF
    - FlatBuffer bombs: deeply nested layout trees, circular references, huge vectors
    - Proto bombs: enormous `values_len`/`indices_len` in metadata triggering OOM allocations
    - Integer overflows in offset arithmetic (`offset + length` wraps to negative)
    - Out-of-bounds buffer reads via crafted `bufferIndices` arrays
    - Zip-bomb style: tiny file that claims huge row counts
  Add a fuzz corpus of malformed `.vortex` files; all must throw `VortexException`, never
  `ArrayIndexOutOfBoundsException`, `NegativeArraySizeException`, or `OutOfMemoryError`.
- [ ] **Verify proto compatibility with upstream** — `dtype.proto` and `scalar.proto` exist in
  `spiraldb/vortex/vortex-proto/proto/` and should be kept in sync with our copies. Encoding
  metadata (e.g. `RLEMetadata`, `RunEndMetadata`) has no upstream `.proto`; tag mismatches
  silently produce zero/default values in proto3. Add value-level assertions (not just
  `rowCount > 0`) to integration tests to catch silent corruption.
- [ ] **Common test code — kill duplication in unit tests**

  Detailed plan for Sonnet:

  **Scope**: `core/src/test/java/io/github/dfa1/vortex/encoding/**` and `core/src/test/java/io/github/dfa1/vortex/core/array/**`.
  Do NOT touch integration/, reader/, writer/, csv/, cli/ in this pass — keep diff focused. Do NOT touch worktrees.

  **Goal**: extract shared test fixtures into one or two helper classes under
  `core/src/test/java/io/github/dfa1/vortex/encoding/` (same package — keeps helpers package-private).
  No new module. No `src/testFixtures`. Plain test sources.

  **What to extract** (each one is its own commit so review/revert is easy):

  1. **`DTypes` constants holder** — package-private final class in
     `io.github.dfa1.vortex.encoding`. Public static finals:
     ```
     I8, I16, I32, I64, U8, U16, U32, U64, F32, F64       // non-nullable primitive
     I32_N, I64_N, F64_N                                   // nullable variants used by tests
     BOOL, BOOL_N, UTF8, UTF8_N, BINARY, BINARY_N, NULL
     LIST_I32 = new DType.List(I32, false)                 // only the list dtype reused across ListEncodingTest / ListViewEncodingTest
     ```
     Skip anything used by only one test. Naming: short — `I64`, not `I64_DTYPE`.
     Replace local `private static final DType X = ...` declarations with `import static ... DTypes.X` (or
     plain qualified ref). Keep test files self-explanatory; don't import a wildcard.

  2. **Reuse `PTypeIO.LE_LONG / LE_INT / LE_SHORT / LE_FLOAT / LE_DOUBLE`** — already public.
     Replace every local `ValueLayout.OfX LE_X = ValueLayout.JAVA_X_UNALIGNED.withOrder(LITTLE_ENDIAN)` and
     inline `withOrder(LITTLE_ENDIAN)` in test files with `PTypeIO.LE_X`. No new code needed; this is pure
     deletion + reference swap. Watch out: a couple of tests use `ByteBuffer.wrap(...).order(LITTLE_ENDIAN)` —
     leave those alone (different API surface).

  3. **`TestSegments` helpers** — package-private final class with static factories:
     ```
     static MemorySegment leLongs(long... values)
     static MemorySegment leInts(int... values)
     static MemorySegment leShorts(short... values)
     static MemorySegment leDoubles(double... values)
     static MemorySegment leFloats(float... values)
     static MemorySegment leBytes(byte... values)            // trivial — included only if used >2 places
     ```
     All allocate via `Arena.ofAuto()` (test lifetime; matches `EncodeTestHelper`). Replaces patterns like:
     ```java
     MemorySegment buf = Arena.ofAuto().allocate(values.length * 8L);
     for (int i = 0; i < values.length; i++) buf.setAtIndex(LE_LONG, i, values[i]);
     ```
     Apply to tests that currently inline this loop: `RunEndEncodingTest`, `FrameOfReferenceEncodingTest`,
     `DeltaEncodingTest`, `DecimalEncodingTest`, `DecimalBytePartsEncodingTest`, `PrimitiveEncodingTest`,
     `RleEncodingTest`, `LongArrayTest`, plus any `buildCtx`/`makeCtx` internals.

  4. **`TestRegistry` factory** — collapses
     ```
     EncodingRegistry r = EncodingRegistry.empty();
     r.register(sut);
     r.register(new PrimitiveEncoding());
     ```
     into:
     ```java
     static EncodingRegistry of(Encoding... encodings) { ... }            // empty + register each
     static EncodingRegistry withPrimitive(Encoding sut) { ... }          // common 2-encoding combo
     ```
     Most affected: `RunEndEncodingTest`, `DeltaEncodingTest`, `ByteBoolEncodingTest`,
     `DecimalBytePartsEncodingTest`, `ListViewEncodingTest`, `DictEncodingTest`, etc.
     Do NOT change `EncodeTestHelper` — it already takes a registry parameter and is correct.

  5. **`TestDecodeContexts` builder** — replaces the ad-hoc `buildCtx`/`makeCtx` local helpers in
     `ByteBoolEncodingTest`, `RunEndEncodingTest`, `ListViewEncodingTest`, `ZstdEncodingTest` with a
     thin builder:
     ```java
     TestDecodeContexts.of(node, dtype)
         .rowCount(n)
         .registry(reg)
         .segments(seg0, seg1)
         .build();   // defaults: registry = empty, arena = Arena.global()
     ```
     Keep it small — only ship methods that have ≥2 callers after migration. Resist adding "might be
     useful" knobs. If only one test ends up using a knob, leave that test with its own helper.

  **Order of work** (each step is one commit, `./mvnw test -pl core` must stay green between commits):
  1. Add `DTypes`, migrate all encoding tests. ~25 files touched, mostly mechanical.
  2. Delete local `LE_X` constants, point at `PTypeIO.LE_X`. ~10 files.
  3. Add `TestSegments`, migrate inline `setAtIndex` loops. ~8 files.
  4. Add `TestRegistry`, migrate `EncodingRegistry.empty() + register` sequences. ~15 files.
  5. Add `TestDecodeContexts`, migrate `buildCtx`/`makeCtx`. ~4 files.

  **Rules**:
  - Helpers live in `io.github.dfa1.vortex.encoding` (test sources). Package-private classes,
    public static methods/fields. No `public` classes — keeps surface area minimal.
  - One commit per step. Each commit message: `test: extract <thing>` body lists files migrated.
  - Don't add JavaDoc paragraphs to trivial helpers; a one-line `///` comment max.
  - Don't migrate a test if the local constant is used exactly once and reads clearer inline
    (judgement call — err on side of dedup, but bail on weird edge cases).
  - Don't introduce `@BeforeEach` to set up these helpers — they're static, callers stay explicit.
  - If a migration makes a `@Nested` class private helper unused, delete it in the same commit.
  - Verify with `./mvnw test -pl core` after each step. Don't batch.

  **Out of scope** (do NOT do in this task):
  - Moving helpers to a shared `testFixtures` Maven config.
  - Touching integration tests (they have their own `RandomArrays` already).
  - Refactoring `EncodeTestHelper` itself.
  - Renaming `sut` or changing BDD structure.
  - Adding new test coverage. This is pure refactor.

## Build

- [ ] add other JDK version in the build matrix (at least JDK 26 and maybe 22, 23, 24): build is fast
- [ ] **Merge `core`/`reader`/`writer` into a single library jar** — the three modules are tightly
  coupled (every `Encoding` class has both encode + decode; format constants are shared). A single
  `vortex-java` artifact simplifies client dependency management and removes artificial module
  boundaries. Keep `integration`, `performance`, and `cli` as separate modules. Package structure
  (`encoding`, `io`, `writer`) already enforces internal boundaries without Maven.
- [ ] prefix all modules with "vortex-"
- [ ] add BOM module
- [ ] deploy to maven central
- [ ] switch back to module-path, but keep in mind these 2 blockers

## Documentation

- [ ] Use Diátaxis (https://diataxis.fr/) to structure docs: tutorials, how-to guides, reference, explanation
- [ ] Format specification: byte-exact diagrams for file layout and each encoding, with annotated examples (Arrow spec style)
- [ ] how to use the library and the cli

## Tooling

- [ ] hardwood to convert parquet files to vortex
- [ ] Optional `vortex-arrow` bridge module for Arrow ecosystem interop
    - Primary API stays `ArrayLong`/`ArrayDouble` (zero-copy, no deps, no Unsafe)
    - Bridge wraps typed views into Arrow `BigIntVector`, `Float8Vector`, etc. for users who need
      Arrow Flight / DuckDB ADBC / pandas interop
    - Conversion involves a copy (MemorySegment → Arrow off-heap buffer) — cost is explicit and opt-in
    - Arrow JVM uses `sun.misc.Unsafe` / Netty internally; keeping it in a separate module means
      the core library stays Unsafe-free

## Large-file support

- [ ] **Test read/write of files > 2 GB**
    - [ ] Parquet baseline for comparison: same data should fail or require splitting when any
      column chunk exceeds 2 GB.

## API

- [ ] Use domain primitives (`UInt32`, `UInt64`, etc.) as value classes via Project Valhalla instead of raw `long`/`int`
    - See https://dfa1.github.io/articles/rethink-domain-primitives-with-valhalla
    - Candidates: `PType` integer kinds, buffer offsets, row indices, byte lengths
    - Goal: type-safety at zero cost (value class = no heap alloc, no boxing)


## Encodings

### All Encodings

| Encoding ID                  | Class                      | Decode   | Encode   | Effort    | Dtypes / Notes |
|------------------------------|----------------------------|----------|----------|-----------|----------------|
| `vortex.primitive`           | `PrimitiveEncoding`        | ✅       | ✅       | —         | all `PType` (I8–I64, U8–U64, F32, F64) |
| `vortex.bool`                | `BoolEncoding`             | ✅       | ✅       | —         | Bool (bit-packed) |
| `vortex.null`                | `NullEncoding`             | ✅       | ✅       | —         | Null |
| `vortex.bytebool`            | `ByteBoolEncoding`         | ✅       | ✅       | —         | Bool |
| `vortex.zigzag`              | `ZigZagEncoding`           | ✅       | ✅       | —         | signed integer PTypes |
| `vortex.constant`            | `ConstantEncoding`         | ✅       | ✅       | —         | Primitive, Utf8, Binary, Bool, Null, Decimal, Extension |
| `vortex.ext`                 | `ExtEncoding`              | ✅       | ✅       | —         | Extension |
| `vortex.runend`              | `RunEndEncoding`           | ✅       | ✅       | —         | Primitive, Utf8/Binary, Bool |
| `vortex.varbin`              | `VarBinEncoding`           | ✅       | ✅       | —         | Utf8, Binary |
| `vortex.alp`                 | `AlpEncoding`              | ✅       | ✅       | —         | F64, F32 |
| `vortex.dict`                | `DictEncoding`             | ✅       | ✅       | —         | Primitive (VarBin via dict.vortex blocked by VarBinView) |
| `fastlanes.delta`            | `DeltaEncoding`            | ✅       | ✅       | —         | integer PTypes |
| `fastlanes.bitpacked`        | `BitpackedEncoding`        | ✅       | ✅       | —         | unsigned integer PTypes |
| `fastlanes.for`              | `FrameOfReferenceEncoding` | ✅       | ✅       | —         | integer PTypes |
| `vortex.sparse`              | `SparseEncoding`           | ✅       | ✅       | —         | Primitive |
| `vortex.sequence`            | `SequenceEncoding`         | ✅       | ✅       | —         | Primitive |
| `vortex.struct`              | `StructEncoding`           | ✅       | ✅       | —         | Struct |
| `vortex.fsst`                | `FsstEncoding`             | ✅       | ✅       | —         | Utf8, Binary (bigram symbol table) |
| `vortex.varbinview`          | `VarBinViewEncoding`       | ✅       | ✅       | —         | Utf8, Binary |
| `vortex.pco`                 | `PcoEncoding`              | ✅       | ❌       | very hard | Classic, IntMult, FloatMult, FloatQuant, Dict; None+Consecutive+Lookback+Conv1 delta; nullable |
| `vortex.chunked`             | `ChunkedEncoding`          | ✅       | ✅       | medium    | decode: primitive + struct concat; encode via ChunkedData |
| `fastlanes.rle`              | `RleEncoding`              | ✅       | ✅       | —         | chunk-based RLE; offset always < 1024 |
| `vortex.alprd`               | `AlpRdEncoding`            | ✅       | ✅       | —         | F64, F32; left ≤16 bits dict-coded (≤8 entries), right bitpacked; exceptions as patches |
| `vortex.decimal`             | `DecimalEncoding`          | ✅       | ✅       | —         | Decimal |
| `vortex.decimal_byte_parts`  | `DecimalBytePartsEncoding` | ✅       | ✅       | —         | Decimal byte parts |
| `vortex.datetimeparts`       | `DateTimePartsEncoding`    | ✅       | ✅       | —         | Timestamp parts |
| `vortex.list`                | `ListEncoding`             | ✅       | ✅       | —         | two children: elements + offsets (I64); `ListArray`; cascadable offsets via `decodeChildAs` |
| `vortex.listview`            | `ListViewEncoding`         | ✅       | ✅       | —         | three children: elements + offsets (len N) + sizes (len N); fixture uses U16 for both |
| `vortex.fixed_size_list`     | `FixedSizeListEncoding`    | ✅       | ✅       | —         | one child: flat elements; no offsets |
| `vortex.zstd`                | `ZstdEncoding`             | ✅       | ✅       | —         | Primitive, Utf8, Binary (no dict, no nullable); uses airlift/aircompressor |
| `vortex.masked`              | `MaskedEncoding`           | ✅       | ❌       | —         | child[0]=payload (non-nullable), child[1]=validity Bool (optional); no S3 fixture in v0.72.0 |
| `vortex.patched`             | —                          | ❌       | ❌       | unknown   | ID registered; no decoder yet; no S3 fixture in v0.72.0 |
| `vortex.variant`             | —                          | ❌       | ❌       | unknown   | ID registered; no decoder yet; no S3 fixture in v0.72.0 |

### `vortex.zstd` known limitations


- [ ] **Multi-frame encode** — `ZstdEncoding.Encoder` always produces a single frame for the whole array.
  Fix: accept a `valuesPerFrame` parameter (default: all values in one frame). Split the raw byte buffer at frame boundaries (`valuesPerFrame * byteWidth`), compress each slice independently, emit one `ZstdFrameMetadata` per frame. Enables partial decompression during slice scans.

- [ ] **Nullable arrays (encode)** — `ZstdEncoding.Encoder` has no null handling.
  Fix: accept nullable input (e.g. `Integer[]` or a validity mask alongside the data array). Strip null positions before compression. Encode the validity bitmap as a Bool child (child[0]) in the `EncodeNode`. Mirrors what Rust does: only valid values go into the compressed payload.


### `vortex.pco` encode plan

Pure-Java encode. Only after decode is stable + a Java consumer asks for write. Not gated
on any S3 fixture (all fixtures are Rust-produced; decode unblocks them).

**Refs**: [pco/src/wrapped/chunk_compressor.rs](https://github.com/pcodec/pcodec/blob/main/pco/src/wrapped/chunk_compressor.rs),
[pco/src/bin_optimization.rs](https://github.com/pcodec/pcodec/blob/main/pco/src/bin_optimization.rs),
[pco/src/histograms.rs](https://github.com/pcodec/pcodec/blob/main/pco/src/histograms.rs),
[pco/src/ans/](https://github.com/pcodec/pcodec/tree/main/pco/src/ans),
[pco/src/sampling.rs](https://github.com/pcodec/pcodec/blob/main/pco/src/sampling.rs).

**Why harder than decode**:
- Encode chooses mode + bin layout + tANS weights; decode just executes a fixed program.
- Bin optimization is dynamic programming over partitions of a histogram (`bin_cost`).
- tANS encoding table differs from decode table (weight quantization → symbol table).
- Mode selection samples input, trial-compresses against candidates (Classic, FloatMult,
  IntMult, FloatQuant), picks best ratio. See `sampling.rs`.
- No oracle: encode is non-deterministic. Validation = round-trip Java→Java decode AND
  Java→Rust decode (existing `JavaWritesRustReadsIntegrationTest` harness).

**Reuse from decode**:
- `LeBitReader` (decode) ↔ `LeBitWriter` (encode, new). Same bit layout, opposite direction.
- tANS table structure (decode-built) ↔ tANS encode table (`ans/encoding.rs`).
- Mode constants, delta constants, proto types — shared.
- Bit-exact wire format already validated by decode tests; encode just emits same bytes.

**Phases**:
- [ ] **Phase E0 — gate**. Is there a consumer (CLI write path, vortex-arrow bridge)? If no,
  stop. Decode is enough.
- [ ] **Phase E1 — bit writer**. `LeBitWriter` over `Arena`-backed `MemorySegment`. Mirrors
  `pco/src/bit_writer.rs`. Property test: random bit sequences round-trip via `LeBitReader`.
- [ ] **Phase E2 — Classic mode, no delta, fixed bins, no optimization**. Hardcoded bin layout
  + uniform tANS weights. Emits a valid (suboptimal) pco stream. Validates: header write,
  chunk meta write, page write, byte alignment. Round-trip via Java decode.
- [ ] **Phase E3 — histogram + bin optimization**. Port `histograms.rs` (sort + bucket by
  latent prefix) and `bin_optimization.rs` (DP partitioning, `bin_cost`, `log2_approx`).
  Replace fixed bins with optimized layout. Compression ratio benchmark vs Rust on same
  input — accept if Java within 5% of Rust ratio.
- [ ] **Phase E4 — tANS weight quantization + encoding table**. Port `ans/spec.rs` weight
  quantizer and `ans/encoding.rs` symbol-table builder. Critical: ANS state values must
  match what Rust decoder expects.
- [ ] **Phase E5 — delta Consecutive encoder**. Compute consecutive differences; store
  initial state at page head. Mirrors Phase 4 of decode.
- [ ] **Phase E6 — mode selection**. Port `sampling.rs`: take stratified sample, trial-encode
  with each candidate mode, pick lowest bit count. Add FloatMult, IntMult (likely), FloatQuant.
  Skip Dict + Lookback + Conv1 unless requested.
- [ ] **Phase E7 — multi-chunk, multi-page, nullable**. Match decode: split into chunks of
  `DEFAULT_MAX_PAGE_N`, pages per `ChunkConfig.paging_spec`. For nullable input, strip
  nulls before encode, emit validity as child[0].
- [ ] **Phase E8 — integration tests**. `JavaWritesRustReadsIntegrationTest`: produce a
  `.vortex` with pco-encoded column, validate the Rust reference reader decodes it
  byte-identical to input. Property test with `tries` low.
- [ ] **Phase E9 — `EncodeResult` glue**. `PcoEncoding.Encoder.encode(dtype, data)` returns
  `EncodeNode(VORTEX_PCO, metadata, no children OR validity child, bufferIndices)` with
  `chunk_metas` then `pages` as separate buffer indices.

**Risks**:
- Bin optimization DP: bug → catastrophic ratio loss but still valid output. Symptom is
  silent — only benchmarks catch it. Test ratio against Rust on known inputs.
- tANS weight quantization: bug → Rust decoder rejects with checksum mismatch. Caught fast
  by Java→Rust integration test.
- Mode selection: wrong mode = valid output but poor ratio. Same silent failure as bin DP.
- `log2_approx` is a fast-math hack. Java port can use `Math.log` (slower but exact);
  measure JMH cost before chasing parity.
- Encode unit test oracle problem: easier to assert bit-exact against a recorded Rust output
  for fixed inputs than to assert "optimal encoding" — record golden encodings per ptype.

**Estimate**: ~20 working days full encode. ~8 days for Classic+Consecutive+I64-only "valid
but suboptimal" encoder (Phase E1+E2+E5+E8 partial). Decode is the prerequisite —
don't start before decode lands.

**Decision**: keep `Encoder` stub until a real write consumer materializes. Reassess
post-decode + post-`vortex-arrow` bridge.

### S3 Fixture Status (`v0.72.0/arrays/`)

| Fixture                          | Status | Blocker                       |
|----------------------------------|--------|-------------------------------|
| `primitives.vortex`              | ✅     |                               |
| `alp.vortex`                     | ✅     |                               |
| `bitpacked.vortex`               | ✅     |                               |
| `booleans.vortex`                | ✅     |                               |
| `constant.vortex`                | ✅     |                               |
| `for.vortex`                     | ✅     |                               |
| `fsst.vortex`                    | ✅     |                               |
| `runend.vortex`                  | ✅     |                               |
| `sequence.vortex`                | ✅     |                               |
| `varbin.vortex`                  | ✅     |                               |
| `struct_nested.vortex`           | ✅     |                               |
| `null.vortex`                    | ✅     |                               |
| `bytebool.vortex`                | ✅     |                               |
| `zigzag.vortex`                  | ✅     |                               |
| `datetime.vortex`                | ✅     |                               |
| `dict.vortex`                    | ✅     |                               |
| `sparse.vortex`                  | ✅     |                               |
| `varbinview.vortex`              | ✅     |                               |
| `chunked.vortex`                 | ✅     |                               |
| `rle.vortex`                     | ✅     |                               |
| `alprd.vortex`                   | ✅     |                               |
| `decimal.vortex`                 | ✅     |                               |
| `decimal_byte_parts.vortex`      | ✅     |                               |
| `datetimeparts.vortex`           | ✅     |                               |
| `list.vortex`                    | ✅     |                               |
| `listview.vortex`                | ✅     |                               |
| `fixed_size_list.vortex`         | ✅     |                               |
| `zstd.vortex`                    | ✅     |                               |
| `tpch_lineitem.compact.vortex`   | ✅     |                               |
| `tpch_lineitem.regular.vortex`   | ✅     |                               |
| `tpch_orders.compact.vortex`     | ✅     |                               |
| `tpch_orders.regular.vortex`     | ✅     |                               |
| `pco.vortex`                     | ✅     |                               |
| `clickbench_hits_5k.compact.vortex` | ✅  |                               |
| `clickbench_hits_5k.regular.vortex` | ✅  |                               |

| `masked.vortex`                     | ❓     | no fixture in v0.72.0; `vortex.masked` ID registered |
| `patched.vortex`                    | ❓     | no fixture in v0.72.0; `vortex.patched` ID registered |
| `variant.vortex`                    | ❓     | no fixture in v0.72.0; `vortex.variant` ID registered |

**Score: 35/35**


