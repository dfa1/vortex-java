# Encoding Coverage

## Implemented

| Encoding ID               | Class               | Decode | Encode | Encode effort | Dtypes supported |
|------------------------|---------------------|--------|--------|---------------|------------------|
| `vortex.primitive`     | `PrimitiveEncoding`    | ✅ | ✅ | — | all `PType` (I8–I64, U8–U64, F32, F64) |
| `vortex.bool`          | `BoolEncoding`         | ✅ | ✅ | — | Bool (bit-packed) |
| `vortex.dict`          | `DictEncoding`         | ✅ | ✅ | — | Primitive (VarBin via dict.vortex blocked by VarBinView) |
| `fastlanes.delta`      | `DeltaEncoding`        | ✅ | ✅ | — | integer PTypes |
| `fastlanes.bitpacked`  | `BitpackedEncoding`    | ✅ | ✅ | — | unsigned integer PTypes |
| `vortex.null`          | `NullEncoding`         | ✅ | trivial | no data to store | Null |
| `vortex.bytebool`      | `ByteBoolEncoding`     | ✅ | trivial | `boolean[]` → 1 byte/elem | Bool |
| `vortex.zigzag`        | `ZigZagEncoding`       | ✅ | trivial | `(v<<1)^(v>>63)`, delegate | signed integer PTypes |
| `fastlanes.for`        | `FrameOfReferenceEncoding` | ✅ | low | find min, emit deltas child | integer PTypes |
| `vortex.runend`        | `RunEndEncoding`       | ✅ | low | scan runs → ends + values arrays | Primitive, Utf8/Binary, Bool |
| `vortex.constant`      | `ConstantEncoding`     | ✅ | low | validate uniform, emit `ScalarValue` proto | Primitive, Utf8, Binary, Bool, Null, Decimal, Extension |
| `vortex.sparse`        | `SparseEncoding`       | ✅ | medium | collect non-fill indices + values; needs fill-value detection | Primitive |
| `vortex.varbin`        | `VarBinEncoding`       | ✅ | medium | offsets buf + bytes buf + `VarBinMetadata` proto | Utf8, Binary |
| `vortex.sequence`      | `SequenceEncoding`     | ✅ | medium | detect arithmetic progression (base + i×step) | Primitive |
| `vortex.struct`        | `StructEncoding`       | ✅ | medium | encode each field, emit children | Struct |
| `vortex.ext`           | `ExtEncoding`          | ✅ | medium | encode storage dtype, wrap with extension | Extension |
| `vortex.alp`           | `AlpEncoding`          | ✅ | hard | ALP float quantization + patch residuals | F64, F32 |
| `vortex.fsst`          | `FsstEncoding`         | ✅ | hard | FSST symbol-table building | Utf8, Binary |
| `vortex.varbinview`    | `VarBinViewEncoding`   | ✅ | hard | 16-byte view layout + inline vs heap split | Utf8, Binary |
| `vortex.pco`           | `PcoEncoding`          | ❌ stub | ❌ | very hard — ANS + bin tokenization not ported | Primitive |

## Missing

| Encoding ID                   | Effort  | Unblocks                                        |
|----------------------------|---------|-------------------------------------------------|
| `vortex.chunked`           | medium  | `chunked.vortex` (segment-level chunked array)  |
| `fastlanes.rle`            | medium  | `rle.vortex`                                    |
| `vortex.alprd`             | medium  | `alprd.vortex`                                  |
| `vortex.decimal`           | medium  | `decimal.vortex`                                |
| `vortex.decimal_byte_parts`| medium  | `decimal_byte_parts.vortex`                     |
| `vortex.datetimeparts`     | medium  | `datetimeparts.vortex`                          |
| `vortex.list`              | hard    | `list.vortex` (needs list array model)          |
| `vortex.listview`          | hard    | `listview.vortex`                               |
| `vortex.fixed_size_list`   | hard    | `fixed_size_list.vortex`                        |
| `vortex.zstd`              | hard    | `zstd.vortex` (needs zstd native lib)           |
| `vortex.pco` (full)        | very hard | `pco.vortex`, `tpch_*.vortex`, `clickbench_*.vortex` |

## S3 Fixture Status (`v0.72.0/arrays/`)

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
| `chunked.vortex`                 | ❌     | `vortex.chunked` at segment level |
| `rle.vortex`                     | ❌     | `fastlanes.rle` missing       |
| `alprd.vortex`                   | ❌     | `vortex.alprd` missing        |
| `decimal.vortex`                 | ❌     | `vortex.decimal` missing      |
| `decimal_byte_parts.vortex`      | ❌     | `vortex.decimal_byte_parts` missing |
| `datetimeparts.vortex`           | ❌     | `vortex.datetimeparts` missing |
| `list.vortex`                    | ❌     | `vortex.list` + list array model |
| `listview.vortex`                | ❌     | `vortex.listview` missing     |
| `fixed_size_list.vortex`         | ❌     | `vortex.fixed_size_list` missing |
| `zstd.vortex`                    | ❌     | needs zstd native library     |
| `tpch_lineitem.compact.vortex`   | ❌     | `vortex.pco`                  |
| `tpch_lineitem.regular.vortex`   | ❌     | `vortex.pco`                  |
| `tpch_orders.compact.vortex`     | ❌     | `vortex.pco`                  |
| `tpch_orders.regular.vortex`     | ❌     | `vortex.pco`                  |
| `pco.vortex`                     | ❌     | `vortex.pco`                  |
| `clickbench_hits_5k.compact.vortex` | ❌  | `vortex.pco`                  |
| `clickbench_hits_5k.regular.vortex` | ❌  | `vortex.pco`                  |

**Score: 18/35** (including `for.vortex` scanned separately from `scan_fixture_decodesAllRows`)
