# Changelog

All notable changes to **vortex-java** are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- `DType.isUnsigned()` — `true` for the unsigned integer primitives (`U8`–`U64`), `false` otherwise. ([#159](https://github.com/dfa1/vortex-java/issues/159))

### Fixed

- Zone-map pruning now compares filter values in the *column's* type domain rather than by the boxed value's type. A predicate whose value is boxed at a different width (e.g. `Integer` on an `I64` column) — or any value on a `U64` column — previously pruned nothing and silently degraded to a full scan; it now prunes correctly (unsigned columns by unsigned order). As part of this, a filter value genuinely incomparable to its column (e.g. a `String` against a numeric column) now raises `VortexException` during the scan instead of silently disabling pruning — a behaviour change for callers that relied on the previous silent full scan. ([#159](https://github.com/dfa1/vortex-java/issues/159))

## [0.9.0] — 2026-06-24

Two import-only breaking changes — the `vortex-core` types moved under `io.github.dfa1.vortex.core.*`, and the no-arg `DType` factories became constants. In return, Vortex now ships with **no FlatBuffers or Protobuf runtime dependency**: the `.fbs`/`.proto` schemas compile in-house to `MemorySegment`-native Java, dropping `com.google.flatbuffers:flatbuffers-java` — the last automatic-module dependency — so a named JPMS `module-info` is viable, and the generated wire classes are prefixed so they no longer collide on your classpath (ADR 0017).

### Added

- Canonical non-nullable `DType` constants: `DType.I8` … `I64`, `U8` … `U64`, `F16`/`F32`/`F64`, plus `BOOL`, `UTF8`, `BINARY`, `NULL`, `VARIANT`; build a nullable column with `DType.I64.asNullable()`. ([f4b22e42](https://github.com/dfa1/vortex-java/commit/f4b22e42))

### Changed

- **Breaking (imports):** every `vortex-core` type moved under `io.github.dfa1.vortex.core.*` — `core.model` (`DType`, `PType`, `TimeUnit`, `EncodingId`, `ExtensionId`, `Time*Dtype`), `core.io` (`IoBounds`, `PTypeIO`, `VortexFormat`), `core.error` (`VortexException`), `core.compute` (`FastLanes`, `PrimitiveArrays`), `core.fbs`/`core.proto` (wire codecs). E.g. `io.github.dfa1.vortex.core.DType` → `io.github.dfa1.vortex.core.model.DType`. ([52f30c16](https://github.com/dfa1/vortex-java/commit/52f30c16))
- Dropped the `com.google.flatbuffers:flatbuffers-java` runtime dependency; the `.fbs`/`.proto` schemas compile in-house to `MemorySegment`-native Java, and the generated wire classes are prefixed `Fbs*`/`Proto*` so the generic names (`Array`, `Buffer`, `DType`, …) no longer collide on your classpath (ADR 0017). ([5907302e](https://github.com/dfa1/vortex-java/commit/5907302e))

### Removed

- **Breaking (imports):** the no-arg `DType` factories (`DType.i64()`, `DType.utf8()`, …) — use the constants above (`DType.i64()` → `DType.I64`). `DType.decimal(..)`/`DType.structBuilder()` and the record constructors are unchanged. ([f4b22e42](https://github.com/dfa1/vortex-java/commit/f4b22e42))

## [0.8.3] — 2026-06-23

A **Sonar-driven refactoring** release: no new file-format capability, but a focused pass using SonarCloud findings to drive cleanups — dead code removed, duplication factored out, and one hot-loop micro-optimisation. Each finding was triaged (lead, not verdict) so the changes preserve behaviour and the JIT vectorisation of the hot decode loops. The interpretation framework behind this is now documented in `docs/testing.md`.

### Performance

- `FastLanes.transposeIndex` / `iterateIndex`: replaced the per-element `%`/`/` + `ORDER[]` indirection with permutation tables built once in a static initialiser. Faster address generation keeps more outstanding scatter misses in flight; measured 1.4×–3.4× on the transpose/undelta kernels (Apple M5, L1→DRAM working sets). The per-element decode loops stay specialised per width to preserve C2 superword vectorisation. ([089b6e36](https://github.com/dfa1/vortex-java/commit/089b6e36), [e683a634](https://github.com/dfa1/vortex-java/commit/e683a634))

### Removed

- **Breaking (read SPI):** removed `EncodingDecoder.accepts(DType)`. It was a residual of the ADR-0001 read/write split — encode-selection semantics copied onto the decoder side, where the reader dispatches purely by `EncodingId` and never called it (dead since the split). `EncodingEncoder.accepts` is unchanged. Downstream custom `EncodingDecoder` implementations should delete their `accepts` override. ([7516a544](https://github.com/dfa1/vortex-java/commit/7516a544))

### Changed

- Internal dedup driven by Sonar duplication findings: extracted the shared FastLanes layout + `PType.bits` and `PrimitiveArrays.toLongs`/`fromLongs` into core, hoisted the `Materialized*` array boilerplate into a shared base, factored the four `BitpackedEncodingDecoder` unpack loops onto one precomputed per-row schedule, added `PType.isUnsigned` (dropping three private copies), and deduplicated the CLI inspect plumbing and `formatBytes`. ([ec6b9631](https://github.com/dfa1/vortex-java/commit/ec6b9631), [a74263c0](https://github.com/dfa1/vortex-java/commit/a74263c0), [7af0af2a](https://github.com/dfa1/vortex-java/commit/7af0af2a), [8362a353](https://github.com/dfa1/vortex-java/commit/8362a353), [87c77cc9](https://github.com/dfa1/vortex-java/commit/87c77cc9), [d8f84088](https://github.com/dfa1/vortex-java/commit/d8f84088), [b557e573](https://github.com/dfa1/vortex-java/commit/b557e573), [d52e8c0c](https://github.com/dfa1/vortex-java/commit/d52e8c0c))
- Dropped dead `PType` switch arms in the writer's `readPrimitiveElement`, `primitiveArrayLen`, and `buildTypedUniqueArray` — unreachable branches flagged as uncovered. ([4c6ab149](https://github.com/dfa1/vortex-java/commit/4c6ab149), [94d2fa49](https://github.com/dfa1/vortex-java/commit/94d2fa49), [f89072a6](https://github.com/dfa1/vortex-java/commit/f89072a6))

### Fixed

- Cleared two SonarCloud-reported bugs in the writer's SUM zone-map stat plumbing. ([33798ab9](https://github.com/dfa1/vortex-java/commit/33798ab9))
- Suppressed `java:S1172` on `AbstractMaterializedArray.materialize` with a reason — the `arena` parameter is contractual (implements `Array#materialize(SegmentAllocator)` for the leaf classes), not a removable unused parameter. ([9b226f73](https://github.com/dfa1/vortex-java/commit/9b226f73))

### Tests

- Filled coverage gaps surfaced by Sonar: the `Materialized*` `materialize` defaults, every `SchemaCommand.formatDType` arm, and the writer's global-dict cardinality fallback with U16 utf8 codes. ([8741dad3](https://github.com/dfa1/vortex-java/commit/8741dad3), [77fad504](https://github.com/dfa1/vortex-java/commit/77fad504), [c2918eaa](https://github.com/dfa1/vortex-java/commit/c2918eaa))

### Docs

- `docs/testing.md`: new section on reading Sonar/PIT as data — the uncovered-line triage (missing-test / dead-code / defensive-by-contract), why mutation testing splits what coverage cannot, and when duplication is the deliberate price of the hot-loop rule. ([8999661b](https://github.com/dfa1/vortex-java/commit/8999661b))

## [0.8.2] — 2026-06-22

The headline is **writer-side zone-map statistics**: the writer now emits `vortex.stats` (zoned) layouts carrying per-chunk MIN/MAX, NULL_COUNT, and SUM — matching the Rust reference — so zone-map chunk pruning and aggregate push-down work on Java-written files (previously the reader could decode these stats but the writer never produced them). The release also continues the test-hardening track: the lowest-covered encoder/decoder paths are filled in, SonarCloud new-code coverage is back to 100% with the quality gate green (overall ~83%, all ratings A, zero bugs/vulnerabilities), and the build toolchain is refreshed across eight dependency bumps.

### Added

- Writer: `vortex.stats` (zoned) layout emission, toggled by `WriteOptions.enableZoneMaps`. Each column is wrapped with a per-zone (one zone per chunk) statistics table; the stat set follows the Rust reference exactly. ([838dba82](https://github.com/dfa1/vortex-java/commit/838dba82), [f2d74351](https://github.com/dfa1/vortex-java/commit/f2d74351))
- Writer: per-zone **MIN/MAX** for primitive columns including F16, extension columns (over their storage primitive), Utf8 columns (full string bounds), and dictionary-encoded columns (computed on the logical values, independent of the dict encoding). ([838dba82](https://github.com/dfa1/vortex-java/commit/838dba82), [fb5d096a](https://github.com/dfa1/vortex-java/commit/fb5d096a), [38ab5c51](https://github.com/dfa1/vortex-java/commit/38ab5c51), [c1198253](https://github.com/dfa1/vortex-java/commit/c1198253), [e51da936](https://github.com/dfa1/vortex-java/commit/e51da936))
- Writer: per-zone **NULL_COUNT** for every column type. ([135c9b37](https://github.com/dfa1/vortex-java/commit/135c9b37), [c52d4b83](https://github.com/dfa1/vortex-java/commit/c52d4b83), [ab233b86](https://github.com/dfa1/vortex-java/commit/ab233b86))
- Writer: per-zone **SUM** for numeric primitive columns (signed → `i64`, unsigned → `u64`, float → `f64`; integer overflow records a null sum). Matches Rust, which sums numeric primitives and decimals but not Utf8/extension columns. ([9661f554](https://github.com/dfa1/vortex-java/commit/9661f554))
- Reader: `RowFilter.isNull` / `RowFilter.isNotNull` predicates with zone-map chunk pruning — IS NULL skips chunks with zero nulls, IS NOT NULL skips all-null chunks — via the per-chunk `null_count`. ([2749b6ca](https://github.com/dfa1/vortex-java/commit/2749b6ca))
- Reader: `columnStats()` aggregates `null_count` across a column's chunks (reported only when every chunk carries one). ([cb844f23](https://github.com/dfa1/vortex-java/commit/cb844f23))

### Changed

- Reader: the shared default `HttpClient` behind `VortexHttpReader.open(URI, ReadRegistry)` is now a package-private non-final field used purely as a unit-test seam, so the default-client overload is driven to a normal return by a mocked client instead of a live network call. Production never reassigns it. ([12e46270](https://github.com/dfa1/vortex-java/commit/12e46270))

### Tests

- Coverage for the ten lowest-coverage encode/decode classes — `ZigZagEncodingDecoder`/`Encoder`, `SequenceEncodingEncoder`, `VariantEncodingDecoder.dtypeFromProto` (every proto→core `DType` arm), `TimeExtensionEncoder`, `VarBinViewEncodingDecoder`, `VarBinEncodingDecoder`, `AlpEncodingDecoder`, `DateTimePartsEncodingDecoder`, and `DeltaEncodingDecoder` — exercising guards, broadcast/constant paths, and ptype arms. ([a3012d4a](https://github.com/dfa1/vortex-java/commit/a3012d4a), [c9386eda](https://github.com/dfa1/vortex-java/commit/c9386eda), [6c9682b8](https://github.com/dfa1/vortex-java/commit/6c9682b8), [bbb9d669](https://github.com/dfa1/vortex-java/commit/bbb9d669), [7742ecd3](https://github.com/dfa1/vortex-java/commit/7742ecd3))
- Writer: property-based and mutation-driven round-trips for the Delta and AlpRd encoders. ([d3d245a6](https://github.com/dfa1/vortex-java/commit/d3d245a6))
- Reader: HTTP fixtures bumped to `v0.75.0` with a smoke test across all encodings; the `open(URI, ReadRegistry)` overload is now covered via the default-client seam. ([8a1b5db2](https://github.com/dfa1/vortex-java/commit/8a1b5db2), [12e46270](https://github.com/dfa1/vortex-java/commit/12e46270))
- Reader: decoder tests allocate via `Arena.ofAuto()` instead of the never-freed `Arena.global()`. ([59ec2e2a](https://github.com/dfa1/vortex-java/commit/59ec2e2a))

### Build

- Dependency refresh: `jacoco-maven-plugin` 0.8.13→0.8.15, `pitest-maven` 1.20.0→1.25.5, `checkstyle` 13.5.0→13.6.0, `byte-buddy-agent` 1.17.7→1.18.10, `central-publishing-maven-plugin` 0.10.0→0.11.0, `maven-jar-plugin` 3.4.1→3.5.0, `maven-dependency-plugin` 3.7.0→3.11.0, and `actions/checkout` 6→7. ([dab876b7](https://github.com/dfa1/vortex-java/commit/dab876b7), [7b7c3580](https://github.com/dfa1/vortex-java/commit/7b7c3580), [46659a73](https://github.com/dfa1/vortex-java/commit/46659a73), [46a30be1](https://github.com/dfa1/vortex-java/commit/46a30be1), [c6723832](https://github.com/dfa1/vortex-java/commit/c6723832), [3e5fa349](https://github.com/dfa1/vortex-java/commit/3e5fa349), [c943f81b](https://github.com/dfa1/vortex-java/commit/c943f81b), [af009116](https://github.com/dfa1/vortex-java/commit/af009116))

## [0.8.1] — 2026-06-20

A hardening release: no new file-format capability, but a large step up in verification rigour. Mutation testing (PIT) now guards the security-critical bounds/parse paths in core, reader, and writer at 99–100% kill rate; the build fails on any javac warning (`-Xlint:all -Werror`); and property-based round-trips exercise every lossless encoding plus the full cascade-selection pipeline against seeded-random inputs. The one functional addition is boxed-nullable array input on the map `writeChunk` path.

### Added

- Writer: the map-based `writeChunk` path accepts boxed nullable arrays (`Integer[]`, `Long[]`, `Double[]`, …) alongside primitive arrays, so columns with nulls can be written without manual validity bookkeeping. ([4d18939a](https://github.com/dfa1/vortex-java/commit/4d18939a))

### Changed

- **Breaking — `ExtensionEncoder.encodeAll` is now abstract.** The default body threw `VortexException`; every implementation already overrides it, so the contract now fails at compile time rather than at runtime. ([2dcd69ce](https://github.com/dfa1/vortex-java/commit/2dcd69ce))
- **Breaking — `Estimate` is now an enum** `{ SKIP, ALWAYS_USE, COMPLETE }`. The sealed interface with empty `Skip`/`AlwaysUse` records, the `skip()`/`alwaysUse()` factories, and the `null` "no verdict" sentinel are gone; `COMPLETE` is the explicit defer-to-sample-encode verdict. ([c355a4bf](https://github.com/dfa1/vortex-java/commit/c355a4bf))
- Reader cleanups: dropped a dead `length < 0` blob check and a redundant `offset > fileSize` bounds clause, reused the shared `PTypeIO` little-endian layouts, and removed redundant numeric casts flagged by static analysis. ([5d5fcc45](https://github.com/dfa1/vortex-java/commit/5d5fcc45), [36328285](https://github.com/dfa1/vortex-java/commit/36328285), [04cab707](https://github.com/dfa1/vortex-java/commit/04cab707))

### Fixed

- Writer: I8/I16 columns are excluded from the global dictionary — the reader cannot decode a narrow-int dict, so dict-encoding them produced unreadable files. ([473256b1](https://github.com/dfa1/vortex-java/commit/473256b1))
- Writer: `WriteRegistry` now iterates encoders in a deterministic order and `accepts()` reports honestly, fixing a non-deterministic encoder selection that broke the Windows build. ([9c4ebb18](https://github.com/dfa1/vortex-java/commit/9c4ebb18))
- Reader: Pco decode now guards `preDeltaN` against int overflow before clamping — the subtraction is widened to `long`, restoring the overflow-safe path. ([b7346e7c](https://github.com/dfa1/vortex-java/commit/b7346e7c))

### Build

- Zero-warning rule: `-Xlint:all -Werror` across all modules. The `classfile` lint (which only flags missing annotation class files inside third-party Arrow bytecode) is scoped off in the two Arrow-using modules only. ([dab467e5](https://github.com/dfa1/vortex-java/commit/dab467e5), [43f6f840](https://github.com/dfa1/vortex-java/commit/43f6f840))
- Mutation testing (PIT): opt-in `pitest` profiles in core, reader, and writer, scoped to the bounds/parse classes (`IoBounds`, `PTypeIO`, `WriteRegistry`, `ChunkImpl`, …), with common config hoisted into the parent POM. ([46904b24](https://github.com/dfa1/vortex-java/commit/46904b24), [ed8c98a1](https://github.com/dfa1/vortex-java/commit/ed8c98a1), [1200c76b](https://github.com/dfa1/vortex-java/commit/1200c76b), [840cc46a](https://github.com/dfa1/vortex-java/commit/840cc46a))
- SonarCloud: generated `fbs/` and `proto/` sources excluded from analysis (machine output, not hand-maintained); the deliberate per-width SIMD-loop duplication is documented in [ADR 0005](docs/adr/0005-vector-api-adoption.md) rather than refactored away. Code smells dropped 857→394; coverage ~81%, all ratings A, zero bugs/vulnerabilities. ([6c591293](https://github.com/dfa1/vortex-java/commit/6c591293))

### Tests

- Property-based lossless round-trips added for ALP (f32/f64), Delta/FoR/ZigZag/AlpRd, a bitpacked bit-width sweep, the full `CascadingCompressor` (every codec × cascade depth 0–3), and a Pco seeded-random distribution sweep. ([dbe44aaa](https://github.com/dfa1/vortex-java/commit/dbe44aaa), [a2cf3443](https://github.com/dfa1/vortex-java/commit/a2cf3443), [aede11d7](https://github.com/dfa1/vortex-java/commit/aede11d7), [115dd6fd](https://github.com/dfa1/vortex-java/commit/115dd6fd), [a426c1de](https://github.com/dfa1/vortex-java/commit/a426c1de))
- Mutation-driven test hardening lifted core/reader/writer bounds and registry classes to 99–100% kill rate. ([2235499a](https://github.com/dfa1/vortex-java/commit/2235499a), [c9243f9a](https://github.com/dfa1/vortex-java/commit/c9243f9a), [912fcaff](https://github.com/dfa1/vortex-java/commit/912fcaff))
- Integration: added Java↔Rust round-trips for `vortex.patched`, `fastlanes.delta`, and `masked` encodings. ([13702764](https://github.com/dfa1/vortex-java/commit/13702764))
- CLI: terminal smoke tests now force class initialization so the FFM libc/kernel32 symbol resolution is actually exercised. ([3f741ef7](https://github.com/dfa1/vortex-java/commit/3f741ef7))

## [0.8.0] — 2026-06-20

Read and write Vortex Variant (semi-structured, JSON-shaped) columns from Java. Internally, transform encodings now decode lazily, trimming per-decode allocation. This release also hardens the reader's bounds handling on untrusted input (ADR 0003 Phase E), fixes CSV-import memory blow-ups on large files, and lifts test coverage to 80% with all Sonar ratings at A.

### Added

- Writer: `vortex.variant` encoder. Encodes a variant column as the canonical `vortex.variant` container over `core_storage` — an all-equal column becomes a single `vortex.constant`, a row-varying column a `vortex.chunked` of per-run constants — with an optional row-aligned typed `shredded` child recorded in `VariantMetadata.shredded_dtype`. Input is `VariantData(List<Scalar>)` with `.constant(n, v)` / `.shredded(...)` factories. Java↔Rust (JNI) round-trip verified for constant, row-varying, and shredded columns. Scalar values only — arbitrary nested objects need `vortex.parquet.variant` (deferred, [ADR 0014](docs/adr/0014-variant-encoding-strategy.md)). ([35da529d](https://github.com/dfa1/vortex-java/commit/35da529d), [e4e44980](https://github.com/dfa1/vortex-java/commit/e4e44980), [4566dca0](https://github.com/dfa1/vortex-java/commit/4566dca0))
- Reader: variant columns now decode Java-side. `ConstantEncodingDecoder` and `ChunkedEncodingDecoder` handle `DType.Variant` (materialising the inner-typed array); `VariantEncodingDecoder` wraps the result as `VariantArray`, exposing `coreStorage()` and `shredded()`. ([76e4c741](https://github.com/dfa1/vortex-java/commit/76e4c741), [4566dca0](https://github.com/dfa1/vortex-java/commit/4566dca0))

### Security

- Reader bounds hardening (ADR 0003 Phase E): untrusted offsets/lengths from file metadata now flow through a typed `IoBounds` helper that throws `VortexException` instead of a raw `IndexOutOfBoundsException`, and hand-rolled index guards were replaced with `Objects.checkIndex`. A crafted flat-segment file can no longer trip an unchecked array access during decode. ([e9af80d6](https://github.com/dfa1/vortex-java/commit/e9af80d6), [3bcd9881](https://github.com/dfa1/vortex-java/commit/3bcd9881), [a5ce8380](https://github.com/dfa1/vortex-java/commit/a5ce8380))

### Fixed

- CSV import: large files no longer OOM. The importer now streams rows in a single pass (buffering only the first chunk for schema inference) and disables the global-dictionary pass by default, which previously accumulated every distinct value in memory. ([d5280ae2](https://github.com/dfa1/vortex-java/commit/d5280ae2), [0b6784b5](https://github.com/dfa1/vortex-java/commit/0b6784b5), [62863616](https://github.com/dfa1/vortex-java/commit/62863616))
- CLI: `IoWorker.runAndAwait` decremented its in-flight counter *after* signaling completion, so a caller reading `pending()` right after it returned could still see the task counted; the counter is now decremented before the await returns. The `view`/`tui` commands also close the opened `VortexHandle` on every error path (`openOnWorker` returns `Optional`). ([95c06b1a](https://github.com/dfa1/vortex-java/commit/95c06b1a), [27446d81](https://github.com/dfa1/vortex-java/commit/27446d81))
- Reader: `BoolArray.materialize` masked the accumulator byte before the bit-set OR, removing a sign-promotion footgun in the packed-bitmap write. ([bc8e9d4e](https://github.com/dfa1/vortex-java/commit/bc8e9d4e))

### Changed

- Decode shape: transform encodings now decode **lazy-only**. The eager `Materialized*Array` fallbacks were removed from `vortex.zigzag` (all PTypes + broadcast, [cd59fefa](https://github.com/dfa1/vortex-java/commit/cd59fefa)), `fastlanes.for` (all integer PTypes, [d7953e1f](https://github.com/dfa1/vortex-java/commit/d7953e1f)), `vortex.alp` (broadcast-without-patches, [deab8067](https://github.com/dfa1/vortex-java/commit/deab8067)), `vortex.constant` (Decimal → `LazyConstantDecimalArray`, [a6a9611e](https://github.com/dfa1/vortex-java/commit/a6a9611e)), `vortex.runend` (Bool → `LazyRunEndBoolArray`, [0bbcb81f](https://github.com/dfa1/vortex-java/commit/0bbcb81f)), `vortex.sparse` (Bool → `LazySparseBoolArray`, [db2e955b](https://github.com/dfa1/vortex-java/commit/db2e955b)), and `fastlanes.rle` (validity → `OffsetBoolArray`, empty → `LazyConstantXxxArray`, [5e83a5c3](https://github.com/dfa1/vortex-java/commit/5e83a5c3)). Decompression encodings (`bitpacked`, `pco`, `zstd`, `fsst`, `delta`, `patched`), the primitive base, the `vortex.dict` encoding-level path, and the `vortex.alp` patches path stay Materialized by design. See [ADR 0015](docs/adr/0015-drop-materialized-fallbacks.md).
- **Breaking — sealed `Array` permits changed.** `DecimalArray` is now a `non-sealed` family interface (decimal arrays moved from `implements Array` to `implements DecimalArray`), so decimal joins the per-dtype family layer. Downstream exhaustive `switch` over `Array` must add a `case DecimalArray`. ([a6a9611e](https://github.com/dfa1/vortex-java/commit/a6a9611e))
- **Breaking — `Array` API.** `Array.truncate(rows)` renamed to `Array.limited(rows)` and made an abstract operation implemented by every array (composites slice their children); raw-segment access moved off the `ArraySegments` utility onto `Array.materialize(SegmentAllocator)` and `Array.segmentIfPresent()`. ([87ab65e2](https://github.com/dfa1/vortex-java/commit/87ab65e2), [4d9ac1f8](https://github.com/dfa1/vortex-java/commit/4d9ac1f8), [332b067e](https://github.com/dfa1/vortex-java/commit/332b067e), [32a35e03](https://github.com/dfa1/vortex-java/commit/32a35e03))
- CSV import reports progress every 10K rows instead of per-chunk. ([07a056e7](https://github.com/dfa1/vortex-java/commit/07a056e7))

### Removed

- **Breaking — `EmptyArray` removed** from the sealed `Array` permits. It was never emitted by the reader (empties are zero-length typed arrays in their own family) and broke the dtype→family invariant (`EmptyArray(I64)` was not a `LongArray`). Represent an empty column as a zero-length array of the appropriate family. ([3a4dcdfa](https://github.com/dfa1/vortex-java/commit/3a4dcdfa))

### Documentation

- [ADR 0016](docs/adr/0016-vortex-arrow-bridge.md): captures `vortex-arrow` bridge interop options (separate module / Arrow C-Data / none); deferred until a concrete downstream need. ([a6126f29](https://github.com/dfa1/vortex-java/commit/a6126f29))

### Tests

- Test coverage raised from ~74% to 80% — the lazy/chunked/dict/run-end/sparse array families, `ChunkImpl`, and several decoders (`DecimalEncodingDecoder`, `DictEncodingDecoder`, `ParquetImporter`) reached full line + branch coverage. SonarCloud quality gate green: reliability, security, and maintainability all at **A**, zero bugs and vulnerabilities.

[0.8.3]: https://github.com/dfa1/vortex-java/compare/v0.8.2...v0.8.3
[0.8.2]: https://github.com/dfa1/vortex-java/compare/v0.8.1...v0.8.2
[0.8.1]: https://github.com/dfa1/vortex-java/compare/v0.8.0...v0.8.1
[0.8.0]: https://github.com/dfa1/vortex-java/compare/v0.7.3...v0.8.0

## [0.7.3] — 2026-06-17

Parquet ZSTD support, `vortex.patched` encoder, constant-encoding selection fix, Windows TUI raw-mode fix.

### Added

- Parquet: ZSTD-compressed Parquet import now works — `zstd-jni` was an optional dep in hardwood and had to be declared explicitly. NYC Yellow Taxi 2024-01 (47.6 MB Parquet, 2.96 M rows × 19 cols) imports to **40.7 MB** Vortex — 14% smaller than the Rust JNI reference (47 MB) thanks to the global-dict encoder catching low-cardinality `F64` columns. ([bea15f2d](https://github.com/dfa1/vortex-java/commit/bea15f2d))
- Writer: `vortex.patched` encoder — identifies outlier values that exceed the optimal bit width, zeros them in the inner array (exposed as an open cascade child for further bitpacking), and stores their within-chunk U16 indices and raw values separately. ([d63ab7c3](https://github.com/dfa1/vortex-java/commit/d63ab7c3))

### Fixed

- CLI: Windows TUI raw-mode — `readKey` now calls `ReadFile` directly on the kernel handle obtained via `GetStdHandle` instead of reading from `System.in`. Java's `System.in` goes through JVM-internal CRT wrappers that ignore `SetConsoleMode`, so every keypress previously required Enter before the TUI reacted. ([31b77acc](https://github.com/dfa1/vortex-java/commit/31b77acc))
- Writer: constant encoding skipped for single-distinct-value columns — `isDictCandidate` returned `true` for `distinctCount == 1`, routing all-same-value columns through the global-dict path instead of `vortex.constant`. ([0e8b945e](https://github.com/dfa1/vortex-java/commit/0e8b945e))

### Changed

- CLI: polling loop in `Terminal.readKey(Duration)` extracted to `KeyDecoder.nextWithTimeout(InputStream, Duration)` — eliminates duplication between `PosixTerminal` and `WindowsTerminal`. ([35b05d16](https://github.com/dfa1/vortex-java/commit/35b05d16))

### Tests

- Integration: `TaxiParquetOracleVsJavaIntegrationTest` — hardwood reads the taxi Parquet to a CSV (oracle); `ParquetImporter` → `CsvExporter` produces a second CSV (SUT); line-by-line diff must be zero. Proves the importer loses no data across 2.96 M rows × 19 columns. ([1a1a676e](https://github.com/dfa1/vortex-java/commit/1a1a676e))

[0.7.3]: https://github.com/dfa1/vortex-java/compare/v0.7.2...v0.7.3

## [0.7.2] — 2026-06-16

CLI usability + reader robustness on real-world files (NYC Yellow Taxi).

### Added

- CLI `view <file>` — scrollable Excel-like grid TUI. Streams rows on demand via a new `LazyGridSource` (one live chunk at a time, format only the visible window). Title bar shows `chunk K/N`. Default writes to alt-screen; quit with `q` / `Esc`. ([1c0311fb](https://github.com/dfa1/vortex-java/commit/1c0311fb), [b7f6b6c1](https://github.com/dfa1/vortex-java/commit/b7f6b6c1), [94e5bff8](https://github.com/dfa1/vortex-java/commit/94e5bff8), [6a8ddd3a](https://github.com/dfa1/vortex-java/commit/6a8ddd3a))
- CLI `export` writes to a derived `<name>.csv` next to the input by default, with a stderr progress bar mirroring the import flow. `export <file.vortex> -` keeps the old stdout streaming behaviour. ([2b26da9a](https://github.com/dfa1/vortex-java/commit/2b26da9a))
- Reader: `ScanIterator.chunkRowCounts()` — returns per-chunk row counts by walking the layout tree, no value decode. Used by the `view` TUI to plan navigation. ([b7f6b6c1](https://github.com/dfa1/vortex-java/commit/b7f6b6c1))
- Reader: lazy `vortex.decimal` decode — new `LazyDecimalArray` record holds a zero-copy mmap slice and produces `BigDecimal` per `getDecimal(i)`. Replaces the `GenericArray` wrapper, no buffers / children indirection. ([6bc955d2](https://github.com/dfa1/vortex-java/commit/6bc955d2))
- Reader: 7 `Offset*Array` records (Long / Int / Short / Byte / Double / Float / Bool) + `VarBinArray.SlicedMode` for offset-based slicing of pre-decoded shared arrays. ([5df3d9a9](https://github.com/dfa1/vortex-java/commit/5df3d9a9))

### Fixed

- Reader: per-column chunking alignment — files where one column has 1 mega-flat and another has N small flats (e.g. NYC Yellow Taxi 2024-01 has a 2.96M-row VendorID flat next to 23 × 131072-row datetime flats) now decode the wide column once into a `sharedArena` and slice it per chunk via `Offset*Array`. Previously the scan iterator emitted a single chunk whose datetime columns were the first 131072 rows only — silently dropping 95.6 % of the file. ([5df3d9a9](https://github.com/dfa1/vortex-java/commit/5df3d9a9))
- Reader: `FrameOfReferenceEncodingDecoder` now takes the arena variant of `ArraySegments.of`, so lazy children (e.g. `LazyRunEndLongArray`) materialise instead of throwing "no primary segment". ([5df3d9a9](https://github.com/dfa1/vortex-java/commit/5df3d9a9))

### Docs

- Compatibility table: `constant`, `varbinview`, `alprd`, `datetimeparts`, `decimal_byte_parts`, `decimal` rows now reflect their shipped Lazy shape; container encodings (`list` / `listview` / `fixed_size_list`) marked Lazy (inherit child shape); `patched` pinned Materialized with reasoning. ([6e87b74e](https://github.com/dfa1/vortex-java/commit/6e87b74e), [6bc955d2](https://github.com/dfa1/vortex-java/commit/6bc955d2), [2ed32ec8](https://github.com/dfa1/vortex-java/commit/2ed32ec8), [d8363920](https://github.com/dfa1/vortex-java/commit/d8363920))

[0.7.2]: https://github.com/dfa1/vortex-java/compare/v0.7.1...v0.7.2

## [0.7.1] — 2026-06-16

Cleanup release on top of 0.7.0 — one more lazy encoding, a Windows TUI usability fix, and a fresh round of read benchmarks.

### Added

- `vortex.constant` lazy decode — seven metadata-only `LazyConstantXxxArray` records (Long / Int / Double / Float / Short / Byte / Bool) replace the one-element broadcast buffer; the per-element broadcast-modulo path is gone ([3edf6e8c](https://github.com/dfa1/vortex-java/commit/3edf6e8c))
- Top-N read benchmarks (N=10, 100) + README table, refreshed 80M-row numbers ([c00fdf7f](https://github.com/dfa1/vortex-java/commit/c00fdf7f), [33714d7b](https://github.com/dfa1/vortex-java/commit/33714d7b), [a6fd92fc](https://github.com/dfa1/vortex-java/commit/a6fd92fc))

### Changed

- CLI: `schema` prints per-row column listing ([9b3fe4b5](https://github.com/dfa1/vortex-java/commit/9b3fe4b5))
- CLI: `Terminal.readKey` takes `Duration` instead of `long ms` ([2942a4da](https://github.com/dfa1/vortex-java/commit/2942a4da))
- Reader: extract `TimeDtype` + `TimestampDtype` shared metadata helpers ([8f1b9feb](https://github.com/dfa1/vortex-java/commit/8f1b9feb))

### Fixed

- CLI: actionable error on Git Bash / MinTTY — `GetConsoleMode` failure now points users at `winpty` / Windows Terminal / PowerShell instead of dead-ending on the raw error ([6ec42288](https://github.com/dfa1/vortex-java/commit/6ec42288))
- Reader: `ArraySegments.of(arr)` typed-accessor fallback for lazy arrays ([74ec207b](https://github.com/dfa1/vortex-java/commit/74ec207b))

### CI

- Drop `sonar.cpd.exclusions` ([cde845bf](https://github.com/dfa1/vortex-java/commit/cde845bf))

[0.7.1]: https://github.com/dfa1/vortex-java/compare/v0.7.0...v0.7.1

## [0.7.0] — 2026-06-16

**pco encoder** (Classic + Consecutive delta + IntMult mode, 4-way tANS, multi-chunk, all 8 ptypes),
**writer compression** (~93% Rust JNI parity on NYC Yellow Taxi: 47.0 MB → 43.4 MB; stratified sampling, stats-driven cascade, sparse-cascade idx/val children, patched bitpacking),
**lazy / zero-copy decode** (ADR 0010 + ADR 0012: ALP / FoR / ZigZag / Chunked / Dict / RunEnd / RLE / Sparse / ALP-RD / VarBinView / DateTimeParts / DecimalByteParts now defer transform / materialisation until access),
**write API ergonomics** (`DType` static factories, `structBuilder`, typed `writeChunk(Consumer<Chunk>)` — ADR 0009),
**Sonar pass** (Codecov → SonarCloud, Javadoc HTML → Markdown, full `S6218 / S7474 / S2184 / S3776` sweep).

### Added

- `vortex.pco` encoder (`PcoEncodingEncoder`) — Classic mode + Consecutive delta + IntMult mode (mode=1); 4-way interleaved tANS; histogram + bin-optimization DP; multi-chunk (64K-element chunks); all 8 supported ptypes (I16/U16/I32/U32/F32/I64/U64/F64) ([1bb14ab](https://github.com/dfa1/vortex-java/commit/1bb14ab), [086aa52](https://github.com/dfa1/vortex-java/commit/086aa52), [30579ed](https://github.com/dfa1/vortex-java/commit/30579ed), [7219974](https://github.com/dfa1/vortex-java/commit/7219974), [f856559](https://github.com/dfa1/vortex-java/commit/f856559))
- `LeBitWriter` — LSB-first bit writer, symmetric to `LeBitReader`; reusable for future bit-oriented encoders ([1bb14ab](https://github.com/dfa1/vortex-java/commit/1bb14ab))
- ADR 0009 — write API ergonomics: `DType` static factories + `asNullable()` ([0e9d6703](https://github.com/dfa1/vortex-java/commit/0e9d6703)), `DType.structBuilder()` ([63d66eef](https://github.com/dfa1/vortex-java/commit/63d66eef)), typed `writeChunk(Consumer<Chunk>)` builder ([ddb3e21a](https://github.com/dfa1/vortex-java/commit/ddb3e21a)); design doc ([d9c4b99](https://github.com/dfa1/vortex-java/commit/d9c4b99), [a57ea70](https://github.com/dfa1/vortex-java/commit/a57ea70)); `MemorySegment` zero-copy overload split to ADR 0011 ([6367eb37](https://github.com/dfa1/vortex-java/commit/6367eb37))
- ADR 0010 — lazy decode for 1:1 transform encodings: `LazyAlpFloatArray`, lazy `FoR` / `ZigZag` arrays defer the transform until first element access ([cff3acb5](https://github.com/dfa1/vortex-java/commit/cff3acb5), [c47c055c](https://github.com/dfa1/vortex-java/commit/c47c055c), [c3ca6951](https://github.com/dfa1/vortex-java/commit/c3ca6951), [68186f8f](https://github.com/dfa1/vortex-java/commit/68186f8f))
- ADR 0012 — zero-copy decode for compound encodings. `ChunkedXxxArray` wraps instead of concatenating ([dfe7aa34](https://github.com/dfa1/vortex-java/commit/dfe7aa34), [c557b8fb](https://github.com/dfa1/vortex-java/commit/c557b8fb), [e2db153d](https://github.com/dfa1/vortex-java/commit/e2db153d)); `DictXxxArray` lazy reads ([9b97a1a5](https://github.com/dfa1/vortex-java/commit/9b97a1a5)); lazy `RunEnd` ([210449b5](https://github.com/dfa1/vortex-java/commit/210449b5)), `RLE` ([f35f9a96](https://github.com/dfa1/vortex-java/commit/f35f9a96)), `Sparse` ([b604f21c](https://github.com/dfa1/vortex-java/commit/b604f21c)), `ALP-RD` ([937ade36](https://github.com/dfa1/vortex-java/commit/937ade36)); `VarBinArray.ChunkedMode` ([b3696f5a](https://github.com/dfa1/vortex-java/commit/b3696f5a)) + `ViewMode` for VarBinView ([0eea0405](https://github.com/dfa1/vortex-java/commit/0eea0405)); `LazyDateTimePartsLongArray` ([8ab9ec70](https://github.com/dfa1/vortex-java/commit/8ab9ec70)); `LazyDecimalBytePartsArray` ([22887cb2](https://github.com/dfa1/vortex-java/commit/22887cb2)); design doc ([f6a19c47](https://github.com/dfa1/vortex-java/commit/f6a19c47), [2578f892](https://github.com/dfa1/vortex-java/commit/2578f892), [1c7f5950](https://github.com/dfa1/vortex-java/commit/1c7f5950))
- ADR 0013 — compute primitives (masks, kernels, no-materialise) design doc ([400e5b03](https://github.com/dfa1/vortex-java/commit/400e5b03))
- `forEach*` / `fold` default methods on Short / Byte / Bool array interfaces; chunked overrides iterate children directly ([7dc6567e](https://github.com/dfa1/vortex-java/commit/7dc6567e), [f500afe3](https://github.com/dfa1/vortex-java/commit/f500afe3))
- `truncateArray` preserves zero-copy on `ChunkedXxxArray` ([6f4eaa96](https://github.com/dfa1/vortex-java/commit/6f4eaa96))
- ALP size-based exponent search ported from Rust, two-step decode ([f9bb7373](https://github.com/dfa1/vortex-java/commit/f9bb7373))
- Decode shape table in `docs/compatibility.md` ([47a91fd1](https://github.com/dfa1/vortex-java/commit/47a91fd1))
- Writer compression closes ~93% of the Java↔Rust file-size gap on NYC Yellow Taxi 2024-01 (2.96M rows, 19 cols): **47.0 MB → 43.4 MB**; Rust JNI baseline 42.8 MB. Four coordinated changes ported from `vortex-compressor`:
  - Global dictionary encoding admitted for F64 columns. Codes assigned in frequency-descending order so the dominant value maps to code 0, which lets `SparseEncodingEncoder` (fill = 0) compress the codes child. Mirrors Rust `FloatDictScheme`. Also: `FrameOfReferenceEncodingEncoder` skips cascade when `ref == 0` and ptype is unsigned (residuals == input, FoR adds wrapper overhead for zero benefit) ([01fbaa6](https://github.com/dfa1/vortex-java/commit/01fbaa6))
  - Stratified sampling in `CascadingCompressor`: 32 contiguous strides at evenly-partitioned, non-overlapping offsets. Preserves local run structure so `RunEnd`/`RLE` can win on dict codes while covering breadth so cardinality-based encoders see realistic distinct counts. Matches `vortex-compressor::sample::stratified_slices` ([715a697](https://github.com/dfa1/vortex-java/commit/715a697), [da16f0d](https://github.com/dfa1/vortex-java/commit/da16f0d))
  - Stats-driven cascade selection. Single-pass `ArrayStats` (distinct count, top-frequency value + count) shared across all eligible encoders via merged `StatsOptions`. New `Estimate` sealed hierarchy (`Skip` / `AlwaysUse` / `Ratio`) lets encoders short-circuit the cascade without paying the sample-encode cost. `ConstantEncodingEncoder.AlwaysUse` when distinct == 1; `DictEncodingEncoder.Skip` when distinct > n/2 (Rust `FloatDictScheme`/`IntDictScheme` rule); `SparseEncodingEncoder.Skip` unless dominant-value bits == 0 and `topFreq * 2 >= n`; `RunEndEncodingEncoder.Skip` when every value is distinct. Mirrors `vortex-compressor::estimate::EstimateVerdict` ([2e31265](https://github.com/dfa1/vortex-java/commit/2e31265))
  - `SparseEncodingEncoder.encodeCascade` exposes patch-index and patch-value buffers as `ChildSlot` entries so the cascade further bitpacks them — biggest single lever (~1.7 MB on dict-coded F64 columns: tolls_amount, Airport_fee, congestion_surcharge, mta_tax, RatecodeID, improvement_surcharge) ([2ad275c](https://github.com/dfa1/vortex-java/commit/2ad275c))
- Patched bitpacking — `BitpackedEncodingEncoder` picks the best `bit_width` and stores overflow as sparse patches; on `trip_distance`-style columns Java is 1.8 MB ahead of Rust JNI ([007e6c47](https://github.com/dfa1/vortex-java/commit/007e6c47))
- Per-chunk zone-map stats shown in the TUI inspector ([5e24fb62](https://github.com/dfa1/vortex-java/commit/5e24fb62))
- Per-chunk column row-count consistency validation in the writer ([c54c8dab](https://github.com/dfa1/vortex-java/commit/c54c8dab))
- `GlobalDictF64Test` — round-trip + dict cardinality + sparse-codes-child verification on dominant-F64 columns ([01fbaa6](https://github.com/dfa1/vortex-java/commit/01fbaa6))
- `TaxiColumnByteDiff` — per-column byte attribution diagnostic. Walks the layout tree, prints Java vs JNI bytes side-by-side. Used to locate the sparse-cascade gap that the global file size hid ([2ad275c](https://github.com/dfa1/vortex-java/commit/2ad275c))
- `TaxiCsvSize` diagnostic — reports CSV / CSV.gz sizes for the taxi corpus ([7f851557](https://github.com/dfa1/vortex-java/commit/7f851557), [5ba2ae30](https://github.com/dfa1/vortex-java/commit/5ba2ae30))
- ADR 0008 — domain primitives and unsigned integer representation ([806f52f2](https://github.com/dfa1/vortex-java/commit/806f52f2))

### Changed

- Primitive `Array` types are non-sealed interfaces; `fold` / `forEach` are default methods ([aec4d813](https://github.com/dfa1/vortex-java/commit/aec4d813), [f500afe3](https://github.com/dfa1/vortex-java/commit/f500afe3))
- `FoR` decode writes in-place when the source segment is writable; `applyReference` always allocates from the arena ([b1906a08](https://github.com/dfa1/vortex-java/commit/b1906a08), [9955a39f](https://github.com/dfa1/vortex-java/commit/9955a39f))
- ALP eager fallback collapsed to a single allocate + transform pass ([e3a6c21a](https://github.com/dfa1/vortex-java/commit/e3a6c21a))
- Arena lifted out of lazy array records into `ArraySegments.of` ([8d6fe4f0](https://github.com/dfa1/vortex-java/commit/8d6fe4f0))
- `ScanIterator` drops dead `registry` field ([53dfdcbb](https://github.com/dfa1/vortex-java/commit/53dfdcbb))
- Per-chunk zone-map stats shown in TUI inspector ([5e24fb62](https://github.com/dfa1/vortex-java/commit/5e24fb62))
- Javadoc HTML tags → Markdown `///` ([44d2a052](https://github.com/dfa1/vortex-java/commit/44d2a052)); `{@code}` → backticks ([aca51d2f](https://github.com/dfa1/vortex-java/commit/aca51d2f))
- CI: Codecov → SonarCloud, daily scheduled run ([c600e3d0](https://github.com/dfa1/vortex-java/commit/c600e3d0), [1e5816a5](https://github.com/dfa1/vortex-java/commit/1e5816a5)); failsafe + integration jacoco-it included in Sonar coverage ([c59e2f6c](https://github.com/dfa1/vortex-java/commit/c59e2f6c), [03b2dfe7](https://github.com/dfa1/vortex-java/commit/03b2dfe7))
- CI: Mockito self-attach warning silenced via byte-buddy-agent ([2ba7b877](https://github.com/dfa1/vortex-java/commit/2ba7b877), [1183c526](https://github.com/dfa1/vortex-java/commit/1183c526))
- OHLC read benchmarks re-run at 80M rows; `-Dvortex.bench.ohlc.rows` override added ([9b7fd61f](https://github.com/dfa1/vortex-java/commit/9b7fd61f))
- `dev.vortex:vortex-jni` 0.74.0 → 0.75.0 ([2f55f1c1](https://github.com/dfa1/vortex-java/commit/2f55f1c1)); `hardwood-core` and `zstd-jni` bumped ([ff5fe4b3](https://github.com/dfa1/vortex-java/commit/ff5fe4b3), [2c885d3b](https://github.com/dfa1/vortex-java/commit/2c885d3b))

### Fixed

- CLI: terminal mode restored on TUI exit ([60cda920](https://github.com/dfa1/vortex-java/commit/60cda920))
- CLI: `aircompressor` bundled in the uber-jar so the zstd decoder loads ([e96c5968](https://github.com/dfa1/vortex-java/commit/e96c5968))
- CLI: scan-based filter parser; `VortexException` caught at the boundary ([d9cff370](https://github.com/dfa1/vortex-java/commit/d9cff370), [6165c497](https://github.com/dfa1/vortex-java/commit/6165c497))
- CLI: CSV import `--delimiter` flag ([976934b3](https://github.com/dfa1/vortex-java/commit/976934b3))
- CLI: `IoWorker` uses `queue.add` over `offer` so submission failures aren't silently dropped ([a624d3da](https://github.com/dfa1/vortex-java/commit/a624d3da))
- Reader: `LazySparsXxxArray` guards null `patchValues` when `numPatches == 0` ([d83ec1b5](https://github.com/dfa1/vortex-java/commit/d83ec1b5))
- Sonar pass: explicit widening on int-math feeding long/float (S2184) ([3cd23364](https://github.com/dfa1/vortex-java/commit/3cd23364), [ba6ea44d](https://github.com/dfa1/vortex-java/commit/ba6ea44d)); LazyRle S6218/S3776 + Pco S1905 ([a2ea4796](https://github.com/dfa1/vortex-java/commit/a2ea4796)); S6218 on internal records with array components ([e1d20cc5](https://github.com/dfa1/vortex-java/commit/e1d20cc5)); S7474/S6218 in new lazy array files ([11fe0c41](https://github.com/dfa1/vortex-java/commit/11fe0c41)); 2 hotspots + 1 assertion bug ([65ccd65d](https://github.com/dfa1/vortex-java/commit/65ccd65d)); SonarCloud organization key fix ([b95dcf0e](https://github.com/dfa1/vortex-java/commit/b95dcf0e))

### Notes

- Pco encode FloatMult / FloatQuant modes deferred — marginal gain over existing Classic+ALP cascade.
- Remaining 0.6 MB (1.4%) writer gap vs Rust JNI on the taxi benchmark is structural — concentrated in `trip_distance` (+540 KB, per-chunk ALP encoding) and `PULocationID` (+250 KB, dict-codes layout shape). Closing it needs `vortex.stats` outer-layer support or dtype-specialised dict schemes.

[0.7.0]: https://github.com/dfa1/vortex-java/compare/v0.6.0...v0.7.0

## [0.6.0] — 2026-06-13

**proto-rewrite** (`protobuf-java` → in-tree MemorySegment-native codec, CLI −14%),
**Extension API split** (`ExtensionDecoder` / `ExtensionEncoder` SPI, writer auto-route, UUID + nullable support, JDBC extension import),
**module boundary cleanup** (`Array` subtypes → `reader.array`, encode data holders → `writer.encode`).

### Added

- `proto-gen` module — build-time `.proto` → Java record/enum generator ([ae6c46a](https://github.com/dfa1/vortex-java/commit/ae6c46a), [743278d](https://github.com/dfa1/vortex-java/commit/743278d), [b527f84](https://github.com/dfa1/vortex-java/commit/b527f84))
- `ProtoReader` / `ProtoWriter` — MemorySegment-native proto3 wire primitives ([ae6c46a](https://github.com/dfa1/vortex-java/commit/ae6c46a), [b527f84](https://github.com/dfa1/vortex-java/commit/b527f84))
- Oneof factories on generated records, e.g. `ScalarValue.ofInt64Value(v)` ([b527f84](https://github.com/dfa1/vortex-java/commit/b527f84))
- `PatchedMetadata` / `VariantMetadata` added to `encodings.proto` ([743278d](https://github.com/dfa1/vortex-java/commit/743278d), [b527f84](https://github.com/dfa1/vortex-java/commit/b527f84))
- Nullable extension columns (`vortex.date/time/timestamp/uuid`) via `ExtEncoding → MaskedEncoding → primitive` ([1015f9b](https://github.com/dfa1/vortex-java/commit/1015f9b))
- Null-preserving `decodeAll` on all extension decoders — `null` at invalid positions ([24c64a9](https://github.com/dfa1/vortex-java/commit/24c64a9))
- `ExtensionDecoder` / `ExtensionEncoder` SPI with separate ServiceLoader manifests ([a560563](https://github.com/dfa1/vortex-java/commit/a560563))
- Spec extension decoders: Date, Time, Timestamp, Uuid in `reader.extension` ([a560563](https://github.com/dfa1/vortex-java/commit/a560563))
- Spec extension encoders: Date, Time, Timestamp, Uuid in `writer.encode` ([a560563](https://github.com/dfa1/vortex-java/commit/a560563))
- Writer auto-routes `List<LocalDate>`, `List<Instant>`, `List<UUID>`, … to extension storage ([1d54b57](https://github.com/dfa1/vortex-java/commit/1d54b57), [75d7b4b](https://github.com/dfa1/vortex-java/commit/75d7b4b))
- `vortex.uuid` extension — `FixedSizeList(U8, 16)`, big-endian, JDBC vendor detection ([89a0a69](https://github.com/dfa1/vortex-java/commit/89a0a69), [cce2d2d](https://github.com/dfa1/vortex-java/commit/cce2d2d))
- JDBC import for `DATE` / `TIME` / `TIMESTAMP` / UUID columns ([9f31d9e](https://github.com/dfa1/vortex-java/commit/9f31d9e), [cce2d2d](https://github.com/dfa1/vortex-java/commit/cce2d2d))
- `Chunk.as(name, Class)` — typed extension column access ([e5cefb0](https://github.com/dfa1/vortex-java/commit/e5cefb0))
- ExtEncoding storage child cascade-compressed (FoR / Bitpacked / ALP / RLE / …) ([33cf42e](https://github.com/dfa1/vortex-java/commit/33cf42e))
- Java → Rust nullable extension integration tests; UUID `@Disabled` pending vortex-jni upgrade ([bb7fcb0](https://github.com/dfa1/vortex-java/commit/bb7fcb0))

### Breaking

- `EncodingRegistry` → `ReadRegistry` in `io.github.dfa1.vortex.reader` ([834d2f1](https://github.com/dfa1/vortex-java/commit/834d2f1), [a560563](https://github.com/dfa1/vortex-java/commit/a560563))
- `core.Extension` / `core.ExtensionEncoder` → `reader.ExtensionDecoder` / `writer.ExtensionEncoder` ([2a0ed93](https://github.com/dfa1/vortex-java/commit/2a0ed93), [a560563](https://github.com/dfa1/vortex-java/commit/a560563))
- `VortexHttpReader.open` gains `HttpClient` overload ([235826f](https://github.com/dfa1/vortex-java/commit/235826f))
- `core.array.*` → `reader.array.*` — update import paths ([286715c](https://github.com/dfa1/vortex-java/commit/286715c))
- `core.array.NullableData` → `writer.encode.NullableData` ([286715c](https://github.com/dfa1/vortex-java/commit/286715c))
- Decode utilities (`LeBitReader`, `PcoBin`, `PcoTansDecoder`, `SegmentBroadcast`) → `reader.decode` ([d514435](https://github.com/dfa1/vortex-java/commit/d514435))
- Encode data holders (`ChunkedData`, `DateTimePartsData`, `FixedSizeListData`, …) → `writer.encode` ([d514435](https://github.com/dfa1/vortex-java/commit/d514435))
- `ExtEncoding` unwrap shortcut removed from registry ([4d4ab34](https://github.com/dfa1/vortex-java/commit/4d4ab34), [75d7b4b](https://github.com/dfa1/vortex-java/commit/75d7b4b))
- `ArrayNode.stats()` / `ArrayNode.of(…, stats)` removed — was dead code in decode path ([dc3aa00](https://github.com/dfa1/vortex-java/commit/dc3aa00f))

### Changed

- `regenerate-sources` profile uses in-process proto-gen; `protoc` no longer needed ([743278d](https://github.com/dfa1/vortex-java/commit/743278d))
- 25 encoding classes migrated to generated record API (`meta.bit_width()` style) ([0132417](https://github.com/dfa1/vortex-java/commit/0132417), [743278d](https://github.com/dfa1/vortex-java/commit/743278d))

### Fixed

- `VortexHttpReader` throws `VortexException` on HTTP body length mismatch ([235826f](https://github.com/dfa1/vortex-java/commit/235826f))
- `vortex.date` / `vortex.uuid` metadata presence fixes Java → Rust cross-compat ([bb7fcb0](https://github.com/dfa1/vortex-java/commit/bb7fcb0))
- Extension dtype `nullable` derived from storage dtype, not hardcoded `false` ([1015f9b](https://github.com/dfa1/vortex-java/commit/1015f9b))
- `DType.Extension.metadata` capped at 64 KiB on parse ([22a5f59](https://github.com/dfa1/vortex-java/commit/22a5f59))
- CLI startup: silenced `dev.hardwood VectorSupport` INFO log ([57a5a38](https://github.com/dfa1/vortex-java/commit/57a5a38))

### Removed

- `vortex-reader` dependency from `vortex-parquet` ([eca40f4](https://github.com/dfa1/vortex-java/commit/eca40f4))
- `com.google.protobuf:protobuf-java` — CLI jar 14 MB → 12 MB; JDK 25 Unsafe warning gone ([743278d](https://github.com/dfa1/vortex-java/commit/743278d))
- `protoc` build dependency ([743278d](https://github.com/dfa1/vortex-java/commit/743278d))

### Compatibility

1046 unit + 248 integration tests, JDK 25 (2 skipped — UUID cross-compat blocked on vortex-jni 0.74.0).

### Performance

- `ProtoWriter.varintSize` branchless via `Integer.numberOfLeadingZeros` ([42177ca](https://github.com/dfa1/vortex-java/commit/42177ca))
- `ProtoWriter` backpatched length-delim writes eliminate per-message temp allocation ([c79611e](https://github.com/dfa1/vortex-java/commit/c79611e))

### Documentation

- Compatibility doc bumped to Rust reference v0.74.0 ([cf73887](https://github.com/dfa1/vortex-java/commit/cf73887))

[0.6.0]: https://github.com/dfa1/vortex-java/compare/v0.5.0...v0.6.0

## [0.5.0] — 2026-06-09

The headline themes are an **interactive inspector TUI** for navigating Vortex files
(extracted as a dedicated `vortex-inspector` module), full **Vortex extension type
decode** (date, time, timestamp, uuid, decimal), and a **scan API rewrite** that
replaces the silent `hasNext()` arena-closing footgun with closeable `Chunk` objects.

### Added

- **Interactive TUI inspector** (`vortex-inspector` module + `tui` CLI subcommand).
  Lazy-loaded layout tree with stats, dictionary entries, hex previews, and decoded
  data; works against local files and `http(s)://` URLs. FFM-based ANSI terminal
  driver — no Lanterna dependency. Documented in `docs/how-to.md#inspect-interactively-tui`.
  ([aa7561f](https://github.com/dfa1/vortex-java/commit/aa7561f),
  [397b64a](https://github.com/dfa1/vortex-java/commit/397b64a),
  [d4cd0bc](https://github.com/dfa1/vortex-java/commit/d4cd0bc),
  [8dae240](https://github.com/dfa1/vortex-java/commit/8dae240),
  [00452e4](https://github.com/dfa1/vortex-java/commit/00452e4),
  [7a51165](https://github.com/dfa1/vortex-java/commit/7a51165),
  [e8db30a](https://github.com/dfa1/vortex-java/commit/e8db30a),
  [a43f340](https://github.com/dfa1/vortex-java/commit/a43f340))
- **Extension type decode** — `vortex.date` → `LocalDate`, `vortex.time` → `LocalTime`,
  `vortex.timestamp` → `Instant` / `ZonedDateTime`, `vortex.uuid` → `UUID`. Routed
  through a new `Extension` sealed hierarchy on `DType.Extension`. See
  `docs/compatibility.md` for the coverage matrix.
  ([4963aa9](https://github.com/dfa1/vortex-java/commit/4963aa9),
  [ca8d687](https://github.com/dfa1/vortex-java/commit/ca8d687),
  [99417ad](https://github.com/dfa1/vortex-java/commit/99417ad),
  [9da2a78](https://github.com/dfa1/vortex-java/commit/9da2a78),
  [175ad07](https://github.com/dfa1/vortex-java/commit/175ad07))
- **Decimal decode** — `GenericArray.getDecimal` supports the `decimal_byte_parts`
  shape, including i128 (precision > 18). Width 1/2/4/8 reads stay allocation-free.
  ([23d5019](https://github.com/dfa1/vortex-java/commit/23d5019),
  [4735324](https://github.com/dfa1/vortex-java/commit/4735324),
  [ff20a24](https://github.com/dfa1/vortex-java/commit/ff20a24),
  [f4ae8c0](https://github.com/dfa1/vortex-java/commit/f4ae8c0))
- **CLI uber-jar deployed to Maven Central** under classifier `all`
  (`io.github.dfa1.vortex:vortex-cli:0.5.0:jar:all`). Useful when the consumer
  environment can't clone from GitHub. The manifest sets `Enable-Native-Access` so
  FFM downcalls work without the JVM flag.
  ([3e2c552](https://github.com/dfa1/vortex-java/commit/3e2c552),
  [cfc5cc8](https://github.com/dfa1/vortex-java/commit/cfc5cc8))
- **Writer: global dictionary for low-cardinality `Utf8`** — columns with ≤ 256
  distinct values across chunks are now emitted as a shared `vortex.dict` layout.
  ([b4d1b43](https://github.com/dfa1/vortex-java/commit/b4d1b43))
- **CI: Windows runs** for the inspector module.
  ([a9c9d4e](https://github.com/dfa1/vortex-java/commit/a9c9d4e))

### Changed

- **Breaking — scan API lifecycle.** `ScanIterator` now implements
  `Iterator<Chunk>`. `next()` returns a `Chunk` that the caller must close
  (try-with-resources); `hasNext()` is side-effect-free. Calling `next()` while a
  prior `Chunk` is still open throws `IllegalStateException`. This removes the
  previous footgun where `iter.hasNext()` silently closed the previous chunk's
  arena, invalidating any `Array` references the caller still held. Use after
  `close()` raises FFM's scope check (`IllegalStateException`) instead of returning
  undefined data. See the updated examples in `README.md` and
  `docs/explanation.md#memory-model`.
  ([b45fd98](https://github.com/dfa1/vortex-java/commit/b45fd98))
- **Breaking — `EncodingRegistry` is immutable.** Register via the new builder:
  `EncodingRegistry.builder().registerServiceLoaded().register(myEncoding).build()`.
  ([64ffbaa](https://github.com/dfa1/vortex-java/commit/64ffbaa))
- **Breaking — `inspect` split into `inspect` (text) + `tui` (interactive).**
  Previous `inspect <file>` behaviour stays on `inspect`; interactive use is now
  on the dedicated `tui` subcommand.
  ([e8db30a](https://github.com/dfa1/vortex-java/commit/e8db30a))
- **`Extension` sealed hierarchy** replaces the prior `Extensions` utility class.
  ([175ad07](https://github.com/dfa1/vortex-java/commit/175ad07))
- CLI errors always print the exception class + cause chain — `VORTEX_DEBUG`
  environment variable removed.
  ([6a4464b](https://github.com/dfa1/vortex-java/commit/6a4464b),
  [f2f85bd](https://github.com/dfa1/vortex-java/commit/f2f85bd))

### Performance

- **Bitpacked unpack** — per-row bookkeeping hoisted out of the inner block loop
  in `unpackLoop8/16/32/64`. Measurable win on bitpacked scan benchmarks.
  ([ab3ca3f](https://github.com/dfa1/vortex-java/commit/ab3ca3f),
  [ad8a64d](https://github.com/dfa1/vortex-java/commit/ad8a64d))
- **Broadcast modulo branch-split** — ALP + Dict hot paths gate the
  `ConstantEncoding` broadcast modulo behind a cheap `cap == n` check, restoring
  C2 vectorization on the common path. ~5–10× recovery on the regressed scans.
  ([442021f](https://github.com/dfa1/vortex-java/commit/442021f))
- **Scan fast-path on non-broadcast reads** — recovers ~25% on bitpacked scans
  by skipping the broadcast capacity check when not needed.
  ([051a794](https://github.com/dfa1/vortex-java/commit/051a794))
- **`GenericArray.getDecimal`** — width 1/2/4/8 reads stay allocation-free.
  ([f4ae8c0](https://github.com/dfa1/vortex-java/commit/f4ae8c0))

### Fixed

- **Decimal element width** is derived from the buffer size, not the declared
  precision — fixes round-trip with the Rust reference implementation for
  oversized declared precisions.
  ([c798e95](https://github.com/dfa1/vortex-java/commit/c798e95))
- **`Extensions.localDate` bounds-check** — rejects out-of-range storage values.
  ([a7eab37](https://github.com/dfa1/vortex-java/commit/a7eab37))
- **`GenericArray.getDecimal`** rejects null cells in the mantissa path.
  ([5198115](https://github.com/dfa1/vortex-java/commit/5198115))
- **TUI thread safety** — `Layout.metadata` byte reads run on the I/O worker
  thread that owns the handle. `InspectorTree.Node` uses identity equality so
  duplicate subtrees don't collapse.
  ([6c732de](https://github.com/dfa1/vortex-java/commit/6c732de),
  [0cc1137](https://github.com/dfa1/vortex-java/commit/0cc1137),
  [a47b6fd](https://github.com/dfa1/vortex-java/commit/a47b6fd))
- **`InspectorTree`** — `vortex.date` columns format using the declared dtype;
  TUI data scan no longer applies `withLimit` (was rejecting `GenericArray`).
  ([0e749df](https://github.com/dfa1/vortex-java/commit/0e749df),
  [b5ce1d6](https://github.com/dfa1/vortex-java/commit/b5ce1d6))
- **`ScanIterator.truncateArray`** now supports `GenericArray`; decimals format
  correctly in the TUI.
  ([f09f564](https://github.com/dfa1/vortex-java/commit/f09f564))
- **CLI** prints the exception class + full cause chain on `inspect` errors.
  ([f2f85bd](https://github.com/dfa1/vortex-java/commit/f2f85bd))

### Removed

- `ScanResult` — renamed to `Chunk` and given lifecycle methods. Update imports:
  `io.github.dfa1.vortex.scan.ScanResult` → `io.github.dfa1.vortex.scan.Chunk`.
  ([b45fd98](https://github.com/dfa1/vortex-java/commit/b45fd98))
- `Extensions` utility class — replaced by the `Extension` sealed hierarchy.
  ([175ad07](https://github.com/dfa1/vortex-java/commit/175ad07))
- `Extension.Time#unit` / `Extension.Timestamp#unit` accessors (unused).
  ([2fcb311](https://github.com/dfa1/vortex-java/commit/2fcb311))
- `VORTEX_DEBUG` env-var gate — stack traces are always printed on CLI error.
  ([6a4464b](https://github.com/dfa1/vortex-java/commit/6a4464b))
- Lanterna dependency — replaced by an FFM-based ANSI terminal in
  `cli/src/main/java/.../tui/term`.
  ([397b64a](https://github.com/dfa1/vortex-java/commit/397b64a))

### Refactored

- **`Trailer` parser** extracted, shared by `VortexReader` (mmap) and
  `VortexHttpReader` (range-request) paths.
  ([1ac6f5a](https://github.com/dfa1/vortex-java/commit/1ac6f5a))
- **`VortexHttpReader`** allocates its own `Arena` in the constructor and reuses
  a single `HttpClient`.
  ([e18dafd](https://github.com/dfa1/vortex-java/commit/e18dafd))
- **Inspector module** carved out of `cli`; TUI + `IoWorker` + terminal code
  later moved back into `cli` (only the `inspect` package stayed in
  `vortex-inspector`).
  ([aa7561f](https://github.com/dfa1/vortex-java/commit/aa7561f),
  [77baff1](https://github.com/dfa1/vortex-java/commit/77baff1))
- Documentation: layout section expanded (node types, encoding namespaces,
  pruning); on-disk file-layout diagram in `explanation.md`.
  ([e696f07](https://github.com/dfa1/vortex-java/commit/e696f07),
  [5c2b410](https://github.com/dfa1/vortex-java/commit/5c2b410))

[0.5.0]: https://github.com/dfa1/vortex-java/compare/v0.4.0...v0.5.0

## [0.4.0] — 2026-06-07

The headline themes for this release are a **security-hardening sweep** of the file-format
parser, a **public-API cleanup** of the `Array` hierarchy (the heap-allocated `buffer(int)` /
`segment()` accessors are gone from the interface), and **cascading writer features** that
close the compression gap with the Rust reference implementation on real-world workloads.

### Security

Every malformed input now surfaces as `VortexException` rather than a JDK exception
(`IndexOutOfBoundsException`, `ArrayIndexOutOfBoundsException`, `StackOverflowError`, raw
FlatBuffer/Protobuf runtime exceptions). Regression suite lives under
`reader/src/test/java/.../*SecurityTest`.

- **Zip-bomb protection** — `ConstantEncoding` and dict-layout decode no longer pre-allocate
  `O(rowCount)` memory; a 150-byte crafted file claiming 10⁹ rows is now constant-cost.
  ([10a7776](https://github.com/dfa1/vortex-java/commit/10a7776))
- **Trailer + postscript validation** — `VortexReader` and `VortexHttpReader` reject unknown
  file `version`, `postscriptLen == 0`, and `postscriptLen > fileSize - 8`. Footer/layout/dtype
  blob offsets and `Layout.encoding` index are bounds-checked at parse time.
  ([f8f89fe](https://github.com/dfa1/vortex-java/commit/f8f89fe))
- **Footer `segmentSpecs` bounds** — every spec is validated against `fileSize` the moment
  the footer is materialised, eliminating later `IndexOutOfBoundsException` on
  `MemorySegment.asSlice`.
  ([03845ac](https://github.com/dfa1/vortex-java/commit/03845ac))
- **PType ordinal bounds-check** — `PType.fromOrdinal(int)` replaces all 22 `PType.values()[idx]`
  call sites across encodings; crafted Protobuf ptype fields are rejected up front.
  ([b4988c3](https://github.com/dfa1/vortex-java/commit/b4988c3))
- **Layout-tree depth cap** — `PostscriptParser.convertLayout` is capped at depth 64,
  preventing both unbounded nesting and self-referential FlatBuffer cycles (a ~120-byte
  cycle attack previously triggered `StackOverflowError`).
  ([29adbe0](https://github.com/dfa1/vortex-java/commit/29adbe0))
- **Layout metadata size cap** — per-layout `metadataAsByteBuffer()` is capped at 4 MiB
  (above any real encoding's footprint; FSST's symbol table is the largest at ~32 KiB).
  ([ebbe644](https://github.com/dfa1/vortex-java/commit/ebbe644))
- **Decimal field validation** — `DType.Decimal` is rejected unless `precision ∈ [1, 38]`
  and `scale ∈ [0, precision]`, matching IEEE 754-2008 decimal128.
  ([ebbe644](https://github.com/dfa1/vortex-java/commit/ebbe644))
- **`readFlatStats` bounds-check** — zone-map stats reads now validate the trailing
  little-endian `fbLen` field against the segment size, returning empty stats on malformed
  input rather than throwing `IndexOutOfBoundsException` from `MemorySegment.asSlice`.
  ([ebbe644](https://github.com/dfa1/vortex-java/commit/ebbe644))

### Added

- **`vortex.sequence` F16 encode/decode** — half-precision floats now round-trip through the
  sequence encoding.
  ([7b3d7a9](https://github.com/dfa1/vortex-java/commit/7b3d7a9))
- **Writer: cascading with global dict layout** — low-cardinality columns (≤ 256 distinct
  values in the chunk sample) are now emitted as a `vortex.dict` layout, with the dict
  candidate detection tightened to avoid false positives.
  ([53b2a19](https://github.com/dfa1/vortex-java/commit/53b2a19),
  [d383765](https://github.com/dfa1/vortex-java/commit/d383765))
- **Writer: opt-in Zstd compression** — `WriteOptions.withZstd(boolean)` exposes the
  size/throughput trade-off. Off by default; turn on for archival workloads.
  ([ea10d37](https://github.com/dfa1/vortex-java/commit/ea10d37))
- **`Encoding.decodeSegment` extension point** — added as part of the typed-segment migration
  (see *Changed* below). Provides a typed alternative to `Array.segment()`.
  ([ed4a0ae](https://github.com/dfa1/vortex-java/commit/ed4a0ae))
- **`DecodeContext.decodeChild(int, DType, long)`** — typed child-decode helper that replaces
  the per-encoding `decodeChildAs(...)` private utilities.
  ([d07faf0](https://github.com/dfa1/vortex-java/commit/d07faf0),
  [a1512da](https://github.com/dfa1/vortex-java/commit/a1512da))
- **Typed accessors on concrete array types** — `LongArray.segment()`, `VarBinArray.offsetsSegment()`,
  `MaskedArray.inner()`, and friends now live on the concrete types where they fit cleanly,
  rather than on the `Array` interface.
  ([84a34f4](https://github.com/dfa1/vortex-java/commit/84a34f4))
- **`*SecurityTest` test-naming convention** — adversarial / robustness tests are now grouped
  under the `*SecurityTest` suffix, mirroring the existing `*IntegrationTest` convention.
  Run with `./mvnw test -Dtest='*SecurityTest'`.
  ([76ba67c](https://github.com/dfa1/vortex-java/commit/76ba67c))
- **`FlatSegmentDecoder`** — extracted from `EncodingRegistry`; the registry is now pure
  dispatch.
  ([4a08356](https://github.com/dfa1/vortex-java/commit/4a08356))

### Changed

- **`Array` interface slimmed down.** `buffer(int)`, `child(int)`, and `segment()` are no
  longer part of the `Array` interface; consumers should use the typed accessors on the
  concrete subtype (e.g. `LongArray.segment()`) or `ArraySegments.of(arr)` for a generic
  fallback. `buffer(int)` is now package-private on the concrete array types.
  ([bdb4e7d](https://github.com/dfa1/vortex-java/commit/bdb4e7d),
  [1283168](https://github.com/dfa1/vortex-java/commit/1283168),
  [ba5957c](https://github.com/dfa1/vortex-java/commit/ba5957c),
  [df6ab3f](https://github.com/dfa1/vortex-java/commit/df6ab3f),
  [bb7b656](https://github.com/dfa1/vortex-java/commit/bb7b656))
- **`VarBinArray`** no longer keeps a redundant `offsetsArr` field; consumers read offsets
  via `offsetsSegment()`.
  ([96687f8](https://github.com/dfa1/vortex-java/commit/96687f8))
- **`ArrayStats`** is no longer eagerly stored on decoded array types; statistics are now
  read on demand from the FlatBuffer node, matching the Rust reference implementation.
  ([9237e28](https://github.com/dfa1/vortex-java/commit/9237e28))

### Fixed

- **`MaskedArray.segment()`** delegates correctly to its inner array (regression introduced
  during the typed-accessor migration).
  ([8a16119](https://github.com/dfa1/vortex-java/commit/8a16119))
- **Constant-encoded array indexing** broadcasts the index correctly when scanning multiple
  rows from a single stored value.
  ([ed658b7](https://github.com/dfa1/vortex-java/commit/ed658b7))
- **Performance benchmark** (`RustWritesJavaReadsBigFileBenchmark`) migrated off the removed
  `Array.buffer(int)` accessor, unblocking `./mvnw verify` and `./bench`.
  ([977a529](https://github.com/dfa1/vortex-java/commit/977a529))

### Removed

- `Array.buffer(int)`, `Array.child(int)`, and `Array.segment()` from the public `Array`
  interface (see *Changed*). Callers should migrate to the concrete-type accessors or
  `ArraySegments.of(arr)`.
  ([bdb4e7d](https://github.com/dfa1/vortex-java/commit/bdb4e7d),
  [1283168](https://github.com/dfa1/vortex-java/commit/1283168))
- `Encoding.decodeSegment(...)` is removed after the migration to `DecodeContext.decodeChild`.
  ([977a529](https://github.com/dfa1/vortex-java/commit/977a529))
- `ArrayStats` field on decoded array types (statistics are now lazy).
  ([9237e28](https://github.com/dfa1/vortex-java/commit/9237e28))

### Documentation

- Added `CONTRIBUTING.md` covering trunk-based workflow, commit conventions, and the
  three-touch-point rule for adding encodings.
  ([d9825cf](https://github.com/dfa1/vortex-java/commit/d9825cf))
- Added an internal-architecture diagram set covering the file format, layout tree, and
  scan path.
  ([75f4cea](https://github.com/dfa1/vortex-java/commit/75f4cea))
- Added a "Vortex vs Parquet" comparison section to the README.
  ([886bb80](https://github.com/dfa1/vortex-java/commit/886bb80))
- Expanded the `## Security` section in `TODO.md` with the open hardening roadmap (resource
  caps, per-encoding adversarial tests, Jazzer fuzz harness, OSS-Fuzz submission).
  ([1bb1465](https://github.com/dfa1/vortex-java/commit/1bb1465))

### Build & Tooling

- **Dependabot enabled** for Maven and GitHub Actions.
  ([dd118a7](https://github.com/dfa1/vortex-java/commit/dd118a7))
- Numerous dependency bumps: JUnit Jupiter (5.11.4 → 6.1.0, tests now require JUnit 6),
  Mockito, FastCSV (3 → 4), H2 (2.3 → 2.4), Checkstyle, Zstd-JNI,
  maven-compiler/surefire/failsafe/javadoc/source/shade/gpg/antrun/exec/build-helper plugins,
  `actions/checkout` (4 → 6), `actions/setup-java` (4 → 5), `actions/cache` (4 → 5),
  Sonatype central-publishing plugin.
  ([81be668](https://github.com/dfa1/vortex-java/commit/81be668),
  [2c8319f](https://github.com/dfa1/vortex-java/commit/2c8319f),
  [f6dbcf1](https://github.com/dfa1/vortex-java/commit/f6dbcf1),
  [dfabd8c](https://github.com/dfa1/vortex-java/commit/dfabd8c),
  [7b4a718](https://github.com/dfa1/vortex-java/commit/7b4a718),
  [fb9b404](https://github.com/dfa1/vortex-java/commit/fb9b404),
  [7b67000](https://github.com/dfa1/vortex-java/commit/7b67000),
  [fd1ea7e](https://github.com/dfa1/vortex-java/commit/fd1ea7e))
- `pom.xml` files now group dependencies under `production` / `testing` comment sections
  with a consistent project-internal-first ordering.
  ([bcbbbfd](https://github.com/dfa1/vortex-java/commit/bcbbbfd))
- Checkstyle scope tightened to exclude generated `fbs`/`proto` packages.
  ([f5ab433](https://github.com/dfa1/vortex-java/commit/f5ab433))

[0.4.0]: https://github.com/dfa1/vortex-java/compare/v0.3.2...v0.4.0
