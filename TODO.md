# TODO

## Project

- [ ] Move project to a dedicated organization
- [ ] Create website
   - build something like hardwood.dev but for vortex files
- [ ] Publish benchmarks — run `./bench` locally, push JMH JSON to gh-pages via `bench-publish` script, view at
  `https://jmh.morethan.io/?source=https://dfa1.github.io/vortex-java/benchmark-result.json`; dated files for history
  comparison via `?source=url1,url2`; then drop `.github/workflows/benchmark.yml`

## Performance

- [ ] Publish reproducible perf artifacts
    - Capture JMH JSON + JFR profile alongside README table; cite hardware (CPU model), JDK build (`java -version`),
      and benchmark commit SHA so numbers don't rot silently.
- [ ] Performance tests must be peer reviewed
- [ ] Run performance tests on other machines (I have access only to Apple M5)
- [ ] Minimize `ctx.arena().allocate(...)` calls — prefer in-place decode when child buffer is writable (already done in
  ALP); audit all decoders for unnecessary off-heap allocs
- [ ] **Evaluate Vector API (JEP 469+) for hot decode loops** — candidates: FastLanes bitpacked unpack,
  FrameOfReference add-base, ZigZag decode, ALP F64 reconstruction, future pco offset+base loop. Measure
  vs scalar baseline with JMH; only adopt where speedup is material and code stays readable. Pin against
  a specific JDK build since Vector API is incubating until Valhalla lands.
- [ ] Support for preview Vector API
   - when JVM flag is activated, put in the Encode/Decode context the type SCALAR / VECTOR
   - if flag is active, any encoder/decoder will switch to vectorized

## Security

**Contract:** the reader memory-maps and parses untrusted binary input. Every malformed input must
throw `VortexException`, never `ArrayIndexOutOfBoundsException`, `NegativeArraySizeException`,
`OutOfMemoryError`, `StackOverflowError`, a raw FlatBuffer runtime exception, or a Protobuf
parser exception. Each entry below is either a known gap, a contract audit, or supporting infra.

### Parser hardening

- [ ] **Audit every `MemorySegment.asSlice` call site for bounds wrapping** —
  `grep -rn 'asSlice' core/src/main reader/src/main`. Each call on untrusted offsets/lengths
  must throw `VortexException` rather than the JDK's `IndexOutOfBoundsException`. Either wrap
  per call site, or route through an `IoBounds.slice(seg, off, len)` helper and add a
  Checkstyle rule rejecting raw `asSlice` in `io`/`scan`/`encoding` packages.
- [ ] **HTTP-reader malformed-tail cases** — `VortexHttpReader.fetchBlob` does not validate
  that the HTTP response length matches the requested `Range`. Server-returned short / extra
  bytes should fail loudly. Add `MalformedHttpResponseTest` (mock `HttpClient`).

### Per-encoding adversarial tests

Each encoding's `decode(DecodeContext)` should be exercised against:
- `bufferIndices[i] >= ctx.bufferCount()` → centralize check in `DecodeContext.buffer(i)`.
- Crafted metadata that decodes but disagrees with the buffer payload.

Per-encoding gotchas:
- [ ] **VarBin**: offsets non-monotonic, negative, past data-buffer length.
- [ ] **Dict**: `codes[i] >= values.length`; `codes` ptype declared u8 but values count > 256.
- [ ] **Bitpacked**: `bit_width < 0 || > 64`; `packed_len < n * bit_width / 8`.
- [ ] **ALP**: `dim < 0`, `f_or_d` byte out of enum range; `exceptions_count > n`.
- [ ] **Sparse**: indices non-sorted or `indices[i] >= length`; values count
  mismatches indices count.
- [ ] **Chunked**: zero children with non-zero `row_count`; child layout self-referencing
  (already protected by depth limit, but add explicit test).
- [ ] **Struct**: `fieldNames.size() != children.size()`; field name UTF-8 invalid.
- [ ] **RLE / RunEnd**: `run_ends` non-monotonic; last `run_end` ≠ `row_count`.
- [ ] **Constant**: protobuf scalar value missing or type-mismatched against declared `DType`.
- [ ] **Zoned**: zone-map min > max; zone count ≠ child chunk count.
- [ ] **Pco**: `bits_per_offset > 64`; `bin_count == 0` with non-empty page; per-page
  `n` greater than `DEFAULT_MAX_PAGE_N`; ANS state values inconsistent with weight table.

### Resource caps

Currently no limits; mmap pressure + decoder allocations are bounded only by file content.

- [ ] Max file size (configurable; reject before `FileChannel.map`).
- [ ] Max segment count.
- [ ] Max children count per layout node.
- [ ] Max row count per layout node (defence in depth on top of the zip-bomb fix).
- [ ] Pco: max pages per chunk, max bins per page.

Expose via a `ReadOptions.limits(...)` builder with sane defaults; integration tests can
relax for large fixtures.

### Fuzz infrastructure

- [ ] **Jazzer + JUnit 5** — add `com.code-intelligence:jazzer-junit` test dep. Two modes:
  regression (`./mvnw test`, replays saved corpus + crashes) and fuzz
  (`JAZZER_FUZZ=1`, nightly profile). See research notes in branch
  `worktree-security-fuzz` commit history.
- [ ] **Seed corpus from integration fixtures** — drop existing `.vortex` test files into
  `reader/src/test/resources/fuzz-corpus/full-file/`. Per-encoding sub-corpora extracted via
  a small tool that walks fixtures and dumps each segment to
  `core/src/test/resources/fuzz-corpus/<encoding>/`.
- [ ] **Fuzz targets**: `VortexReader.open(byte[])`, `PostscriptParser.parseBlobs`, and one
  `@FuzzTest` per encoding `Encoding.decode`. Crash oracle: `ignore = {VortexException.class}`.
- [ ] **Differential fuzz (Java vs Rust)** — round-trip random bytes through Java decode
  and `vortex-jni`; assert both throw or both return identical row count + values. Reuse
  `RustWritesJavaReadsIntegrationTest` harness.
- [ ] **OSS-Fuzz submission** — Jazzer is a first-class OSS-Fuzz engine; submit the project
  once the corpus + targets stabilise. Free continuous fuzzing.

### Process

- [ ] **Error messages — structural sanitization of `VortexException`** —
  ~233 `throw new VortexException(...)` sites today; ~10–15 interpolate attacker-controlled
  strings from the parsed file (layout `encodingId` strings from `layoutSpecs`, struct field
  names, extension type ids). A crafted file can put ANSI escapes, newlines, or 1 MB blobs
  into the exception message → terminal injection, log injection, allocation pressure.

  **Sanitization primitive (shared by all approaches):** add a package-private `Sanitize`
  helper. `Sanitize.safe(String)` length-caps at 128 chars, hex-escapes non-printable ASCII
  (`< 0x20 || > 0x7e`) as `\xHH`, appends `[…]` truncation marker. ASCII-only stays
  unchanged; attacker bytes get escaped. Unit-tested for ANSI escapes, newlines, oversize,
  null bytes, RTL override.

  **Two viable structural designs — pick one before starting:**

  *Option A — Enum catalog (`VortexError`):*
  Add a `VortexError` enum with ~100 constants, one per failure mode. Each carries a
  format template; `render(Object...)` sanitizes any `String` arg via `Sanitize.safe`.
  `VortexException(VortexError, Object...)` replaces the raw-`String` constructors.
  Pros: single-file catalog (~300 LOC); stable string error codes via `enum.name()` (great
  for structured logs, metrics, alerts); easy i18n path (extract templates to `.properties`);
  smallest per-error friction (1 line per new error); native `switch (e.error())` dispatch.
  Cons: arg count is runtime-checked; no typed deconstruction in `catch`.

  *Option B — Sealed `VortexException` hierarchy:*
  Make `VortexException` `sealed` over ~4 category abstracts (`Malformed`, `Unsupported`,
  `Resource`, `Internal`), each `sealed` over ~25 leaf record-like classes. Each leaf carries
  typed final fields; compact constructor sanitizes attacker-controlled fields. Catches stay
  `catch (VortexException e)`; pattern-matching gives `case FileTooSmall(long size) -> …`.
  Pros: compile-time arg-count + arg-type checking; typed deconstruction in catch; category
  sub-sealing keeps leaf additions non-breaking for callers who switch on the category.
  Cons: ~600 LOC across many tiny classes; new error contribution is a new file; monitoring
  labels via `getClass().getSimpleName()` are less stable than enum names.

  **Side-by-side comparison:**

  | Property                              | Enum catalog (A)         | Sealed hierarchy (B)        |
  | ------------------------------------- | ------------------------ | --------------------------- |
  | Compile-time arg-count check          | ✗ (runtime)              | ✓                           |
  | Compile-time arg-type check           | ✗ (runtime)              | ✓                           |
  | Pattern destructuring in `catch`      | ✗                        | ✓ (`FileTooSmall(var s)`)   |
  | Stable string error code              | `enum.name()` native     | `simpleName` accessor       |
  | i18n migration path                   | swap template            | per-record overhaul         |
  | External monitoring / metric labels   | stable enum names        | class names (refactor risk) |
  | Catalog file size                     | ~300 LOC                 | ~600 LOC                    |
  | Friction adding a new error          | 1 line                   | 5 LOC + new file            |
  | Generic `catch (VortexException)`     | ✓                        | ✓                           |
  | SemVer behaviour of exhaustive switch | same (mitigate via cat.) | same (mitigate via cat.)    |

  **SemVer note (both):** Adding new variants is source-incompatible for callers who do
  exhaustive switches without `default`. Document the policy — leaf additions are
  non-breaking, category additions only in MAJOR.

  **Phasing (both):**
  - Phase A — add `Sanitize`, the chosen catalog (enum or sealed leaves for the
    ~30 errors already used by recent security commits), new `VortexException`
    constructors, deprecate the `String` constructors. ~1.5 h.
  - Phase B — migrate the ~10 attacker-controlled sites + add
    `MessageSanitizationSecurityTest` end-to-end. ~1 h.
  - Phase C — mass-migrate remaining ~220 safe sites. ~3–4 h, largely mechanical.
  - Phase D (0.5.0) — remove deprecated `String` constructors; add Checkstyle rule
    forbidding raw `throw new VortexException(.* + .*)` to prevent regression.

  **Defense-in-depth (later, independent):** Checker Framework `@Untrusted` annotation on
  every `String` accessor that returns parsed bytes — IDE-time warnings for any
  interpolation into a trusted message. Incremental adoption, no runtime cost.

## Build

- [ ] switch back to module-path, but keep in mind these 2 blockers:
-    [ ] 'dfa1' in package name is rejected by maven central
-    [ ] automatic module names for flatbuffers is rejected by maven central

## Documentation

- [ ] Format specification: byte-exact diagrams for file layout and each encoding, with annotated examples (Arrow spec
  style)
- [ ] Review documentation for new joiners

## Tooling

- [ ] Optional `vortex-arrow` bridge module for Arrow ecosystem interop
    - Primary API stays `ArrayLong`/`ArrayDouble` (zero-copy, no deps, no Unsafe)
    - Bridge wraps typed views into Arrow `BigIntVector`, `Float8Vector`, etc. for users who need
      Arrow Flight / DuckDB ADBC / pandas interop
    - Conversion involves a copy (MemorySegment → Arrow off-heap buffer) — cost is explicit and opt-in
    - Arrow JVM uses `sun.misc.Unsafe` / Netty internally; keeping it in a separate module means
      the core library stays Unsafe-free

## API

- [ ] **Java-writes / Rust-reads nullable extension columns** — Java-side
  round-trip (write + read) is covered by
  `JdbcImporterTest#roundTripsNullableExtensionColumns`. JNI cross-compat is
  unverified: `JavaWritesRustReadsIntegrationTest` has no extension tests at
  all today, so the new `ExtEncoding → MaskedEncoding → primitive` layout has
  not been validated against the Rust reader. Add per-extension Java→Rust
  tests once an extension test harness exists (Arrow DateDayVector /
  TimeMilliVector / TimestampMilliVector / FixedSizeBinaryVector mapping).
- [ ] Use domain primitives (`UInt32`, `UInt64`, etc.) as value classes via Project Valhalla instead of raw `long`/`int`
    - See https://dfa1.github.io/articles/rethink-domain-primitives-with-valhalla
    - Candidates: `PType` integer kinds, buffer offsets, row indices, byte lengths
    - Goal: type-safety at zero cost (value class = no heap alloc, no boxing)
- [ ] **Align `ArrayStats` with Rust lazy model** — Rust's `Array` trait exposes `statistics()`
  returning `Option<ArrayStatistics>`; stats are lazy and optional, read from the FlatBuffer node
  on demand. Java's `ArrayStats` was removed from decoded array objects (done) but `KnownArrayNode`
  still stores them eagerly at parse time. Evaluate: expose stats on-demand via a `stats(ArrayNode)`
  helper on `EncodingRegistry` or `DecodeContext`; surface them on `Array` only for types where
  zone-map callers actually need it (today: only `ZonedEncoding`).
- [ ] **Audit runtime pluggability vs Rust impl** — maintainer (2026-06-04) flagged that Rust supports
  runtime registration for: Encodings, DTypes, Compute, Layouts. Java status:
    - Encodings: ✅ `ServiceLoader` + `EncodingRegistry.Builder.register()`; ✅ `allowUnknown()` passthrough for
      unregistered encodings (mirrors `VortexSession::allow_unknown()`). Runtime registration is build-time on a
      fresh builder — the registry itself is immutable after `build()`.
    - DTypes: ❌ sealed hierarchy — no user-extensible type. If a downstream consumer needs a custom
      logical type (e.g. UUID, IP address) they can't register one. Decide: keep sealed (simpler) or
      open via SPI mirroring `EncodingRegistry`.
    - Layouts: ❌ fixed set (Flat/Chunked/Zoned/Struct). Same trade-off as DTypes.
    - Compute: ❌ no compute layer yet. Out of scope until reader feature-complete.
      Action: short design note weighing sealed-vs-pluggable for DType + Layout; revisit when Java impl
      has a real downstream consumer asking for it. Don't pre-open these without a use case.

## Encodings

See [docs/compatibility.md](docs/compatibility.md) for the full encoding support table and S3 fixture status.

### Remaining gap (no-Zstd mode) — biggest to smallest:
- [ ] **Nullable column handling** — `ParquetImporter` maps nulls to 0.0/0L (type defaults) for the 9 nullable F64
  columns in the taxi dataset (`fare_amount`, `extra`, `mta_tax`, `tip_amount`, `tolls_amount`,
  `improvement_surcharge`, `total_amount`, `congestion_surcharge`, `Airport_fee`). Rust uses `vortex.sparse`
  or `vortex.masked` to store only valid values, then ALP on clean data. Java passes zero-polluted arrays to ALP.
  Fix: add a `NullableData(double[] values, boolean[] validity)` wrapper; writer detects it, compacts valid values,
  encodes validity as a Bool child. Requires API change to `VortexWriter.writeChunk` and corresponding
  `ParquetImporter` changes.

- [ ] **Global dict for F64 low-cardinality** — excluded from `isDictCandidate` because ALP/RLE were expected to
  win; but for columns like `mta_tax` (8 unique F64 values) and `Airport_fee` (4 unique), dict codes are
  ~same size as ALP+bitpack while Rust uses dict. Measure actual gain before implementing. Utf8 global dict
  plumbing already in place (see `writeGlobalDictUtf8Column`); the F64 case is a relaxation of one branch in
  `isDictCandidate` plus its own value-array materializer.

- [ ] **Global dict for `Binary` dtype** — `DictEncoding.accepts` already covers `Utf8` and `Binary`-ish bytes,
  but the writer's candidate scan only handles `DType.Primitive` + `DType.Utf8`. Mirror the Utf8 path for
  binary columns once a real workload surfaces.

- [ ] **Multi-symbol size comparison test** — `FileSizeComparisonIntegrationTest` writes a single ticker
  symbol ("ACME" × N), so the global-dict-Utf8 path is not exercised in size measurement. Add a variant
  using the 5-symbol generator from `OhlcEncodingInspectionIntegrationTest#writeOhlcMultiSymbol` and assert
  the global-dict file is smaller than the per-chunk-dict baseline.

- [ ] **FSST symbol-table builder: port `fsst-rs` Algorithm 3** —
  `FsstEncoding.Encoder` is a single-pass, bigram-only top-K table. Rust's
  `fsst-rs` (used by `vortex-fsst`) implements **Algorithm 3 from the FSST
  paper**: 5 generations of iterative training, symbols up to 8 bytes long,
  Lossy Perfect Hash Table for O(1) symbol lookup during compression. On the
  high-cardinality random ASCII benchmark
  (`FileSizeComparisonIntegrationTest#highCardinalityUtf8_javaVsJni`) the gap
  is Java 1.75× raw vs Rust 1.18× raw — purely encoder quality, the wire
  format and decoder are unchanged. Estimate: ~1 week of work.
  Reference: <https://www.vldb.org/pvldb/vol13/p2649-boncz.pdf>,
  <https://github.com/spiraldb/fsst/blob/develop/src/builder.rs>.

### `vortex.zstd` known limitations

- [ ] **Multi-frame encode** — `ZstdEncoding.Encoder` always produces a single frame for the whole array.
  Fix: accept a `valuesPerFrame` parameter (default: all values in one frame). Split the raw byte buffer at frame
  boundaries (`valuesPerFrame * byteWidth`), compress each slice independently, emit one `ZstdFrameMetadata` per frame.
  Enables partial decompression during slice scans.

- [ ] **Nullable arrays (encode)** — `ZstdEncoding.Encoder` has no null handling.
  Fix: accept nullable input (e.g. `Integer[]` or a validity mask alongside the data array). Strip null positions before
  compression. Encode the validity bitmap as a Bool child (child[0]) in the `EncodeNode`. Mirrors what Rust does: only
  valid values go into the compressed payload.

### `vortex.pco` encode plan

Pure-Java encode. Only after decode is stable + a Java consumer asks for write. Not gated
on any S3 fixture (all fixtures are Rust-produced; decode unblocks them).

**Refs
**: [pco/src/wrapped/chunk_compressor.rs](https://github.com/pcodec/pcodec/blob/main/pco/src/wrapped/chunk_compressor.rs),
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

**Decision**: E0 gate cleared — start E1 (LeBitWriter). Suboptimal path E1+E2+E9 (~5 days)
gives Rust-compatible wire format first; E3+E4 add ratio parity (~+5 days); E5+E6 add delta +
mode selection (~+5 days). Tackle E1 next.


