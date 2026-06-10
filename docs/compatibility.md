# Compatibility

Tested against the [Rust reference implementation](https://github.com/vortex-data/vortex) v0.72.0.
For the rest of the API surface (reader, writer, scan, CLI), see [reference.md](reference.md).

## Encodings

| Encoding ID                 | Class                      | Decode | Encode | Notes                                                                 |
|-----------------------------|----------------------------|--------|--------|-----------------------------------------------------------------------|
| `vortex.primitive`          | `PrimitiveEncoding`        | ✅      | ✅      | All `PType` (I8–I64, U8–U64, F32, F64)                                |
| `vortex.bool`               | `BoolEncoding`             | ✅      | ✅      | Bool (bit-packed)                                                     |
| `vortex.null`               | `NullEncoding`             | ✅      | ✅      | Null                                                                  |
| `vortex.bytebool`           | `ByteBoolEncoding`         | ✅      | ✅      | Bool (byte-per-element)                                               |
| `vortex.zigzag`             | `ZigZagEncoding`           | ✅      | ✅      | Signed integer PTypes                                                 |
| `vortex.constant`           | `ConstantEncoding`         | ✅      | ✅      | Primitive, Utf8, Binary, Bool, Null, Decimal, Extension               |
| `vortex.ext`                | `ExtEncoding`              | ✅      | ✅      | Extension                                                             |
| `vortex.runend`             | `RunEndEncoding`           | ✅      | ✅      | Primitive, Utf8/Binary, Bool                                          |
| `vortex.varbin`             | `VarBinEncoding`           | ✅      | ✅      | Utf8, Binary                                                          |
| `vortex.varbinview`         | `VarBinViewEncoding`       | ✅      | ✅      | Utf8, Binary                                                          |
| `vortex.alp`                | `AlpEncoding`              | ✅      | ✅      | F64, F32                                                              |
| `vortex.alprd`              | `AlpRdEncoding`            | ✅      | ✅      | F64, F32                                                              |
| `vortex.dict`               | `DictEncoding`             | ✅      | ✅      | Primitive, Utf8/Binary                                                |
| `vortex.sparse`             | `SparseEncoding`           | ✅      | ✅      | Primitive                                                             |
| `vortex.sequence`           | `SequenceEncoding`         | ✅      | ✅      | Primitive                                                             |
| `vortex.struct`             | `StructEncoding`           | ✅      | ✅      | Struct                                                                |
| `vortex.chunked`            | `ChunkedEncoding`          | ✅      | ✅      | Primitive + Struct concat                                             |
| `vortex.fsst`               | `FsstEncoding`             | ✅      | ✅      | Utf8, Binary                                                          |
| `vortex.list`               | `ListEncoding`             | ✅      | ✅      |                                                                       |
| `vortex.listview`           | `ListViewEncoding`         | ✅      | ✅      |                                                                       |
| `vortex.fixed_size_list`    | `FixedSizeListEncoding`    | ✅      | ✅      |                                                                       |
| `vortex.zstd`               | `ZstdEncoding`             | ✅      | ✅      | Primitive, Utf8, Binary                                               |
| `vortex.masked`             | `MaskedEncoding`           | ✅      | ❌      | Encode not yet implemented                                            |
| `vortex.decimal`            | `DecimalEncoding`          | ✅      | ✅      |                                                                       |
| `vortex.decimal_byte_parts` | `DecimalBytePartsEncoding` | ✅      | ✅      |                                                                       |
| `vortex.datetimeparts`      | `DateTimePartsEncoding`    | ✅      | ✅      |                                                                       |
| `vortex.pco`                | `PcoEncoding`              | ✅      | ❌      | Decode: all modes; encode not yet implemented                         |
| `fastlanes.bitpacked`       | `BitpackedEncoding`        | ✅      | ✅      | Unsigned integer PTypes                                               |
| `fastlanes.delta`           | `DeltaEncoding`            | ✅      | ✅      | Integer PTypes                                                        |
| `fastlanes.for`             | `FrameOfReferenceEncoding` | ✅      | ✅      | Integer PTypes                                                        |
| `fastlanes.rle`             | `RleEncoding`              | ✅      | ✅      | Chunk-based RLE                                                       |
| `vortex.patched`            | `PatchedEncoding`          | ✅      | ❌      | Primitive PTypes; encode not yet implemented                          |
| `vortex.variant`            | `VariantEncoding`          | ✅      | ❌      | Encode not yet implemented                                            |

### Unknown encodings

Files containing unrecognised encoding IDs throw `VortexException` by default. Opt in to
passthrough mode to read such files without failing:

```java
Registry registry = Registry.builder()
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
var schema = new DType.Struct(List.of("birthdays"),
                              List.of(DateExtension.INSTANCE.dtype(false)), false);
writer.writeChunk(Map.of("birthdays", dates));                  // Collection auto-routed

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
