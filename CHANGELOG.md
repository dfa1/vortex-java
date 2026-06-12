# Changelog

All notable changes to **vortex-java** are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.6.0] — Unreleased

Three headline themes. The **proto-rewrite** drops `protobuf-java` in favour of an in-tree
MemorySegment-native proto3 codec, generated from `.proto` schemas by a new `proto-gen`
module — CLI uber-jar shrinks ~14% and the JDK 25 `sun.misc.Unsafe` stderr warning is
gone. The **Extension API redesign** splits extension handling into `ExtensionDecoder`
(reader) and `ExtensionEncoder` (writer), adds writer auto-route from domain collections
(`List<LocalDate>`, `List<UUID>`, ...), UUID extension support, JDBC import for SQL
DATE/TIME/TIMESTAMP/UUID, nullable column round-trip, and `Chunk.as(name, Class)` typed
access. The **module boundary cleanup** moves all decode-output types (`Array` and
subclasses) to `reader.array`, all writer-only data holders to `writer.encode`, and
decode utilities to `reader.decode` — a writer process now pulls in only `writer + core`.

### Added

- **`proto-gen` module** — build-time `.proto` to Java code generator. Lexer + parser +
  type registry + emitter. Outputs one immutable Java `record` per message and one Java
  `enum` per proto enum, each carrying a `@Generated("io.github.dfa1.vortex.protogen.CodeGen")`
  annotation. Records expose `decode(MemorySegment, long, long)` static factories and
  `encode()` instance methods that operate directly on a memory segment — zero `byte[]`
  copy, no `protobuf-java` runtime. (ae6c46a, 743278d, b527f84)
- **`ProtoReader` / `ProtoWriter`** — package-private proto3 wire-format primitives
  under `io.github.dfa1.vortex.proto`. Reads varint / sint64 / fixed32 / fixed64 /
  length-delimited / packed-repeated payloads, with bounds checks and a 10-byte cap on
  varint length. 42 unit tests cover happy path + truncation + bounds. (ae6c46a, b527f84)
- **Oneof factories** on generated records (e.g. `ScalarValue.ofInt64Value(123L)`) —
  avoids the 11-arg constructor for `ScalarValue`'s oneof. (b527f84)
- **`PatchedMetadata` / `VariantMetadata`** — added to `encodings.proto`. Previously
  hand-parsed with `CodedInputStream`; now go through the generated record path. (743278d, b527f84)
- **Nullable extension columns** — `vortex.date`, `vortex.time`, `vortex.timestamp`,
  `vortex.uuid` round-trip null elements via the `ExtEncoding → MaskedEncoding → primitive`
  layout. New `NullableData(values, validity)` carrier feeds the writer; `MaskedEncoding.encode`
  splits storage + Bool validity child. `JdbcImporter` preserves SQL NULL end-to-end. (1015f9b)
- **Null-preserving `decodeAll`** — `DateExtension`, `TimeExtension`, `TimestampExtension`,
  `UuidExtension` `decodeAll(MaskedArray)` yields `List<T>` with `null` at invalid positions
  instead of throwing on `epochInteger`. (24c64a9)
- **Extension SPI** — `Extension` interface + `ExtensionId` enum, ServiceLoader-discovered,
  registered on `Registry`. Third-party extensions register via the builder. (1af6f2a, 0d3815f, 834d2f1)
- **Spec extension impls** — `DateExtension`, `TimeExtension`, `TimestampExtension`,
  `UuidExtension`. Each carries `encode`, `decode`, `decodeAll`, polymorphic
  `encodeAll(DType.Extension, Collection<?>)` for writer auto-routing. (0d3815f, bba49c7)
- **Writer auto-route extension columns** — `writeChunk` accepts domain collections
  (`List<LocalDate>`, `List<Instant>`, `List<UUID>`, ...) and routes through the matching
  extension impl to produce `int[]` / `long[]` / `FixedSizeListData` storage. (1d54b57, bd6dbdc, 75d7b4b)
- **`vortex.uuid` extension** — `FixedSizeList(U8, 16)` storage, big-endian byte layout.
  Writer + reader + JDBC vendor-type-name detection (PostgreSQL `java.util.UUID`, H2
  `BINARY(16)`, others via canonical string). (89a0a69, cce2d2d)
- **JDBC import: extension types** — `Types.DATE` / `Types.TIME` / `Types.TIMESTAMP` /
  vendor UUID columns map to `vortex.date` / `.time` / `.timestamp` / `.uuid`. (9f31d9e, cce2d2d)
- **`Chunk.as(name, Class)`** — typed extension access without manual `Extension.findKnown`
  + `decodeAll` boilerplate. (e5cefb0)
- **ExtEncoding storage cascade-compress** — storage child goes through the cascading
  compressor (`FoR` / `Bitpacked` / `ALP` / `RLE` / etc.) instead of bare `Primitive`. (33cf42e)

### Breaking

- **`EncodingRegistry` → `ReadRegistry`** — renamed and moved to `io.github.dfa1.vortex.reader`.
  Holds `EncodingDecoder` and `ExtensionDecoder` impls only (read side). ServiceLoader
  discovery via `registerServiceLoaded()` on the builder. (834d2f1, 2272fe4, a560563)
- **`core.Extension` sealed hierarchy retired** — replaced by `ExtensionDecoder`
  (`io.github.dfa1.vortex.reader.ExtensionDecoder`) and `ExtensionEncoder`
  (`io.github.dfa1.vortex.writer.ExtensionEncoder`). Register each independently with
  `ReadRegistry` / `WriteRegistry`. (2a0ed93, a560563)
- **`core.array.*` → `reader.array.*`** — all `Array` subtypes moved to
  `io.github.dfa1.vortex.reader.array`. Update import paths. (286715c)
- **`core.array.NullableData` → `writer.encode.NullableData`** — writer-side carrier
  moved to `io.github.dfa1.vortex.writer.encode`. (286715c)
- **Decode utilities moved to `reader.decode`** — `LeBitReader`, `PcoBin`,
  `PcoTansDecoder`, `SegmentBroadcast` moved from `core.encoding` to
  `io.github.dfa1.vortex.reader.decode`. (d514435)
- **Encode data holders moved to `writer.encode`** — `ChunkedData`, `DateTimePartsData`,
  `FixedSizeListData`, `ListData`, `ListViewData`, `StructData` moved from `core.encoding`
  to `io.github.dfa1.vortex.writer.encode`. (d514435)
- **Reader unwrap path removed** — `ExtEncoding` wraps + unwraps the storage child
  uniformly; the previous one-off unwrap shortcut in the registry is gone. (4d4ab34, 75d7b4b)

### Changed

- **Build-time tooling**: `regenerate-sources` profile no longer shells out to `protoc`.
  Run `./mvnw compile -pl proto-gen` once, then
  `./mvnw generate-sources -pl core -P regenerate-sources`. `brew install protobuf` is
  no longer needed for normal development. (743278d)
- **Encoding consumers**: 25 encoding classes (`ALP`, `Bitpacked`, `Dict`, `Rle`,
  `Sparse`, `Sequence`, etc.) and 23 test files rewritten to use the new record API.
  Constructor calls are positional; field accessors follow proto3 snake_case
  (`meta.bit_width()`, not `meta.getBitWidth()`). (0132417, 68be6fc, 743278d)

### Fixed

- **`PostscriptParser`**: extension dtype `nullable` was hardcoded `false` on read; now
  derived from the storage dtype, matching the Rust spec (`ext_dtype.storage_dtype()`
  carries the column nullability). (1015f9b)
- **`DType.Extension.metadata`** capped at 64 KiB during parse — prevents crafted
  extension metadata from inflating memory on hostile input. (22a5f59)
- **CLI startup**: silenced `dev.hardwood VectorSupport` INFO log on every cold start. (57a5a38)

### Removed

- **`com.google.protobuf:protobuf-java`** dependency dropped from `core`, `reader`,
  `writer`, and root `dependencyManagement`. The `protobuf.version` property is gone.
  CLI uber-jar: **14 MB → 12 MB**. JDK 25 `sun.misc.Unsafe::arrayBaseOffset` stderr
  warning emitted by `UnsafeUtil` on every cold start: **gone**. (743278d)
- `protoc` no longer required by the build. `brew install flatbuffers` covers `.fbs`
  edits; `.proto` edits use the in-process generator. (743278d)

### Compatibility

Wire-format compatibility with the Rust reference implementation is unchanged and is
verified by the full integration suite:

- `RustWritesJavaReadsIntegrationTest` (10 tests) — Rust writes, Java reads
- `JavaWritesRustReadsIntegrationTest` (194 tests) — Java writes, JNI reads
- `RustJavaReaderComparisonIntegrationTest` (25 tests) — both readers, same file
- `ParquetImportIntegrationTest` (5 tests) — round-trip through ParquetImporter

All 872 unit + 243 integration tests pass on JDK 25.

### Performance

No measurable change on bulk-read benchmarks (`RustVsJavaReadBenchmark.javaReadCascading`
within 1% of main, stdev ±2 ops/s). Proto metadata parse is < 1% of work on multi-million-row
scans; the win is architectural, not throughput.

- **`ProtoWriter.varintSize`** — branchless via `Integer.numberOfLeadingZeros` (~3 cycles
  vs 4-branch cascade). Hot on every length-delimited write. (42177ca)
- **`ProtoWriter` backpatched length-delim writes** — eliminate the temp `ProtoWriter`
  allocation per nested message. (c79611e)

### Documentation

- Compatibility doc bumped to Rust reference v0.74.0; Union / onpair / Variant gaps
  documented. (cf73887)

[0.6.0]: https://github.com/dfa1/vortex-java/compare/v0.5.0...main

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
- **Extension type decode** — `vortex.date` → `LocalDate`, `vortex.time` → `LocalTime`,
  `vortex.timestamp` → `Instant` / `ZonedDateTime`, `vortex.uuid` → `UUID`. Routed
  through a new `Extension` sealed hierarchy on `DType.Extension`. See
  `docs/compatibility.md` for the coverage matrix.
- **Decimal decode** — `GenericArray.getDecimal` supports the `decimal_byte_parts`
  shape, including i128 (precision > 18). Width 1/2/4/8 reads stay allocation-free.
- **CLI uber-jar deployed to Maven Central** under classifier `all`
  (`io.github.dfa1.vortex:vortex-cli:0.5.0:jar:all`). Useful when the consumer
  environment can't clone from GitHub. The manifest sets `Enable-Native-Access` so
  FFM downcalls work without the JVM flag.
- **Writer: global dictionary for low-cardinality `Utf8`** — columns with ≤ 256
  distinct values across chunks are now emitted as a shared `vortex.dict` layout.
- **CI: Windows runs** for the inspector module.

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
- **Breaking — `EncodingRegistry` is immutable.** Register via the new builder:
  `EncodingRegistry.builder().registerServiceLoaded().register(myEncoding).build()`.
- **Breaking — `inspect` split into `inspect` (text) + `tui` (interactive).**
  Previous `inspect <file>` behaviour stays on `inspect`; interactive use is now
  on the dedicated `tui` subcommand.
- **`Extension` sealed hierarchy** replaces the prior `Extensions` utility class.
- CLI errors always print the exception class + cause chain — `VORTEX_DEBUG`
  environment variable removed.

### Performance

- **Bitpacked unpack** — per-row bookkeeping hoisted out of the inner block loop
  in `unpackLoop8/16/32/64`. Measurable win on bitpacked scan benchmarks.
- **Broadcast modulo branch-split** — ALP + Dict hot paths gate the
  `ConstantEncoding` broadcast modulo behind a cheap `cap == n` check, restoring
  C2 vectorization on the common path. ~5–10× recovery on the regressed scans.
- **Scan fast-path on non-broadcast reads** — recovers ~25% on bitpacked scans
  by skipping the broadcast capacity check when not needed.
- **`GenericArray.getDecimal`** — width 1/2/4/8 reads stay allocation-free.

### Fixed

- **Decimal element width** is derived from the buffer size, not the declared
  precision — fixes round-trip with the Rust reference implementation for
  oversized declared precisions.
- **`Extensions.localDate` bounds-check** — rejects out-of-range storage values.
- **`GenericArray.getDecimal`** rejects null cells in the mantissa path.
- **TUI thread safety** — `Layout.metadata` byte reads run on the I/O worker
  thread that owns the handle. `InspectorTree.Node` uses identity equality so
  duplicate subtrees don't collapse.
- **`InspectorTree`** — `vortex.date` columns format using the declared dtype;
  TUI data scan no longer applies `withLimit` (was rejecting `GenericArray`).
- **`ScanIterator.truncateArray`** now supports `GenericArray`; decimals format
  correctly in the TUI.
- **CLI** prints the exception class + full cause chain on `inspect` errors.

### Removed

- `ScanResult` — renamed to `Chunk` and given lifecycle methods. Update imports:
  `io.github.dfa1.vortex.scan.ScanResult` → `io.github.dfa1.vortex.scan.Chunk`.
- `Extensions` utility class — replaced by the `Extension` sealed hierarchy.
- `Extension.Time#unit` / `Extension.Timestamp#unit` accessors (unused).
- `VORTEX_DEBUG` env-var gate — stack traces are always printed on CLI error.
- Lanterna dependency — replaced by an FFM-based ANSI terminal in
  `cli/src/main/java/.../tui/term`.

### Refactored

- **`Trailer` parser** extracted, shared by `VortexReader` (mmap) and
  `VortexHttpReader` (range-request) paths.
- **`VortexHttpReader`** allocates its own `Arena` in the constructor and reuses
  a single `HttpClient`.
- **Inspector module** carved out of `cli`; TUI + `IoWorker` + terminal code
  later moved back into `cli` (only the `inspect` package stayed in
  `vortex-inspector`).
- Documentation: layout section expanded (node types, encoding namespaces,
  pruning); on-disk file-layout diagram in `explanation.md`.

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
- **Trailer + postscript validation** — `VortexReader` and `VortexHttpReader` reject unknown
  file `version`, `postscriptLen == 0`, and `postscriptLen > fileSize - 8`. Footer/layout/dtype
  blob offsets and `Layout.encoding` index are bounds-checked at parse time.
- **Footer `segmentSpecs` bounds** — every spec is validated against `fileSize` the moment
  the footer is materialised, eliminating later `IndexOutOfBoundsException` on
  `MemorySegment.asSlice`.
- **PType ordinal bounds-check** — `PType.fromOrdinal(int)` replaces all 22 `PType.values()[idx]`
  call sites across encodings; crafted Protobuf ptype fields are rejected up front.
- **Layout-tree depth cap** — `PostscriptParser.convertLayout` is capped at depth 64,
  preventing both unbounded nesting and self-referential FlatBuffer cycles (a ~120-byte
  cycle attack previously triggered `StackOverflowError`).
- **Layout metadata size cap** — per-layout `metadataAsByteBuffer()` is capped at 4 MiB
  (above any real encoding's footprint; FSST's symbol table is the largest at ~32 KiB).
- **Decimal field validation** — `DType.Decimal` is rejected unless `precision ∈ [1, 38]`
  and `scale ∈ [0, precision]`, matching IEEE 754-2008 decimal128.
- **`readFlatStats` bounds-check** — zone-map stats reads now validate the trailing
  little-endian `fbLen` field against the segment size, returning empty stats on malformed
  input rather than throwing `IndexOutOfBoundsException` from `MemorySegment.asSlice`.

### Added

- **`vortex.sequence` F16 encode/decode** — half-precision floats now round-trip through the
  sequence encoding.
- **Writer: cascading with global dict layout** — low-cardinality columns (≤ 256 distinct
  values in the chunk sample) are now emitted as a `vortex.dict` layout, with the dict
  candidate detection tightened to avoid false positives.
- **Writer: opt-in Zstd compression** — `WriteOptions.withZstd(boolean)` exposes the
  size/throughput trade-off. Off by default; turn on for archival workloads.
- **`Encoding.decodeSegment` extension point** — added as part of the typed-segment migration
  (see *Changed* below). Provides a typed alternative to `Array.segment()`.
- **`DecodeContext.decodeChild(int, DType, long)`** — typed child-decode helper that replaces
  the per-encoding `decodeChildAs(...)` private utilities.
- **Typed accessors on concrete array types** — `LongArray.segment()`, `VarBinArray.offsetsSegment()`,
  `MaskedArray.inner()`, and friends now live on the concrete types where they fit cleanly,
  rather than on the `Array` interface.
- **`*SecurityTest` test-naming convention** — adversarial / robustness tests are now grouped
  under the `*SecurityTest` suffix, mirroring the existing `*IntegrationTest` convention.
  Run with `./mvnw test -Dtest='*SecurityTest'`.
- **`FlatSegmentDecoder`** — extracted from `EncodingRegistry`; the registry is now pure
  dispatch.

### Changed

- **`Array` interface slimmed down.** `buffer(int)`, `child(int)`, and `segment()` are no
  longer part of the `Array` interface; consumers should use the typed accessors on the
  concrete subtype (e.g. `LongArray.segment()`) or `ArraySegments.of(arr)` for a generic
  fallback. `buffer(int)` is now package-private on the concrete array types.
- **`VarBinArray`** no longer keeps a redundant `offsetsArr` field; consumers read offsets
  via `offsetsSegment()`.
- **`ArrayStats`** is no longer eagerly stored on decoded array types; statistics are now
  read on demand from the FlatBuffer node, matching the Rust reference implementation.

### Fixed

- **`MaskedArray.segment()`** delegates correctly to its inner array (regression introduced
  during the typed-accessor migration).
- **Constant-encoded array indexing** broadcasts the index correctly when scanning multiple
  rows from a single stored value.
- **Performance benchmark** (`RustWritesJavaReadsBigFileBenchmark`) migrated off the removed
  `Array.buffer(int)` accessor, unblocking `./mvnw verify` and `./bench`.

### Removed

- `Array.buffer(int)`, `Array.child(int)`, and `Array.segment()` from the public `Array`
  interface (see *Changed*). Callers should migrate to the concrete-type accessors or
  `ArraySegments.of(arr)`.
- `Encoding.decodeSegment(...)` is removed after the migration to `DecodeContext.decodeChild`.
- `ArrayStats` field on decoded array types (statistics are now lazy).

### Documentation

- Added `CONTRIBUTING.md` covering trunk-based workflow, commit conventions, and the
  three-touch-point rule for adding encodings.
- Added an internal-architecture diagram set covering the file format, layout tree, and
  scan path.
- Added a "Vortex vs Parquet" comparison section to the README.
- Expanded the `## Security` section in `TODO.md` with the open hardening roadmap (resource
  caps, per-encoding adversarial tests, Jazzer fuzz harness, OSS-Fuzz submission).

### Build & Tooling

- **Dependabot enabled** for Maven and GitHub Actions.
- Numerous dependency bumps: JUnit Jupiter (5.11.4 → 6.1.0, tests now require JUnit 6),
  Mockito, FastCSV (3 → 4), H2 (2.3 → 2.4), Checkstyle, Zstd-JNI,
  maven-compiler/surefire/failsafe/javadoc/source/shade/gpg/antrun/exec/build-helper plugins,
  `actions/checkout` (4 → 6), `actions/setup-java` (4 → 5), `actions/cache` (4 → 5),
  Sonatype central-publishing plugin.
- `pom.xml` files now group dependencies under `production` / `testing` comment sections
  with a consistent project-internal-first ordering.
- Checkstyle scope tightened to exclude generated `fbs`/`proto` packages.

[0.4.0]: https://github.com/dfa1/vortex-java/compare/v0.3.2...v0.4.0
