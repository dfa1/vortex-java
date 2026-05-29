# Encoding Coverage

## Implemented

| Encoding ID               | Class               | Dtypes supported                        |
|------------------------|---------------------|-----------------------------------------|
| `vortex.primitive`     | `PrimitiveEncoding`    | all `PType` (I8–I64, U8–U64, F32, F64) |
| `vortex.bool`          | `BoolEncoding`         | Bool (bit-packed)                       |
| `vortex.struct`        | `StructEncoding`       | Struct (single-field unwrap + multi-field → StructArray) |
| `vortex.constant`      | `ConstantEncoding`     | Primitive, Utf8, Binary, Bool, Null, Decimal, Extension |
| `vortex.dict`          | `DictEncoding`         | Utf8/Binary values (VarBin only; VarBinView blocks dict.vortex) |
| `vortex.sparse`        | `SparseEncoding`       | Primitive (VarBinView blocks sparse.vortex) |
| `vortex.varbin`        | `VarBinEncoding`       | Utf8, Binary                            |
| `vortex.fsst`          | `FsstEncoding`         | Utf8, Binary                            |
| `vortex.runend`        | `RunEndEncoding`       | Primitive, Utf8/Binary, Bool            |
| `vortex.sequence`      | `SequenceEncoding`     | Primitive                               |
| `vortex.alp`           | `AlpEncoding`          | F64, F32                                |
| `fastlanes.bitpacked`  | `BitpackedEncoding`    | unsigned integer PTypes                 |
| `fastlanes.for`        | `FrameOfReferenceEncoding` | integer PTypes                      |
| `fastlanes.delta`      | `DeltaEncoding`        | integer PTypes                          |
| `vortex.pco`           | `PcoEncoding`          | stub — throws (ANS + bin tokenization not ported) |

## Missing

| Encoding ID                   | Effort  | Unblocks                                        |
|----------------------------|---------|-------------------------------------------------|
| `vortex.null`              | trivial | `null.vortex`                                   |
| `vortex.bytebool`          | low     | `bytebool.vortex`                               |
| `vortex.zigzag`            | low     | `zigzag.vortex`                                 |
| `vortex.ext`               | low     | `datetime.vortex` (transparent storage wrapper) |
| `vortex.varbinview`        | medium  | `varbinview.vortex`, `dict.vortex`, `sparse.vortex` |
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
| `null.vortex`                    | ❌     | `vortex.null` not registered  |
| `bytebool.vortex`                | ❌     | `vortex.bytebool` missing     |
| `zigzag.vortex`                  | ❌     | `vortex.zigzag` missing       |
| `datetime.vortex`                | ❌     | `vortex.ext` missing          |
| `dict.vortex`                    | ❌     | `vortex.varbinview` missing   |
| `sparse.vortex`                  | ❌     | `vortex.varbinview` missing   |
| `varbinview.vortex`              | ❌     | `vortex.varbinview` missing   |
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

**Score: 11/35** (including `for.vortex` scanned separately from `scan_fixture_decodesAllRows`)
