# TODO

## Project

- [ ] Move project to a dedicated organization
- [ ] Create website
- [ ] Publish benchmarks
- [ ] Build something like hardwood.dev but for vortex files
- [ ] Publish to Maven Central (OSSRH/SONATYPE setup, GPG signing, coordinates, CI release pipeline)

## Performance

- [ ] **#10c Publish reproducible perf artifacts**
    - Capture JMH JSON + JFR profile alongside README table; cite hardware (CPU model), JDK build (`java -version`),
      and benchmark commit SHA so numbers don't rot silently.

## Testing

- [ ] a lot of tests are doing "new String(array.getBytes()", let's add a method there
- [ ] lots of repetitions like in every test
```java
   private static final DType I64 = new DType.Primitive(PType.I64, false);
	private static final ValueLayout.OfLong LE_LONG =
			ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
```
- [ ] fix weird output in CliIT
```
[INFO] Running io.github.dfa1.vortex.cli.CliIT
written: /var/folders/dq/w0lpx2tj70g0cgv4ckcyth740000gn/T/junit-724885696333023617/data.vortex  (15 B → 888 B, -5820.0% smaller, cascading depth 3)
```

## Build

- [ ] add BOM module
- [ ] deploy to maven central
- [ ] drop warnings about flatbuffers
```shell
[WARNING] ****************************************************************************************************************************************************
[WARNING] * Required filename-based automodules detected: [flatbuffers-java-25.2.10.jar]. Please don't publish this project to a public artifact repository! *
[WARNING] ****************************************************************************************************************************************************
```
- [ ] warnings about using dfa1 as module name
```
[WARNING] /Users/dfa/projects/vortex-java/csv/src/main/java/module-info.java:[1,17] module name component dfa1 should avoid terminal digits
```

## Documentation

- [ ] Format specification: byte-exact diagrams for file layout and each encoding, with annotated examples (Arrow spec style)

## Tooling


## Large-file support

- [ ] **#12 Test read/write of files > 2 GB**
    - [ ] Parquet baseline for comparison: same data should fail or require splitting when any
      column chunk exceeds 2 GB.

## Array API

- [ ] Use domain primitives (`UInt32`, `UInt64`, etc.) as value classes via Project Valhalla instead of raw `long`/`int`
    - See https://dfa1.github.io/articles/rethink-domain-primitives-with-valhalla
    - Candidates: `PType` integer kinds, buffer offsets, row indices, byte lengths
    - Goal: type-safety at zero cost (value class = no heap alloc, no boxing)

- [ ] Optional `vortex-arrow` bridge module for Arrow ecosystem interop
    - Primary API stays `ArrayLong`/`ArrayDouble` (zero-copy, no deps, no Unsafe)
    - Bridge wraps typed views into Arrow `BigIntVector`, `Float8Vector`, etc. for users who need
      Arrow Flight / DuckDB ADBC / pandas interop
    - Conversion involves a copy (MemorySegment → Arrow off-heap buffer) — cost is explicit and opt-in
    - Arrow JVM uses `sun.misc.Unsafe` / Netty internally; keeping it in a separate module means
      the core library stays Unsafe-free

## Encodings

### All Encodings

| Encoding ID                  | Class                      | Decode   | Encode   | Effort    | Dtypes / Notes |
|------------------------------|----------------------------|----------|----------|-----------|----------------|
| `vortex.primitive`           | `PrimitiveEncoding`        | ✅       | ✅       | —         | all `PType` (I8–I64, U8–U64, F32, F64) |
| `vortex.bool`                | `BoolEncoding`             | ✅       | ✅       | —         | Bool (bit-packed) |
| `vortex.null`                | `NullEncoding`             | ✅       | ✅       | —         | Null |
| `vortex.bytebool`            | `ByteBoolEncoding`         | ✅       | ✅       | —         | Bool |
| `vortex.zigzag`              | `ZigZagEncoding`           | ✅       | ✅       | —         | signed integer PTypes |
| `vortex.constant`            | `ConstantEncoding`         | ✅       | ✅       | —         | Primitive, Utf8, Binary, Bool, Null, Decimal, Extension |
| `vortex.ext`                 | `ExtEncoding`              | ✅       | ✅       | —         | Extension |
| `vortex.runend`              | `RunEndEncoding`           | ✅       | ✅       | —         | Primitive, Utf8/Binary, Bool |
| `vortex.varbin`              | `VarBinEncoding`           | ✅       | ✅       | —         | Utf8, Binary |
| `vortex.alp`                 | `AlpEncoding`              | ✅       | ✅       | —         | F64, F32 |
| `vortex.dict`                | `DictEncoding`             | ✅       | ✅       | —         | Primitive (VarBin via dict.vortex blocked by VarBinView) |
| `fastlanes.delta`            | `DeltaEncoding`            | ✅       | ✅       | —         | integer PTypes |
| `fastlanes.bitpacked`        | `BitpackedEncoding`        | ✅       | ✅       | —         | unsigned integer PTypes |
| `fastlanes.for`              | `FrameOfReferenceEncoding` | ✅       | ✅       | —         | integer PTypes |
| `vortex.sparse`              | `SparseEncoding`           | ✅       | ✅       | —         | Primitive |
| `vortex.sequence`            | `SequenceEncoding`         | ✅       | ✅       | —         | Primitive |
| `vortex.struct`              | `StructEncoding`           | ✅       | ❌ stub  | medium    | Struct — wire format trivial (no buffers/metadata, children = fields). Blockers: (1) no Java `data` type for structs — need `record StructData(List<Object> fieldArrays)`; (2) `CascadingCompressor` hardcodes primitive arrays (`dataLength`, `sliceSample`). Encoding itself: recurse per field + `remapBufferIndices` (already exists). |
| `vortex.fsst`                | `FsstEncoding`             | ✅       | ❌ stub  | —         | Utf8, Binary |
| `vortex.varbinview`          | `VarBinViewEncoding`       | ✅       | ❌ stub  | —         | Utf8, Binary |
| `vortex.pco`                 | `PcoEncoding` (stub)       | ❌       | ❌       | very hard | ANS + bin tokenization not ported; unblocks `pco.vortex`, tpch/clickbench fixtures |
| `vortex.chunked`             | —                          | ❌       | ❌       | medium    | unblocks `chunked.vortex` (segment-level chunked array) |
| `fastlanes.rle`              | —                          | ❌       | ❌       | medium    | unblocks `rle.vortex` |
| `vortex.alprd`               | —                          | ❌       | ❌       | medium    | unblocks `alprd.vortex` |
| `vortex.decimal`             | —                          | ❌       | ❌       | medium    | unblocks `decimal.vortex` |
| `vortex.decimal_byte_parts`  | —                          | ❌       | ❌       | medium    | unblocks `decimal_byte_parts.vortex` |
| `vortex.datetimeparts`       | —                          | ❌       | ❌       | medium    | unblocks `datetimeparts.vortex` |
| `vortex.list`                | —                          | ❌       | ❌       | hard      | needs list array model; unblocks `list.vortex` |
| `vortex.listview`            | —                          | ❌       | ❌       | hard      | unblocks `listview.vortex` |
| `vortex.fixed_size_list`     | —                          | ❌       | ❌       | hard      | unblocks `fixed_size_list.vortex` |
| `vortex.zstd`                | —                          | ❌       | ❌       | hard      | use [airlift/aircompressor](https://github.com/airlift/aircompressor) (pure Java zstd, no native); unblocks `zstd.vortex` |

### S3 Fixture Status (`v0.72.0/arrays/`)

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
| `zstd.vortex`                    | ❌     | `vortex.zstd` (use aircompressor) |
| `tpch_lineitem.compact.vortex`   | ❌     | `vortex.pco`                  |
| `tpch_lineitem.regular.vortex`   | ❌     | `vortex.pco`                  |
| `tpch_orders.compact.vortex`     | ❌     | `vortex.pco`                  |
| `tpch_orders.regular.vortex`     | ❌     | `vortex.pco`                  |
| `pco.vortex`                     | ❌     | `vortex.pco`                  |
| `clickbench_hits_5k.compact.vortex` | ❌  | `vortex.pco`                  |
| `clickbench_hits_5k.regular.vortex` | ❌  | `vortex.pco`                  |

**Score: 18/35** (including `for.vortex` scanned separately from `scan_fixture_decodesAllRows`)


