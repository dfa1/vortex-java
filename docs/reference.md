# Reference

API surface, CLI commands, and operator tables. Look here for "what exists and what it accepts."
For task-oriented usage see [how-to.md](how-to.md); for design rationale see [explanation.md](explanation.md).

- [Core types](#core-types)
- [Reader API](#reader-api)
- [Writer API](#writer-api)
- [Scan API](#scan-api)
- [Encoding registry](#encoding-registry)
- [Parquet / CSV import](#parquet--csv-import)
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
| `F16`                     | 2             | IEEE 754 half — decode not yet supported |
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
| `DType.Extension`     | —                                                      | `new DType.Extension(id, storageDType, metadata, nullable)` |

Helpers: `nullable()` (boolean accessor on every record), `asNullable()` (fluent
shortcut returning a nullable copy), `withNullable(boolean)`, `DType.Struct.field(name)`.

---

### Identity types (`io.github.dfa1.vortex.core.model`)

| Type | Shape | Notes |
|------|-------|-------|
| `EncodingId` | `sealed interface` — `WellKnown` enum + `Custom` record | Array-encoding identity; total `parse(String)` over non-blank ids; constants re-exported (`EncodingId.VORTEX_PRIMITIVE`, …) |
| `LayoutId` | `sealed interface` — `WellKnown` enum + `Custom` record | Layout identity (separate namespace from encodings; `vortex.flat` is layout-only); both zoned aliases `vortex.zoned`/`vortex.stats` |
| `ColumnName` | `record ColumnName(String value)` | Validated column name: non-blank, no control characters. `ColumnName.violation(String)` is the policy chokepoint shared by builder, writer, and file parser |

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

Record: `(int chunkSize, boolean enableZoneMaps, double compressionRatioThreshold, int allowedCascading)`.

| Factory                         | Defaults                                                                                          |
|---------------------------------|---------------------------------------------------------------------------------------------------|
| `WriteOptions.defaults()`       | `chunkSize=65_536`, `enableZoneMaps=true`, `compressionRatioThreshold=0.90`, `allowedCascading=0` |
| `WriteOptions.cascading(depth)` | Same defaults, `allowedCascading=depth`                                                           |

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

Sealed predicate used for zone-map pruning (per-chunk min/max). Chunks that cannot match are skipped entirely.

| Record                         | Static factory             | Builder      |
|--------------------------------|----------------------------|--------------|
| `RowFilter.Gt(column, value)`  | `RowFilter.gt(col, val)`   | —            |
| `RowFilter.Gte(column, value)` | `RowFilter.gte(col, val)`  | —            |
| `RowFilter.Lt(column, value)`  | `RowFilter.lt(col, val)`   | —            |
| `RowFilter.Lte(column, value)` | `RowFilter.lte(col, val)`  | —            |
| `RowFilter.Eq(column, value)`  | `RowFilter.eq(col, val)`   | —            |
| `RowFilter.Neq(column, value)` | `RowFilter.neq(col, val)`  | —            |
| `RowFilter.And(filters)`       | `RowFilter.and(f1, f2, …)` | `f1.and(f2)` |

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
| `static loadAll()`          | Immutable registry populated via `ServiceLoader`                             |
| `static empty()`            | Immutable empty registry (strict mode)                                       |
| `hasDecoder(EncodingId)`    | Lookup                                                                       |
| `isAllowUnknown()`          | Predicate                                                                    |

### `ReadRegistry.Builder`

| Method                       | Notes                                                                                    |
|------------------------------|------------------------------------------------------------------------------------------|
| `register(EncodingDecoder)`  | Add a custom encoding decoder; throws if already registered                              |
| `registerServiceLoaded()`    | Add every `EncodingDecoder` discovered via `ServiceLoader`                               |
| `allowUnknown()`             | Switch to passthrough mode — unknown nodes (and their children) decode as `UnknownArray` |
| `build()`                    | Produce the immutable `ReadRegistry`                                                     |

Register custom encoding decoders via `ServiceLoader` by adding the fully qualified class name to
`META-INF/services/io.github.dfa1.vortex.reader.decode.EncodingDecoder`. Extension decoders
(`io.github.dfa1.vortex.reader.extension.ExtensionDecoder`) are not registry-managed: the built-in
implementations are singletons invoked directly by their `ExtensionId`.

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

## Parquet / CSV import

### `ParquetImporter` (`io.github.dfa1.vortex.parquet.ParquetImporter`)

| Method                                            | Notes    |
|---------------------------------------------------|----------|
| `importParquet(Path in, Path out)`                | Defaults |
| `importParquet(Path in, Path out, ImportOptions)` | Tuned    |

### `ImportOptions` (`io.github.dfa1.vortex.parquet.ImportOptions`)

Record: `(int chunkSize, List<String> columns, ProgressListener progressListener, WriteOptions writeOptions)`.

| Factory / builder                 | Notes                                                          |
|-----------------------------------|----------------------------------------------------------------|
| `ImportOptions.defaults()`        | `chunkSize=65_536`, no projection, `WriteOptions.cascading(3)` |
| `.withColumns(List<String>)`      | Project columns during import                                  |
| `.withProgressListener(listener)` | Progress callbacks                                             |
| `.withWriteOptions(WriteOptions)` | Override write options                                         |
| `.withChunkSize(int)`             | Override chunk size                                            |

CSV import is CLI-only — types are inferred from the data.

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
| `tui`      | `tui <file.vortex \| http(s)://url>`           | Interactive terminal browser (lazy stats + data) |
| `schema`   | `schema <file.vortex>`                         | Column names and types                           |
| `count`    | `count <file.vortex>`                          | Total row count                                  |
| `stats`    | `stats <file.vortex>`                          | Per-column min/max                               |
| `export`   | `export <file.vortex>`                         | All columns to CSV on stdout                     |
| `select`   | `select <file.vortex> <col> [col2 ...]`        | Project columns to CSV                           |
| `filter`   | `filter <file.vortex> "<expr>"`                | Filter rows to CSV                               |
| `import`   | `import [--delimiter <char>] <file.csv\|file.parquet> [out.vortex]` | Convert CSV or Parquet to Vortex                 |

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
the Footer (FlatBuffer), the DType (Protobuf), and the Layout (FlatBuffer) — each stored elsewhere in the file.

See [explanation.md#memory-model](explanation.md#memory-model) for the mmap lifecycle.
