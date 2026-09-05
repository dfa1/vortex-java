# Reference

API surface, CLI commands, and operator tables. Look here for "what exists and what it accepts."
For task-oriented usage see [how-to.md](how-to.md); for design rationale see [explanation.md](explanation.md).

- [Core types](#core-types)
- [Reader API](#reader-api)
- [Writer API](#writer-api)
- [Scan API](#scan-api)
- [Encoding registry](#encoding-registry)
- [FSST (`io.github.dfa1.vortex.fsst`)](#fsst-iogithubdfa1vortexfsst)
- [Parquet / CSV import and Parquet export](#parquet--csv-import-and-parquet-export)
- [CLI](#cli)
- [Encoding compatibility](compatibility.md)

---

## Core types

### `PType` (`io.github.dfa1.vortex.core.model.PType`)

Physical primitive type — wire-level numeric kind for a column.

| Constant                  | Bytes         | Notes                                    |
|---------------------------|---------------|------------------------------------------|
| `U8`, `U16`, `U32`, `U64` | 1 / 2 / 4 / 8 | Unsigned integers                        |
| `I8`, `I16`, `I32`, `I64` | 1 / 2 / 4 / 8 | Signed integers                          |
| `F16`                     | 2             | IEEE 754 half — decoded via `Float#float16ToFloat` |
| `F32`, `F64`              | 4 / 8         | IEEE 754 single / double                 |

Methods: `byteSize()`, `isFloating()`, `isSigned()`.

### `DType` (`io.github.dfa1.vortex.core.model.DType`)

Sealed logical type. All variants take a trailing `boolean nullable`.

Each `DType` variant is a record. Prefer the canonical constants / factories for
new code — the record constructors stay available for pattern matching and tests.

| Record                | Constant / factory                                     | Record constructor                                          |
|-----------------------|--------------------------------------------------------|-------------------------------------------------------------|
| `DType.Null`          | `DType.NULL`                                           | `new DType.Null(nullable)`                                  |
| `DType.Bool`          | `DType.BOOL`                                           | `new DType.Bool(nullable)`                                  |
| `DType.Primitive`     | `DType.I8` … `DType.I64`, `DType.U8` … `DType.U64`, `DType.F16`, `DType.F32`, `DType.F64` (constants) | `DType.I64.asNullable()` / `new DType.Primitive(PType, nullable)` |
| `DType.Decimal`       | `DType.decimal(precision, scale)`                      | `new DType.Decimal(precision, scale, nullable)`             |
| `DType.Utf8`          | `DType.UTF8`                                           | `new DType.Utf8(nullable)`                                  |
| `DType.Binary`        | `DType.BINARY`                                         | `new DType.Binary(nullable)`                                |
| `DType.Variant`       | `DType.VARIANT`                                        | `new DType.Variant(nullable)`                               |
| `DType.Struct`        | `DType.structBuilder().field(name, type)…build()`      | `new DType.Struct(fieldNames, fieldTypes, nullable)`        |
| `DType.List`          | —                                                      | `new DType.List(elementType, nullable)`                     |
| `DType.FixedSizeList` | —                                                      | `new DType.FixedSizeList(elementType, fixedSize, nullable)` |
| `DType.Map`           | —                                                      | `new DType.Map(keyType, valueType, keysSorted, nullable)`   |
| `DType.Extension`     | —                                                      | `new DType.Extension(id, storageDType, metadata, nullable)` |

Helpers: `nullable()` (boolean accessor on every record), `asNullable()` (fluent
shortcut returning a nullable copy), `withNullable(boolean)`, `DType.Struct.field(name)`,
`DType.Map.entriesDtype()` (the non-nullable `{key, value}` struct backing a map). A map's
`keyType` must be non-nullable — `new DType.Map(...)` throws `VortexException` otherwise.

---

### Identity types (`io.github.dfa1.vortex.core.model`)

| Type | Shape | Notes |
|------|-------|-------|
| `EncodingId` | `sealed interface` — `WellKnown` enum + `Custom` record | Array-encoding identity; total `parse(String)` over non-blank ids; constants re-exported (`EncodingId.VORTEX_PRIMITIVE`, …) |
| `LayoutId` | `sealed interface` — `WellKnown` enum + `Custom` record | Layout identity (separate namespace from encodings; `vortex.flat` is layout-only); both zoned aliases `vortex.zoned`/`vortex.stats` |
| `ColumnName` | `record ColumnName(String value)` | Validated column name: non-blank, no control characters. `ColumnName.violation(String)` is the policy chokepoint shared by builder, writer, and file parser |
| `MemorySize` | `record MemorySize(long bytes)` | Validated non-negative byte count; `ofKiB`/`ofMiB`/`ofGiB` factories, `toGiB()` for display. Rejects negative values (`IllegalArgumentException`) — a programmer-error guard, not a `VortexException`-worthy untrusted-input check |
| `EditionFamily` | `enum` — `CORE`, `UNSTABLE` | Closed (unlike `EncodingId`/`LayoutId`): an edition family is a cross-implementation compatibility promise, so a custom family carries no real guarantee — see [Editions](#editions) |
| `EditionId` | `record EditionId(EditionFamily family, YearMonth cutMonth, int version)` | e.g. `core2025.05.0`; `isAtOrBefore` orders editions within a family only |
| `Edition` | `record Edition(EditionId id, Set<EncodingId> added)` | Only `Editions`'s catalog constants should be constructed (API contract, not compiler-enforced — see ADR 0023) |

## Reader API

### `VortexReader` (`io.github.dfa1.vortex.reader.VortexReader`)

Memory-mapped handle to a Vortex file. Implements `AutoCloseable`. Closing releases the mmap region;
all `Array` buffers obtained during scans become invalid.

| Method                                | Returns                   | Notes                                         |
|---------------------------------------|---------------------------|-----------------------------------------------|
| `static open(Path)`                   | `VortexReader`            | Uses `ReadRegistry.loadAll()`                 |
| `static open(Path, ReadRegistry)`     | `VortexReader`            | Custom registry (e.g. `allowUnknown()`)       |
| `static open(Path, ReadRegistry, LayoutRegistry)` | `VortexReader` | Custom layout decoders too                    |
| `dtype()`                             | `DType`                   | Schema (typically `DType.Struct`)             |
| `layout()`                            | `Layout`                  | Layout tree (Struct → Zoned → Chunked → Flat) |
| `footer()`                            | `Footer`                  | Segment specs, encoding specs                 |
| `version()`                           | `int`                     | File format version                           |
| `fileSize()`                          | `long`                    | File size in bytes                            |
| `scan(ScanOptions)`                   | `ScanIterator`            | Open a scan                                   |
| `columnStats()`                       | `Map<ColumnName, ArrayStats>` | Aggregated min/max per column             |
| `slice(offset, length)`               | `MemorySegment`           | Zero-copy slice of mmap region                |
| `close()`                             | —                         | Releases mmap                                 |

---

## Writer API

### `VortexWriter` (`io.github.dfa1.vortex.writer.VortexWriter`)

Writes a Vortex file. Implements `Closeable`. The file is complete and readable as soon as `close()` returns.

| Method                                                                           | Notes                                                                                                            |
|----------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------|
| `static create(WritableByteChannel, DType.Struct, WriteOptions)`                 | Default codec set                                                                                                |
| `static create(WritableByteChannel, DType.Struct, WriteOptions, List<Encoding>)` | Custom codec set                                                                                                 |
| `writeChunk(Consumer<Chunk>)`                                                    | One batch of rows; typed builder validates column names + array types at each `.put`; missing columns throw `IllegalStateException` when the lambda returns. Preferred when columns are known at compile time. |
| `writeChunk(Map<ColumnName, Object>)`                                                | One batch of rows by map. Validates that every schema column is present and that all columns share the same row count. Use when the column set is built dynamically (Parquet/JDBC importers, generic exporters). |
| `close()`                                                                        | Finalizes file (footer, postscript, trailer)                                                                     |

### `Chunk` (`io.github.dfa1.vortex.writer.Chunk`)

Builder handed to the `writeChunk(Consumer<Chunk>)` lambda. Validates each `.put`
at the call site.

| Method                          | Notes                                                              |
|---------------------------------|--------------------------------------------------------------------|
| `put(ColumnName column, Object data)` | Adds one column; returns `this` for chaining                   |

Accepted array types per column `DType`:

| `DType`                       | Non-nullable array | Nullable array     |
|-------------------------------|--------------------|--------------------|
| `Primitive(I8/U8)`            | `byte[]`           | `Byte[]`           |
| `Primitive(I16/U16)`          | `short[]`          | `Short[]`          |
| `Primitive(I32/U32)`          | `int[]`            | `Integer[]`        |
| `Primitive(I64/U64)`          | `long[]`           | `Long[]`           |
| `Primitive(F32)`              | `float[]`          | `Float[]`          |
| `Primitive(F64)`              | `double[]`         | `Double[]`         |
| `Utf8`                        | `String[]`         | `String[]` (nulls allowed) |
| `Bool`                        | `boolean[]`        | `Boolean[]`        |

### `WriteOptions` (`io.github.dfa1.vortex.writer.WriteOptions`)

Record: `(int chunkSize, boolean enableZoneMaps, double compressionRatioThreshold, int allowedCascading, boolean globalDict, boolean enableZstd, MemorySize globalDictMaxRetainedBytes, Map<EditionFamily, Edition> editions)`.

| Factory                         | Defaults                                                                                          |
|---------------------------------|---------------------------------------------------------------------------------------------------|
| `WriteOptions.defaults()`       | `chunkSize=65_536`, `enableZoneMaps=true`, `compressionRatioThreshold=0.90`, `allowedCascading=0`, `globalDict=true`, `enableZstd=false`, `globalDictMaxRetainedBytes=MemorySize.ofGiB(2)`, `editions={CORE: Editions.CORE_2026_08_0}` |
| `WriteOptions.cascading(depth)` | Same defaults, `allowedCascading=depth`                                                           |

| Method | Notes |
|--------|-------|
| `withZoneMaps(boolean)` | Toggle per-chunk min/max/sum statistics |
| `withGlobalDict(boolean)` | Toggle the shared cross-chunk dictionary |
| `withZstd(boolean)` | Add Zstandard to the cascade codec competition. Requires `allowedCascading > 0` — Zstd only competes inside the cascade, so `withZstd(true)` throws `IllegalArgumentException` at depth 0; combine with `cascading(depth)` |
| `withGlobalDictMaxRetainedBytes(long)` | Aggregate heap budget for buffered global-dict candidate columns |
| `withEdition(Edition)` | Enable an [edition](#editions) for its family, replacing any edition already enabled for that family. No method disables the guard entirely — an `unstable`-family encoding is reached by enabling its edition explicitly, e.g. `withEdition(Editions.UNSTABLE_2025_05_0)` |

---

## Scan API

### `ScanOptions` (`io.github.dfa1.vortex.reader.ScanOptions`)

Record: `(List<ColumnName> columns, RowFilter rowFilter, long limit)` (built via `columns(String...)`). Empty `columns` = read all. `NO_LIMIT` =
`Long.MAX_VALUE`.

| Factory / builder                                   | Effect                           |
|-----------------------------------------------------|----------------------------------|
| `ScanOptions.all()`                                 | All columns, no filter, no limit |
| `ScanOptions.columns(String... names)`              | Project columns                  |
| `ScanOptions.limit(long n)`                         | Limit rows                       |
| `.withColumns(String... names)`                     | Project columns (builder)        |
| `.withFilter(RowFilter)`                            | Add zone-map filter              |
| `.withLimit(long n)`                                | Cap rows                         |
| `.hasProjection()` / `.hasFilter()` / `.hasLimit()` | Predicates                       |

### `RowFilter` (`io.github.dfa1.vortex.reader.RowFilter`)

Sealed predicate tree used for zone-map pruning (per-chunk min/max) and row selection. Two
variants: `RowFilter.Column(ColumnName, Predicate)` binds one column to a value-test
`Predicate` (`io.github.dfa1.vortex.reader.compute.Predicate` — the same vocabulary the
compute kernels evaluate); `RowFilter.And(List<RowFilter>)` conjoins several. Chunks that
cannot match are skipped entirely.

| Static factory              | Builds                            |
|------------------------------|------------------------------------|
| `RowFilter.eq(col, val)`    | `Column` bound to `Predicate.Eq`   |
| `RowFilter.neq(col, val)`   | `Column` bound to `Predicate.Neq`  |
| `RowFilter.gt(col, val)`    | `Column` bound to `Predicate.Gt`   |
| `RowFilter.gte(col, val)`   | `Column` bound to `Predicate.Gte`  |
| `RowFilter.lt(col, val)`    | `Column` bound to `Predicate.Lt`   |
| `RowFilter.lte(col, val)`   | `Column` bound to `Predicate.Lte`  |
| `RowFilter.isNull(col)`     | `Column` bound to `Predicate.IsNull`    |
| `RowFilter.isNotNull(col)`  | `Column` bound to `Predicate.IsNotNull` |
| `RowFilter.and(f1, f2, …)` / `f1.and(f2)` | `And` over the given filters |

### `ScanIterator` (`io.github.dfa1.vortex.reader.ScanIterator`)

Implements `Iterator<Chunk>` and `AutoCloseable`. Drives one scan.

| Method                 | Notes                                                                                |
|------------------------|--------------------------------------------------------------------------------------|
| `hasNext()`            | Side-effect-free. Returns whether another chunk is available after zone-map pruning. |
| `next()`               | Returns a fresh `Chunk` whose arena the caller closes. Throws `IllegalStateException` if a prior `Chunk` is still open, or `NoSuchElementException` if exhausted. |
| `forEachRemaining(Consumer)` | Overridden to wrap each `next()` in try-with-resources so chunks auto-close.   |
| `close()`              | Releases iterator state and closes any chunk still open.                             |

### `Chunk` (`io.github.dfa1.vortex.reader.Chunk`)

Implements `AutoCloseable`. Each chunk owns a confined `Arena` holding the decoded
columnar buffers; closing the chunk releases the arena. After `close()`, touching
any `Array` previously returned by `column(...)` or `columns()` raises FFM's scope
check (`IllegalStateException`).

Columns are stored as one order-preserving map keyed by the validated [`ColumnName`]; each
entry is a `Chunk.Column(Array array, DType dtype)` carrier, so a column's data and type can
never desync. `column(String)` is boundary sugar: the name is wrapped in a `ColumnName` (a
policy-invalid name fails fast — it could never match a certified column).

| Method                                      | Notes                                                    |
|---------------------------------------------|----------------------------------------------------------|
| `rowCount()`                                | Rows in this chunk                                       |
| `columns()`                                 | `SequencedMap<ColumnName, Chunk.Column>`, schema order, unmodifiable |
| `<T extends Array> column(String name)`     | Typed column lookup; throws `VortexException` if absent  |
| `<T extends Array> column(ColumnName name)` | Same, for callers that validated early                   |
| `as(String name, Class<T> domainType)`      | Extension column → typed `List<T>`                       |
| `isClosed()`                                | Whether `close()` has run                                |
| `close()`                                   | Releases the chunk's arena. Idempotent.                  |

---

## Encoding registry

### `ReadRegistry` (`io.github.dfa1.vortex.reader`)

Immutable after construction. Build via `ReadRegistry.builder()` or the static convenience factories.

| Method                      | Notes                                                                        |
|-----------------------------|------------------------------------------------------------------------------|
| `static builder()`          | Returns a fresh `Builder`                                                    |
| `static loadAll()`          | Immutable registry populated with all built-in decoders                      |
| `static empty()`            | Immutable empty registry (strict mode)                                       |
| `hasDecoder(EncodingId)`    | Lookup                                                                       |
| `isAllowUnknown()`          | Predicate                                                                    |

### `ReadRegistry.Builder`

| Method                       | Notes                                                                                    |
|------------------------------|------------------------------------------------------------------------------------------|
| `register(EncodingDecoder)`  | Add a custom encoding decoder; throws if already registered                              |
| `registerDefaults()`         | Add every built-in `EncodingDecoder`                                                     |
| `allowUnknown()`             | Switch to passthrough mode — unknown nodes (and their children) decode as `UnknownArray` |
| `build()`                    | Produce the immutable `ReadRegistry`                                                     |

Register custom encoding decoders programmatically via `register(EncodingDecoder)` — there is no
`ServiceLoader` discovery. Extension decoders
(`io.github.dfa1.vortex.reader.extension.ExtensionDecoder`) are not registry-managed: the built-in
implementations are singletons invoked directly by their `ExtensionId`.

---

## Editions

Frozen, named, additive sets of encoding IDs, each carrying a forever read-compatibility
guarantee once frozen (see ADR 0023). vortex-java mirrors the ground-truth catalog from
[vortex-data/vortex#8871](https://github.com/vortex-data/vortex/pull/8871) — the published
[editions spec](https://github.com/vortex-data/vortex/blob/develop/docs/specs/editions.md)'s own
registry section is not populated upstream yet. Editions are a **write/read policy, not part of
the wire format** — nothing about a targeted edition is ever persisted into a `.vortex` file.

### `Editions` (`io.github.dfa1.vortex.core.model.Editions`)

| Member                              | Notes                                                                 |
|--------------------------------------|------------------------------------------------------------------------------|
| `CORE_2025_05_0` … `CORE_2026_08_0` | The frozen `core` editions (forever read-compatibility guarantee)           |
| `UNSTABLE_2025_05_0` … `UNSTABLE_2026_06_0` | The draft `unstable` editions (no compatibility guarantee)          |
| `ALL`                                | Every declared edition, in declaration order                                 |
| `cumulativeMembers(Edition)`         | The edition's own additions plus every earlier same-family edition's         |
| `owningEdition(EncodingId)`          | The edition an id first joined, or empty if it belongs to none               |

vortex-java implements every `core`-family encoding through `core2026.08.0`, including
`vortex.map` (`EncodingId.VORTEX_MAP`, the canonical encoding for the `DType.Map` logical type). Of
`unstable`, only `fastlanes.delta` and `vortex.patched` have an `EncodingId.WellKnown` constant;
the rest (`vortex.zstd_buffers`, `vortex.parquet.variant`, the `vortex.tensor.*` family,
`vortex.onpair`) resolve to `EncodingId.Custom` and are stored in the catalog anyway, mirroring
upstream faithfully.

### Writer integration (`WriteOptions#editions()`)

`WriteOptions.defaults()`/`cascading(depth)` enable the latest frozen `core` edition
(`Editions.CORE_2026_08_0`) by default — verified safe: the default cascade candidate list never
includes `DeltaEncodingEncoder`/`PatchedEncodingEncoder` (the only two `unstable`-family encoders
implemented), so no default write can emit an `unstable` encoding. If a write would emit an
encoding outside the union of every enabled edition's cumulative members, `VortexWriter` fails
the write immediately with a `VortexException` naming the encoding and the configured edition(s).
Where possible (any selection routed through `CascadingCompressor`, including nested competitions
like a masked column's validity-bitmap cascade) the guard instead steers selection away from the
ineligible candidate ahead of time, falling back to the best remaining one — see ADR 0023.

`WriteOptions.withEdition(Edition)` enables an edition, replacing any edition already enabled for
that edition's family. Multiple families can be enabled at once (e.g. `core` and `unstable`
simultaneously); at most one edition per family. There is deliberately no "disable the guard"
method — an `unstable`-family encoding is reached by enabling its edition explicitly, not by
opting out of the guard altogether.

### Reader integration

When `ReadRegistry` hits an unregistered encoding id (and `allowUnknown()` is off), the thrown
`VortexException` names the edition the id belongs to (and whether it's an `unstable` draft with
no compatibility guarantee), or says the id is unknown to every edition and points at
`allowUnknown()`.

---

## FSST (`io.github.dfa1.vortex.fsst`)

The `vortex-fsst` module is the standalone FSST (Fast Static Symbol Table) string-compression
algorithm, usable independently of Vortex. It depends only on the JDK (`java.lang.foreign`), never
on `core`/`reader`/`writer`; `writer`/`reader` depend on it. The `vortex.fsst` encoding adapter is
one caller — the module itself knows nothing of the Vortex wire format. See
[ADR 0022](../adr/0022-fsst-module-extraction.md).

Compress: train a `Compressor` over a corpus, then compress rows against its table. Decompress:
build a `Decompressor` (from the trained `Compressor`, or from raw table arrays). Hot-path methods
accept `MemorySegment` (FFM-native, matching the rest of the codebase); `byte[]` overloads exist for
zero-ceremony standalone use, and both paths produce identical output for the same input.

### `CompressorBuilder`

| Method                  | Notes                                                                       |
|-------------------------|-----------------------------------------------------------------------------|
| `new CompressorBuilder()` | Fresh builder using a fixed reproducible default seed                     |
| `seed(long)`            | Override the training-sample seed; returns `this` for chaining               |
| `train(byte[][] rows)`  | Runs the bottom-up generation loop, returns a trained `Compressor` (deterministic in rows + seed) |

### `Compressor`

Immutable trained symbol table (up to 255 codes; `0xFF` is the escape). Produced by
`CompressorBuilder.train(byte[][])`.

| Method                                                    | Notes                                                                 |
|-----------------------------------------------------------|-----------------------------------------------------------------------|
| `symbolCount()`                                           | Number of symbols in the table (0–255)                                |
| `packedSymbol(int code)`                                  | The symbol's bytes packed LSB-first into a `long`                     |
| `symbolLength(int code)`                                  | The symbol's length in bytes (1–8)                                    |
| `codesSortedByLength()`                                   | Code permutation, multi-byte length-ascending then all length-1 last  |
| `toDecompressor()`                                        | A `Decompressor` bound to this table                                  |
| `compress(byte[] in, int start, int end, byte[] out, long outPos)`             | Greedy longest-match compress; size `out` ≥ `2 * (end - start)`  |
| `compress(MemorySegment in, long start, long end, MemorySegment out, long outPos)` | FFM-native hot path; same sizing rule                       |

### `Decompressor`

Decodes an FSST code stream against a trained table (parallel arrays indexed by code).

| Method                                                    | Notes                                                                 |
|-----------------------------------------------------------|-----------------------------------------------------------------------|
| `static of(long[] packedSymbols, int[] lengths)`          | Bind to a table; arrays are referenced, not copied, and must match in length |
| `static final int ESCAPE`                                 | The escape code (`0xFF`)                                              |
| `decompress(byte[] in, int start, int end, byte[] out, long outPos)`           | Decode; size `out` ≥ `8 * (end - start)`                         |
| `decompress(MemorySegment in, long start, long end, MemorySegment out, long outPos)` | Unconditional-8-byte-store decode; caller MUST allocate `out` with ≥ 7 bytes of trailing slack |

### `Symbol`

`record Symbol(long packedBytes, int length)` — up to 8 bytes packed LSB-first (byte `k` at bit
`k * 8`), length in 1–8.

| Method                              | Notes                                                       |
|-------------------------------------|-------------------------------------------------------------|
| `static of(byte[] data, int offset, int length)` | Pack `length` bytes at `data[offset..]` LSB-first |
| `byteAt(int i)`                     | The `i`-th byte (0-indexed; byte 0 is the LSB)              |

---

## Layout registry

### `LayoutRegistry` (`io.github.dfa1.vortex.reader.layout`)

Maps `LayoutId` → `LayoutDecoder`, making layout decode pluggable the way `ReadRegistry` makes
encodings pluggable. Programmatic registration only — no `ServiceLoader`. Unknown layouts fail
loudly (`VortexException`); there is no allow-unknown mode for layouts (Rust default).

| Method                      | Notes                                                              |
|-----------------------------|--------------------------------------------------------------------|
| `static defaults()`         | The built-ins: flat, chunked, zoned (both aliases), dict, struct   |
| `static builder()`          | Returns a fresh `Builder`                                          |
| `hasDecoder(LayoutId)`      | Lookup                                                             |
| `decode(ctx, layout, dtype)`| Dispatch by the layout's typed id                                  |

`Builder`: `register(LayoutDecoder)` (throws on duplicate id), `registerDefaults()`, `build()`.
A decoder claims its ids via `LayoutDecoder.layoutIds()` (a set — the zoned decoder claims both
`vortex.zoned` and legacy `vortex.stats`). Custom decoders receive a `LayoutDecodeContext`
(`decodeChild`, `decodeSegment` access, `arena`) and are reachable end-to-end via
`VortexReader.open(path, readRegistry, layoutRegistry)`.

## Parquet / CSV import and Parquet export

### `ParquetImporter` (`io.github.dfa1.vortex.parquet.ParquetImporter`)

Supports flat schemas and nested `LIST`/`STRUCT` columns, recursively composable (e.g.
`LIST<STRUCT<...>>`); `MAP` and `VARIANT` are not yet supported. Un-annotated `BYTE_ARRAY` maps
to `DType.Binary`.

| Method                                            | Notes                              |
|---------------------------------------------------|-------------------------------------|
| `importParquet(Path in, Path out)`                | Defaults                            |
| `importParquet(Path in, Path out, ImportOptions)` | Tuned                                |
| `importParquet(URI in, Path out)`                 | Remote source over HTTP(S), defaults |
| `importParquet(URI in, Path out, ImportOptions)`  | Remote source over HTTP(S), tuned    |

A `URI` source is fetched entirely through targeted HTTP Range requests (`HttpInputFile`,
internal) — no full-file download occurs.

### `ImportOptions` (`io.github.dfa1.vortex.parquet.ImportOptions`)

Record: `(int chunkSize, List<String> columns, ProgressListener progressListener, WriteOptions writeOptions)`.

| Factory / builder                 | Notes                                                          |
|-----------------------------------|----------------------------------------------------------------|
| `ImportOptions.defaults()`        | `chunkSize=65_536`, no projection, `WriteOptions.cascading(3)` |
| `.withColumns(List<String>)`      | Project columns during import                                  |
| `.withProgressListener(listener)` | Progress callbacks                                             |
| `.withWriteOptions(WriteOptions)` | Override write options                                         |
| `.withChunkSize(int)`             | Override chunk size                                            |

### `ParquetExporter` (`io.github.dfa1.vortex.parquet.ParquetExporter`)

Supports flat schemas only: `Bool`, non-`F16` `Primitive`, `Utf8`, `Binary`, and the
`vortex.timestamp` extension over MILLIS/MICROS/NANOS resolution. `Struct`/`List`/`Map` top-level
columns throw `UnsupportedOperationException` — the inverse of `ParquetImporter`'s nested
`LIST`/`STRUCT` support does not exist yet.

| Method                                                   | Notes                                                    |
|------------------------------------------------------------|-----------------------------------------------------------|
| `exportParquet(Path in, Path out)`                       | Defaults                                                 |
| `exportParquet(Path in, Path out, ExportOptions)`        | Tuned                                                    |
| `exportParquet(VortexHandle in, Path out)`               | Already-open source (local `VortexReader` or remote `VortexHttpReader`), defaults |
| `exportParquet(VortexHandle in, Path out, ExportOptions)`| Already-open source, tuned                               |

The `VortexHandle` overloads don't close `in` — the caller opened it and keeps ownership of its
lifecycle, so a remote `VortexHttpReader.open(uri)` can be exported without an intervening local
copy.

### `ExportOptions` (`io.github.dfa1.vortex.parquet.ExportOptions`)

Record: `(List<String> columns, ProgressListener progressListener, WriterConfig writerConfig)`.
`WriterConfig` is Hardwood's own writer configuration type
(`dev.hardwood.writer.WriterConfig`) — row group/page targets, compression codec, column
encoding policy.

| Factory / builder                 | Notes                                               |
|------------------------------------|-----------------------------------------------------|
| `ExportOptions.defaults()`        | No projection, Hardwood's `WriterConfig.defaults()` |
| `.withColumns(List<String>)`      | Project columns during export                       |
| `.withProgressListener(listener)` | Progress callbacks                                  |
| `.withWriterConfig(WriterConfig)` | Override Hardwood writer configuration              |

### `CsvImporter` (`io.github.dfa1.vortex.csv.CsvImporter`)

Column types are inferred from the data (long → double → boolean → utf8, in priority order)
unless a schema is given via `ImportOptions#withSchema`. Schema-driven CLI subcommands
(`schema`, `count`, …) are documented for Vortex/Parquet files only — CSV has no schema of its
own, so it is only ever an `import` *source*: never a destination (there is no CSV export from
`import`), and never a source for the other subcommands, which all expect a Vortex or Parquet
file.

| Method                                       | Notes                                                     |
|-----------------------------------------------|------------------------------------------------------------|
| `importCsv(Path in, Path out)`               | Defaults                                                  |
| `importCsv(Path in, Path out, ImportOptions)`| Tuned                                                     |
| `importCsv(URI in, Path out)`                | Remote source over HTTP(S), defaults                       |
| `importCsv(URI in, Path out, ImportOptions)` | Remote source over HTTP(S), tuned                           |

A `URI` source streams the response body directly, front to back, as it arrives — unlike
Parquet's random-access footer-first format, CSV needs no Range requests and no local temp file.

---

## CLI

The `-all` uber-jar is self-contained: pure-Java code plus the native `libzstd` for all
supported platforms (the FFM loader picks the right one at runtime) — no system libraries
required.

The `cli` module ships a fat jar with subcommands for inspecting and querying Vortex files.

```bash
./mvnw package -pl cli -am -DskipTests
java -jar cli/target/vortex-cli-*-all.jar <subcommand> [args]
```

| Subcommand | Syntax                                         | Description                                      |
|------------|------------------------------------------------|--------------------------------------------------|
| `inspect`  | `inspect <file.vortex>`                        | Layout tree, encodings, row counts, buffer sizes |
| `tui`      | `tui <file.vortex \| http(s)://url>`           | Interactive layout-tree browser (lazy stats + data) |
| `view`     | `view <file.vortex \| http(s)://url>`          | Interactive spreadsheet-grid browser over the row data |
| `schema`   | `schema <file.vortex>`                         | Column names and types                           |
| `count`    | `count <file.vortex>`                          | Total row count                                  |
| `stats`    | `stats <file.vortex>`                          | Per-column min/max                               |
| `export`   | `export <file.vortex\|url> [out.csv\|out.parquet\|-]` | All columns to CSV (default) or Parquet, by output extension; `-` for CSV on stdout. A `url` source requires an explicit `out.parquet` path — CSV/stdout from a URL isn't supported |
| `select`   | `select <file.vortex> <col> [col2 ...]`        | Project columns to CSV                           |
| `filter`   | `filter <file.vortex> "<expr>"`                | Filter rows to CSV                               |
| `import`   | `import [--delimiter <char>] <file.csv\|file.parquet\|url> [out.vortex\|out.parquet]` | CSV or Parquet (local or remote) source to Vortex; a `.parquet` output is CSV-only (chains through a temp Vortex file internally) — a Parquet source always produces Vortex, `.parquet` output is rejected |

### `filter` expression syntax

```
<column> <op> <value>
```

| Operator  | Meaning                        |
|-----------|--------------------------------|
| `>`, `>=` | Greater than, greater-or-equal |
| `<`, `<=` | Less than, less-or-equal       |
| `=`, `==` | Equal                          |
| `!=`      | Not equal                      |

Values are parsed as integer, double, boolean, or string (in that order).

---

## File format trailer

8 bytes at EOF:

```
version (u16 LE) | postscriptLen (u16 LE) | magic ("VTXF")
```

The postscript is a FlatBuffer blob immediately before the trailer. It points (offset + length) to:
the Footer (FlatBuffer), the DType (FlatBuffer), and the Layout (FlatBuffer) — each stored elsewhere in the file.

See [explanation.md#memory-model](explanation.md#memory-model) for the mmap lifecycle.
