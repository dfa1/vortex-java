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
EncodingRegistry registry = EncodingRegistry.builder()
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

| Extension id        | Storage                                        | Metadata                                    | Java decoder                                  | Status |
|---------------------|-------------------------------------------------|---------------------------------------------|-----------------------------------------------|--------|
| `vortex.date`       | I32 (days) or I64 (ms) since Unix epoch         | 1 byte: `TimeUnit` (2 = ms, 4 = days)       | `Extensions.localDate(Array, long)`           | ✅      |
| `vortex.time`       | I32 (s/ms) or I64 (μs/ns) since midnight        | 1 byte: `TimeUnit`                          | _not yet_                                     | ❌      |
| `vortex.timestamp`  | I64 with `TimeUnit` (s/ms/μs/ns) + optional tz  | 1 byte unit + UTF-8 tz string (optional)    | _not yet_                                     | ❌      |
| `vortex.uuid`       | `FixedSizeList(Primitive(U8), 16)`              | 1 byte UUID version (optional, 0xff = unset) | _not yet_                                     | ❌      |

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
