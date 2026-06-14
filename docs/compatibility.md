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
  <version>0.6.0</version>
</dependency>

<!-- optional: inspector for layout-tree introspection -->
<dependency>
  <groupId>io.github.dfa1.vortex</groupId>
  <artifactId>vortex-inspector</artifactId>
  <version>0.6.0</version>
</dependency>
```

`./mvnw -pl core,reader,inspector verify` builds the read-only artifact set
without the writer module on the classpath. `ServiceLoader<EncodingDecoder>`
resolves only the standalone decoders in `reader`; no encoder class is loaded.

## Known wire-format gaps

| Item | Introduced | Java status |
|------|------------|-------------|
| `DType::Union` (`fbs.DType.Type.Union = 12`) | Rust 0.71.0 | ❌ Decode throws `VortexException("unsupported DType typeType=12")`. No `DType.Union` variant in Java's sealed type. |
| `vortex.onpair` experimental string encoding | Rust 0.74.0 | ❌ Not registered. Files using it fail to decode unless `Registry.allowUnknown()` is enabled. |
| `vortex.variant` write path | Rust 0.73.0 (`Allow writing Variant to files`, #7945) | ❌ Java decode works; Java encode throws `"encode not yet implemented"`. Java→Rust round-trip not possible for Variant columns. |
| Arrow extension array import affecting Variant shape | Rust 0.74.0 (#8125) | Untested. Re-run integration fixtures against v0.74.0 once published. |

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
| `vortex.masked`             | `MaskedEncodingDecoder`          | `MaskedEncodingEncoder`          | ✅      | ❌      | Encode not yet implemented                                            |
| `vortex.decimal`            | `DecimalEncodingDecoder`         | `DecimalEncodingEncoder`         | ✅      | ✅      |                                                                       |
| `vortex.decimal_byte_parts` | `DecimalBytePartsEncodingDecoder`| `DecimalBytePartsEncodingEncoder`| ✅      | ✅      |                                                                       |
| `vortex.datetimeparts`      | `DateTimePartsEncodingDecoder`   | `DateTimePartsEncodingEncoder`   | ✅      | ✅      |                                                                       |
| `vortex.pco`                | `PcoEncodingDecoder`             | `PcoEncodingEncoder`             | ✅      | ✅      | Decode: all modes. Encode: Classic + Consecutive delta + IntMult; FloatMult/FloatQuant deferred |
| `fastlanes.bitpacked`       | `BitpackedEncodingDecoder`       | `BitpackedEncodingEncoder`       | ✅      | ✅      | Unsigned integer PTypes                                               |
| `fastlanes.delta`           | `DeltaEncodingDecoder`           | `DeltaEncodingEncoder`           | ✅      | ✅      | Integer PTypes                                                        |
| `fastlanes.for`             | `FrameOfReferenceEncodingDecoder`| `FrameOfReferenceEncodingEncoder`| ✅      | ✅      | Integer PTypes                                                        |
| `fastlanes.rle`             | `RleEncodingDecoder`             | `RleEncodingEncoder`             | ✅      | ✅      | Chunk-based RLE                                                       |
| `vortex.patched`            | `PatchedEncodingDecoder`         | `PatchedEncodingEncoder`         | ✅      | ❌      | Primitive PTypes; encode not yet implemented                          |
| `vortex.variant`            | `VariantEncodingDecoder`         | `VariantEncodingEncoder`         | ✅      | ❌      | Decode (incl. shredded child); encode not yet implemented (Rust 0.73+) |
| `vortex.onpair`             | _none_                           | _none_                           | ❌      | ❌      | Experimental in Rust 0.74.0; not yet ported                            |

### Unknown encodings

Files containing unrecognised encoding IDs throw `VortexException` by default. Opt in to
passthrough mode to read such files without failing:

```java
ReadRegistry registry = ReadRegistry.builder()
        .registerServiceLoaded()
        .allowUnknown()
        .build();
try (VortexReader vf = VortexReader.open(path, registry)) {
    // columns with unknown encodings are returned as UnknownArray
}
```

## Extension types

Extension dtypes wrap a primitive storage array with a logical-id tag plus optional
metadata. The Rust catalogue lives in
[`vortex-array/src/extension/`](https://github.com/vortex-data/vortex/tree/develop/vortex-array/src/extension);
each subdir below names a canonical extension id and its on-disk shape.

Extensions live in `io.github.dfa1.vortex.extension`. Each spec extension is a
singleton implementing the `Extension` interface, with typed encode/decode
methods on the concrete impl. Resolve a column to its impl via
`Registry.lookup(ExtensionId)`, or grab the singleton directly:

```java
DType.Extension dtype = (DType.Extension) schema.field("birthdays");
List<LocalDate> values = DateExtension.INSTANCE.decodeAll(chunk.column("birthdays"));
```

End-to-end round-trip — write a `List<LocalDate>`, read it back:

```java
var schema = DType.structBuilder()
        .field("birthdays", DateExtension.INSTANCE.dtype(false))
        .build();
writer.writeChunk(c -> c.put("birthdays", dates));              // Collection auto-routed

try (var iter = reader.scan(ScanOptions.all());
     Chunk chunk = iter.next()) {
    List<LocalDate> back = chunk.as("birthdays", LocalDate.class);
}
```

`Chunk.as(name, Class)` hides the per-extension decode dispatch for the four
spec extensions (`LocalDate` ↔ `vortex.date`, `LocalTime` ↔ `vortex.time`,
`Instant` ↔ `vortex.timestamp`, `UUID` ↔ `vortex.uuid`). Third-party
extensions still go through `Registry.lookup(ExtensionId)` and the impl's own
typed methods.

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

## S3 Fixture Status (v0.72.0)

> **Note:** the fixture matrix below is locked to `v0.72.0/`. The Rust reference is
> now at `v0.74.0`; re-run the integration suite against `v0.74.0/arrays/` once
> upstream publishes the corresponding fixture set, and refresh this section.

Cross-language round-trips tested against Rust-written fixture files hosted at
`s3://vortex-compat-fixtures/v0.72.0/arrays/`.

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
| `masked.vortex`                     | ❓      | No fixture in v0.72.0 |
| `patched.vortex`                    | ❓      | No fixture in v0.72.0 |
| `variant.vortex`                    | ❓      | No fixture in v0.72.0 |
