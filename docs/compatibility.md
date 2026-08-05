# Compatibility

Tested against the [Rust reference implementation](https://github.com/vortex-data/vortex) v0.74.0.
For the rest of the API surface (reader, writer, scan, CLI), see [reference.md](reference.md).

## Read-only deployment

The reader and inspector modules carry no transitive dependency on the writer module.
A consumer that only needs to read Vortex files can depend on a strict subset:

```xml
<dependency>
  <groupId>io.github.dfa1.vortex</groupId>
  <artifactId>vortex-reader</artifactId>
  <version>0.13.0</version>
</dependency>

<!-- optional: inspector for layout-tree introspection -->
<dependency>
  <groupId>io.github.dfa1.vortex</groupId>
  <artifactId>vortex-inspector</artifactId>
  <version>0.13.0</version>
</dependency>
```

The zstd binding is optional on both sides: files using `vortex.zstd` need
`io.github.dfa1.zstd:zstd` plus `io.github.dfa1.zstd:zstd-platform` on the classpath (versions
pinned by the vortex BOM); without them, touching a `vortex.zstd` segment fails with a
`VortexException` that names the two artifacts. All other encodings are pure Java.

`./mvnw -pl core,reader,inspector verify` builds the read-only artifact set
without the writer module on the classpath. `ReadRegistry.loadAll()` registers
only the built-in decoders in `reader`; no encoder class is loaded.

## Known wire-format gaps

| Item | Introduced | Java status |
|------|------------|-------------|
| `DType::Union` (`fbs.DType.Type.Union = 12`) | Rust 0.71.0 | ❌ Decode throws `VortexException("unsupported DType typeType=12")`. No `DType.Union` variant in Java's sealed type. |
| `vortex.onpair` experimental string encoding | Rust 0.74.0 | ❌ Not registered. Files using it fail to decode unless `ReadRegistry.builder().allowUnknown()` is enabled. |
| `vortex.variant` arbitrary nested objects | Rust (`vortex.parquet.variant`) | ⚠️ Java encodes/decodes variant columns of **typed scalar** values (constant / chunked-of-constants core, optional shredded child); Java↔Rust round-trip verified. Arbitrary nested JSON objects and real path-based shredding need the `vortex.parquet.variant` physical encoding — deferred ([ADR 0014](../adr/0014-variant-encoding-strategy.md)). |
| Arrow extension array import affecting Variant shape | Rust 0.74.0 (#8125) | Untested. Re-run integration fixtures against v0.74.0 once published. |
| Duplicate struct field names | Rust writer rejects ("StructLayout must have unique field names"); Rust reader tolerates foreign files (first-match access) | ⚠️ Deliberate divergence on read: Java rejects such files with `VortexException("duplicate field name in file schema")` instead of tolerating them — the name-keyed `Chunk` API cannot represent both columns, and silent column loss is worse than a loud failure on a file the reference writer refuses to produce. Java's writer mirrors the Rust writer's rejection. |
| Blank / control-character field names | Wire-legal; the Rust writer produces `""` and whitespace-only names. NUL (`U+0000`) additionally aborts the Rust toolchain: Arrow FFI schema export hits a panic-cannot-unwind in `arrow-rs` (`ffi_stream::get_schema`) and SIGABRTs the process (measured against vortex-jni 0.75.0) | ⚠️ Deliberate strictness BOTH ways: vortex-java's writer refuses blank and control-character field names (`IllegalArgumentException`), and its reader rejects files carrying them (`VortexException` naming the producing pipeline as the likely bug) — the JSON-`""`-key principle: wire-legal is a floor, not a policy. Printable names of any shape (`$`-runs, spaces inside, emoji) are legal and round-trip intact both directions (measured; pinned by `ColumnNameEdgeCasesIntegrationTest`). |

## Real-world conformance: the Raincloud corpus

Cross-implementation conformance against [Raincloud](https://github.com/spiraldb/raincloud)
(tracked in [#205](https://github.com/dfa1/vortex-java/issues/205)): 247 curated public
datasets whose `.vortex` files are written by the Vortex **Python** bindings
(`vortex-data 0.69.0`, pinned via Raincloud `v0.2.1`) with a Parquet sibling built from the
same Arrow table. `RaincloudConformanceIntegrationTest` reads each hydrated `.vortex` with
vortex-java, exports CSV, and diffs it against the Parquet sibling read by hardwood (the
oracle) — a zero-diff proves every value survived the Python-write / Java-read boundary.

```bash
scripts/hydrate-raincloud-corpus.sh --max-mb 200      # cache → mirror → local build
./mvnw verify -pl integration -am -Dvortex.it.excludedGroups= -Dit.test=RaincloudConformanceIntegrationTest
```

Per-slug status lives in `integration/src/test/resources/raincloud/expected-status.csv`
(`ok` must pass; `gap:<issue>` must still fail, so a fix flips the entry in the same change;
`untriaged` runs and reports without failing the build). A scheduled workflow
(`raincloud-conformance.yml`) hydrates a size-capped subset weekly. Current triage —
117 `ok`, 0 known gaps; 130 slugs untriaged. Every gap found so far is fixed
([#206](https://github.com/dfa1/vortex-java/issues/206)–[#211](https://github.com/dfa1/vortex-java/issues/211),
[#215](https://github.com/dfa1/vortex-java/issues/215)–[#217](https://github.com/dfa1/vortex-java/issues/217),
[#221](https://github.com/dfa1/vortex-java/issues/221),
[#225](https://github.com/dfa1/vortex-java/issues/225) RunEnd run-values validity,
[#226](https://github.com/dfa1/vortex-java/issues/226) Sparse null fill / nullable patches,
[#257](https://github.com/dfa1/vortex-java/issues/257) CsvExporter FixedSizeList/List support,
[#259](https://github.com/dfa1/vortex-java/issues/259) conformance-test pipe deadlock,
[#261](https://github.com/dfa1/vortex-java/issues/261) oracle repeated/list column support, plus
two bugs the widened oracle then caught: `CsvExporter` list offsets assumed I32/I64 only (any
integer width is wire-legal, mirroring `VarBinArray`), and `ScanIterator` could not slice a shared
`ListArray`/`FixedSizeListArray` spanning several split windows).

## Encodings

| Encoding ID                 | Decoder                          | Encoder                          | Decode | Encode | Notes                                                                 |
|-----------------------------|----------------------------------|----------------------------------|--------|--------|-----------------------------------------------------------------------|
| `vortex.primitive`          | `PrimitiveEncodingDecoder`       | `PrimitiveEncodingEncoder`       | ✅      | ✅      | All `PType` (I8–I64, U8–U64, F32, F64)                                |
| `vortex.bool`               | `BoolEncodingDecoder`            | `BoolEncodingEncoder`            | ✅      | ✅      | Bool (bit-packed)                                                     |
| `vortex.null`               | `NullEncodingDecoder`            | `NullEncodingEncoder`            | ✅      | ✅      | Null                                                                  |
| `vortex.bytebool`           | `ByteBoolEncodingDecoder`        | `ByteBoolEncodingEncoder`        | ✅      | ✅      | Bool (byte-per-element)                                               |
| `vortex.zigzag`             | `ZigZagEncodingDecoder`          | `ZigZagEncodingEncoder`          | ✅      | ✅      | Signed integer PTypes                                                 |
| `vortex.constant`           | `ConstantEncodingDecoder`        | `ConstantEncodingEncoder`        | ✅      | ✅      | Primitive, Utf8, Binary, Bool, Null, Decimal, Extension               |
| `vortex.ext`                | `ExtEncodingDecoder`             | `ExtEncodingEncoder`             | ✅      | ✅      | Extension                                                             |
| `vortex.runend`             | `RunEndEncodingDecoder`          | `RunEndEncodingEncoder`          | ✅      | ✅      | Primitive, Utf8/Binary, Bool                                          |
| `vortex.varbin`             | `VarBinEncodingDecoder`          | `VarBinEncodingEncoder`          | ✅      | ✅      | Utf8, Binary                                                          |
| `vortex.varbinview`         | `VarBinViewEncodingDecoder`      | `VarBinViewEncodingEncoder`      | ✅      | ✅      | Utf8, Binary                                                          |
| `vortex.alp`                | `AlpEncodingDecoder`             | `AlpEncodingEncoder`             | ✅      | ✅      | F64, F32                                                              |
| `vortex.alprd`              | `AlpRdEncodingDecoder`           | `AlpRdEncodingEncoder`           | ✅      | ✅      | F64, F32                                                              |
| `vortex.dict`               | `DictEncodingDecoder`            | `DictEncodingEncoder`            | ✅      | ✅      | Primitive, Utf8/Binary                                                |
| `vortex.sparse`             | `SparseEncodingDecoder`          | `SparseEncodingEncoder`          | ✅      | ✅      | Primitive                                                             |
| `vortex.sequence`           | `SequenceEncodingDecoder`        | `SequenceEncodingEncoder`        | ✅      | ✅      | Primitive                                                             |
| `vortex.struct`             | `StructEncodingDecoder`          | `StructEncodingEncoder`          | ✅      | ✅      | Struct                                                                |
| `vortex.chunked`            | `ChunkedEncodingDecoder`         | `ChunkedEncodingEncoder`         | ✅      | ✅      | Primitive + Struct concat                                             |
| `vortex.fsst`               | `FsstEncodingDecoder`            | `FsstEncodingEncoder`            | ✅      | ✅      | Utf8, Binary                                                          |
| `vortex.list`               | `ListEncodingDecoder`            | `ListEncodingEncoder`            | ✅      | ✅      |                                                                       |
| `vortex.listview`           | `ListViewEncodingDecoder`        | `ListViewEncodingEncoder`        | ✅      | ✅      |                                                                       |
| `vortex.fixed_size_list`    | `FixedSizeListEncodingDecoder`   | `FixedSizeListEncodingEncoder`   | ✅      | ✅      |                                                                       |
| `vortex.zstd`               | `ZstdEncodingDecoder`            | `ZstdEncodingEncoder`            | ✅      | ✅      | Primitive, Utf8, Binary                                               |
| `vortex.masked`             | `MaskedEncodingDecoder`          | `MaskedEncodingEncoder`          | ✅      | ✅      | NullableData carrier; inner values cascade (Dict/FSST/ALP/…) when cascading is enabled; Utf8/Primitive still compete on measured size even at depth 0, else first-match FixedSizeList |
| `vortex.decimal`            | `DecimalEncodingDecoder`         | `DecimalEncodingEncoder`         | ✅      | ✅      |                                                                       |
| `vortex.decimal_byte_parts` | `DecimalBytePartsEncodingDecoder`| `DecimalBytePartsEncodingEncoder`| ✅      | ✅      |                                                                       |
| `vortex.datetimeparts`      | `DateTimePartsEncodingDecoder`   | `DateTimePartsEncodingEncoder`   | ✅      | ✅      |                                                                       |
| `vortex.pco`                | `PcoEncodingDecoder`             | `PcoEncodingEncoder`             | ✅      | ✅      | Decode: all modes. Encode: Classic + Consecutive delta + IntMult; FloatMult/FloatQuant deferred |
| `fastlanes.bitpacked`       | `BitpackedEncodingDecoder`       | `BitpackedEncodingEncoder`       | ✅      | ✅      | Unsigned integer PTypes                                               |
| `fastlanes.delta`           | `DeltaEncodingDecoder`           | `DeltaEncodingEncoder`           | ✅      | ✅      | Integer PTypes                                                        |
| `fastlanes.for`             | `FrameOfReferenceEncodingDecoder`| `FrameOfReferenceEncodingEncoder`| ✅      | ✅      | Integer PTypes                                                        |
| `fastlanes.rle`             | `RleEncodingDecoder`             | `RleEncodingEncoder`             | ✅      | ✅      | Chunk-based RLE                                                       |
| `vortex.patched`            | `PatchedEncodingDecoder`         | `PatchedEncodingEncoder`         | ✅      | ✅      | Primitive PTypes; base + chunked patches (1024-elem blocks)            |
| `vortex.variant`            | `VariantEncodingDecoder`         | `VariantEncodingEncoder`         | ✅      | ✅      | Canonical container; constant / chunked-of-constants core + optional shredded child. Typed-scalar values only — nested objects need `parquet.variant` (ADR 0014) |
| `vortex.onpair`             | _none_                           | _none_                           | ❌      | ❌      | Experimental in Rust 0.74.0; not yet ported                            |

### Decode shape

Per [ADR 0010](../adr/0010-lazy-decode.md) and [ADR 0012](../adr/0012-zero-copy-layout-decoding.md), each
decoder falls into one of three shapes:

- **Zero-copy** — output is a view over the memory-mapped file (or a wrapper over child arrays).
  No arena allocation, no per-element copy.
- **Lazy** — output is a `LazyXxxArray` / `ChunkedXxxArray` record that holds the encoded child
  plus the transform parameters. Per-row `getXxx(i)` applies the transform on demand. No
  output buffer is allocated unless a caller explicitly materializes via
  `Array.materialize(arena)`.
- **Materialized** — output is a buffer allocated from `ctx.arena()` populated during `decode()`.
  Required for decompression-style encodings (Bitpacked, Pco, Zstd, etc.) where reading element
  `i` would require decoding a window.

| Encoding ID                 | Now           | Target        | Notes                                                                    |
|-----------------------------|---------------|---------------|--------------------------------------------------------------------------|
| `vortex.primitive`          | Zero-copy     | Zero-copy     | mmap slice                                                               |
| `vortex.bool`               | Zero-copy     | Zero-copy     | mmap slice (bit-packed)                                                  |
| `vortex.null`               | n/a           | n/a           | no per-row data                                                          |
| `vortex.bytebool`           | Zero-copy     | Zero-copy     | mmap slice                                                               |
| `vortex.zigzag`             | Lazy          | Lazy          | `LazyZigZagXxxArray` (I8/I16/I32/I64); broadcast → `LazyConstantXxxArray`, ADR 0010 + 0015 |
| `vortex.constant`           | Lazy          | Lazy          | `LazyConstantXxxArray` (primitive + bool + decimal); per-row broadcast, no buffer, ADR 0015 |
| `vortex.ext`                | Zero-copy     | Zero-copy     | wraps storage                                                            |
| `vortex.runend`             | Lazy          | Lazy          | `LazyRunEndXxxArray` (primitive + bool); Utf8/Binary stays Materialized (offset rebasing), ADR 0015 |
| `vortex.varbin`             | Zero-copy     | Zero-copy     | bytes + offsets slices                                                   |
| `vortex.varbinview`         | Lazy          | Lazy          | `VarBinArray.ViewMode` — keeps views + data buffers as mmap slices       |
| `vortex.alp`                | Lazy          | Lazy          | `LazyAlpXxxArray`; broadcast → `LazyConstantXxxArray`; patched stays Materialized, ADR 0010 + 0015 |
| `vortex.alprd`              | Lazy          | Lazy          | `LazyAlpRdDoubleArray`/`LazyAlpRdFloatArray` — left/right + patches on access |
| `vortex.dict`               | Lazy          | Lazy          | `DictXxxArray` (numeric) + `VarBinArray.DictMode` (string), ADR 0012      |
| `vortex.sparse`             | Lazy          | Lazy          | `LazySparseXxxArray` (primitive + bool); Utf8/Binary stays Materialized, ADR 0015 |
| `vortex.sequence`           | Zero-copy     | Zero-copy     | synthetic (no data)                                                      |
| `vortex.struct`             | Zero-copy     | Zero-copy     | `StructArray` wraps fields                                               |
| `vortex.chunked`            | Lazy          | Lazy          | `ChunkedXxxArray` (primitive/Bool) + `VarBinArray.ChunkedMode` (Utf8/Binary), ADR 0012 |
| `vortex.fsst`               | Materialized  | Materialized  | symbol-table decompression                                               |
| `vortex.list`               | Lazy          | Lazy          | `ListArray` wraps elements + offsets children; shape inherits from child  |
| `vortex.listview`           | Lazy          | Lazy          | `ListViewArray` wraps elements + offsets + sizes children                 |
| `vortex.fixed_size_list`    | Lazy          | Lazy          | `FixedSizeListArray` wraps flat elements child; no per-row alloc          |
| `vortex.zstd`               | Materialized  | Materialized  | block decompression                                                      |
| `vortex.masked`             | Zero-copy     | Zero-copy     | wraps inner + validity                                                   |
| `vortex.decimal`            | Lazy          | Lazy          | `LazyDecimalArray` — `BigDecimal` materialized per row on `getDecimal(i)` |
| `vortex.decimal_byte_parts` | Lazy          | Lazy          | `LazyDecimalBytePartsArray` — reassembles byte parts on access           |
| `vortex.datetimeparts`      | Lazy          | Lazy          | `LazyDateTimePartsLongArray` — reassembles parts on access               |
| `vortex.pco`                | Materialized  | Materialized  | range-encoded decompression                                              |
| `fastlanes.bitpacked`       | Materialized  | Materialized  | window unpacks bits                                                      |
| `fastlanes.delta`           | Materialized  | Materialized  | cumulative sum requires sequential decode                                |
| `fastlanes.for`             | Lazy          | Lazy          | `LazyForXxxArray` (I8/U8/I16/U16/I32/U32/I64/U64), ADR 0010 + 0015      |
| `fastlanes.rle`             | Lazy          | Lazy          | `LazyRleXxxArray`; validity → `OffsetBoolArray`; empty → `LazyConstantXxxArray`, ADR 0015 |
| `vortex.patched`            | Materialized  | Materialized  | inner is full base + chunked patches (1024-elem blocks, lane-window-sorted); per-row access requires 2 laneOffsets reads + binary search inside the chunk window, so eager scatter wins for full scans |
| `vortex.variant`            | Lazy          | Lazy          | container wraps constant/chunked core (inner-typed) + optional shredded child |
| `vortex.onpair`             | n/a           | n/a           | not ported                                                               |

Decompression-style encodings (Bitpacked / Pco / Zstd / Fsst / Delta) stay Materialized by design
— element-at-`i` requires decoding a window, so they must allocate output (ADR 0010 §"Decompression
encodings stay eager"). Their output can itself be wrapped in a 1:1 lazy transform (e.g. ALP over
Bitpacked produces `LazyAlp(MaterializedXxx)`).

### Unknown encodings

Files containing unrecognized encoding IDs throw `VortexException` by default. The message names
which [edition](#editions) the id belongs to (and whether it's an `unstable` draft with no
compatibility guarantee), or says the id is unknown to every edition if it belongs to none. Opt
in to passthrough mode to read such files without failing:

```java
ReadRegistry registry = ReadRegistry.builder()
        .registerDefaults()
        .allowUnknown()
        .build();
try (VortexReader vf = VortexReader.open(path, registry)) {
    // columns with unknown encodings are returned as UnknownArray
}
```

## Editions

Frozen, named, additive sets of encoding IDs, each carrying a forever read-compatibility
guarantee once frozen (ADR 0023) — a write-time/read-time policy, not part of the wire format.
`WriteOptions.defaults()` targets the latest frozen `core` edition; see
[the reference doc](reference.md#editions) for the writer/reader integration.

| Edition | Family | Adds |
|---|---|---|
| `core2025.05.0` | `core` | 23 baseline: `fastlanes.bitpacked`/`for`, `vortex.alp`/`alprd`/`bool`/`bytebool`/`chunked`/`constant`/`datetimeparts`/`decimal`/`decimal_byte_parts`/`dict`/`ext`/`fsst`/`list`/`null`/`primitive`/`runend`/`sparse`/`struct`/`varbin`/`varbinview`/`zigzag` |
| `core2025.06.0` | `core` | `vortex.pco`, `vortex.sequence`, `vortex.zstd` |
| `core2025.10.0` | `core` | `fastlanes.rle`, `vortex.fixed_size_list`, `vortex.listview`, `vortex.masked` |
| `core2026.07.0` | `core` | `vortex.variant` |
| `unstable2025.05.0` | `unstable` | `fastlanes.delta` ✅ implemented |
| `unstable2026.02.0` | `unstable` | `vortex.zstd_buffers` ❌ not implemented |
| `unstable2026.04.0` | `unstable` | `vortex.parquet.variant` ❌, `vortex.patched` ✅, `vortex.tensor.*` (4 ids) ❌ |
| `unstable2026.06.0` | `unstable` | `vortex.onpair` ❌ not implemented |

`core` editions are frozen with a forever read-compatibility guarantee; `unstable` editions are
drafts with none. (Upstream additionally records each frozen `core` edition's minimum *Rust*
Vortex reader release — that number refers to a different implementation's release train, has no
relationship to vortex-java's own versioning, and isn't surfaced here or in any vortex-java API.)

## Extension types

Extension dtypes wrap a primitive storage array with a logical-id tag plus optional
metadata. The Rust catalog lives in
[`vortex-array/src/extension/`](https://github.com/vortex-data/vortex/tree/develop/vortex-array/src/extension);
each subdir below names a canonical extension id and its on-disk shape.

Read-side extensions live in `io.github.dfa1.vortex.reader.extension` (write-side encoders
implement `ExtensionEncoder` in the writer module). Each spec extension is a singleton
implementing the `ExtensionDecoder` interface, with typed decode methods on the concrete
impl — grab the singleton directly:

```java
DType.Extension dtype = (DType.Extension) schema.field("birthdays");
List<LocalDate> values = DateExtensionDecoder.INSTANCE.decodeAll(chunk.column("birthdays"));
```

End-to-end round-trip — write a `List<LocalDate>`, read it back:

```java
var schema = DType.structBuilder()
        .field("birthdays", DateExtensionDecoder.INSTANCE.dtype(false))
        .build();
writer.writeChunk(c -> c.put(ColumnName.of("birthdays"), dates));              // Collection auto-routed

try (var iter = reader.scan(ScanOptions.all());
     Chunk chunk = iter.next()) {
    List<LocalDate> back = chunk.as("birthdays", LocalDate.class);
}
```

`Chunk.as(name, Class)` hides the per-extension decode dispatch for the four
spec extensions (`LocalDate` ↔ `vortex.date`, `LocalTime` ↔ `vortex.time`,
`Instant` ↔ `vortex.timestamp`, `UUID` ↔ `vortex.uuid`); the dispatch is closed over the
spec set. Third-party extensions call their own impl's typed methods directly.

`ExtensionId` is the enum of known spec ids (`VORTEX_DATE`, `VORTEX_TIME`,
`VORTEX_TIMESTAMP`, `VORTEX_UUID`). Unknown wire ids on `DType.Extension`
round-trip verbatim through the raw `String` field — the registry simply
returns `null` for them and callers can read the storage column directly.

| Extension id        | Impl                   | Storage                                         | Metadata                                  | Round-trip |
|---------------------|------------------------|-------------------------------------------------|-------------------------------------------|------------|
| `vortex.date`       | `DateExtension`        | Signed integer days since 1970-01-01            | none                                      | ✅          |
| `vortex.time`       | `TimeExtension`        | I32 (s/ms) or I64 (μs/ns) since midnight        | 1 byte: `TimeUnit`                        | ✅          |
| `vortex.timestamp`  | `TimestampExtension`   | I64 epoch count in the recorded `TimeUnit`      | unit byte + u16 LE tz_len + UTF-8 tz      | ✅          |
| `vortex.uuid`       | `UuidExtension`        | `FixedSizeList(Primitive(U8), 16)`              | none                                      | ✅          |
| _custom ids_        | _none_                 | _whatever the column declares_                  | _opaque bytes_                            | passthrough |

`TimeUnit` (see [`extension/datetime/unit.rs`](https://github.com/vortex-data/vortex/blob/develop/vortex-array/src/extension/datetime/unit.rs))
encodes precision in the first metadata byte:

| Value | Unit         |
|-------|--------------|
| 0     | Nanoseconds  |
| 1     | Microseconds |
| 2     | Milliseconds |
| 3     | Seconds      |
| 4     | Days         |

For unsupported extension ids the inspector falls back to a placeholder cell
(`<GenericArray ext<vortex.X>>`); the underlying storage array still decodes
correctly via the primitive accessors, callers just have to format the value
themselves.

## S3 Fixture Status (v0.75.0)

> **Note:** the oracle round-trip suite is pinned to `v0.75.0/` (current Rust
> release). The bucket reuses identical fixture file names across versions but
> rewrites the bytes, so the `/tmp/pco-fixtures` cache is version-keyed; bump the
> `FIXTURE_VERSION` constant in the integration tests and refresh this section
> when a newer set is published.

Cross-language round-trips tested against Rust-written fixture files hosted at
`s3://vortex-compat-fixtures/v0.75.0/arrays/`.

| Fixture                             | Status |
|-------------------------------------|--------|
| `primitives.vortex`                 | ✅      |
| `alp.vortex`                        | ✅      |
| `bitpacked.vortex`                  | ✅      |
| `booleans.vortex`                   | ✅      |
| `constant.vortex`                   | ✅      |
| `for.vortex`                        | ✅      |
| `fsst.vortex`                       | ✅      |
| `runend.vortex`                     | ✅      |
| `sequence.vortex`                   | ✅      |
| `varbin.vortex`                     | ✅      |
| `struct_nested.vortex`              | ✅      |
| `null.vortex`                       | ✅      |
| `bytebool.vortex`                   | ✅      |
| `zigzag.vortex`                     | ✅      |
| `datetime.vortex`                   | ✅      |
| `dict.vortex`                       | ✅      |
| `sparse.vortex`                     | ✅      |
| `varbinview.vortex`                 | ✅      |
| `chunked.vortex`                    | ✅      |
| `rle.vortex`                        | ✅      |
| `alprd.vortex`                      | ✅      |
| `decimal.vortex`                    | ✅      |
| `decimal_byte_parts.vortex`         | ✅      |
| `datetimeparts.vortex`              | ✅      |
| `list.vortex`                       | ✅      |
| `listview.vortex`                   | ✅      |
| `fixed_size_list.vortex`            | ✅      |
| `zstd.vortex`                       | ✅      |
| `tpch_lineitem.compact.vortex`      | ✅      |
| `tpch_lineitem.regular.vortex`      | ✅      |
| `tpch_orders.compact.vortex`        | ✅      |
| `tpch_orders.regular.vortex`        | ✅      |
| `pco.vortex`                        | ✅      |
| `clickbench_hits_5k.compact.vortex` | ✅      |
| `clickbench_hits_5k.regular.vortex` | ✅      |
| `masked.vortex`                     | ❓      | No fixture through v0.75.0 |
| `patched.vortex`                    | ❓      | No fixture through v0.75.0 |
| `variant.vortex`                    | ❓      | No fixture through v0.75.0 |
