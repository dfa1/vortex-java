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

- [ ] **#13 Cascading compressor — remaining follow-ups**
    - [ ] **Cross-compat test**: Add `JavaWritesRustReadsIntegrationTest` in the `integration` module.
      Write OHLC data with `WriteOptions.cascading(3)` via Java writer; read it back with the Rust JNI
      reader and assert values round-trip correctly. Same OHLC schema / dataset as `RustVsJavaWriteBenchmark`.
    - [ ] **Benchmark — cascading write variants**: In `RustVsJavaWriteBenchmark`, add:
        - `javaWriteCascading()` — same body as `javaWrite()` but use `WriteOptions.cascading(3)` instead of `WriteOptions.defaults()`
        - After both `javaWrite` and `javaWriteCascading` run, log the compressed-bytes ratio
          (`javaWriteCascading file size / javaWrite file size`) via a JMH `AuxCounters` or a
          `@TearDown` print so it appears in the benchmark output.
    - [ ] **Benchmark — cascading read variants**: In `RustVsJavaReadBenchmark`, add a
      `javaReadCascading()` method that reads a file written with `WriteOptions.cascading(3)`.
      Warm up the file in `@Setup`; measure decode throughput (rows/s). Compare against `javaRead()`
      (no cascading) and `jniRead()` to show the cascading cost/benefit at read time.

## Testing

- [ ] missing unit tests for VarBinArray
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

## Tooling


## Large-file support

- [ ] **#12 Test read/write of files > 2 GB**
    - [ ] Parquet baseline for comparison: same data should fail or require splitting when any
      column chunk exceeds 2 GB.

## Array API

- [ ] Optional `vortex-arrow` bridge module for Arrow ecosystem interop
    - Primary API stays `ArrayLong`/`ArrayDouble` (zero-copy, no deps, no Unsafe)
    - Bridge wraps typed views into Arrow `BigIntVector`, `Float8Vector`, etc. for users who need
      Arrow Flight / DuckDB ADBC / pandas interop
    - Conversion involves a copy (MemorySegment → Arrow off-heap buffer) — cost is explicit and opt-in
    - Arrow JVM uses `sun.misc.Unsafe` / Netty internally; keeping it in a separate module means
      the core library stays Unsafe-free

## Encodings

### Implemented

| Encoding ID               | Class               | Decode | Encode | Encode effort | Dtypes supported |
|------------------------|---------------------|--------|--------|---------------|------------------|
| `vortex.primitive`     | `PrimitiveEncoding`    | ✅ | ✅ | — | all `PType` (I8–I64, U8–U64, F32, F64) |
| `vortex.bool`          | `BoolEncoding`         | ✅ | ✅ | — | Bool (bit-packed) |
| `vortex.dict`          | `DictEncoding`         | ✅ | ✅ | — | Primitive (VarBin via dict.vortex blocked by VarBinView) |
| `fastlanes.delta`      | `DeltaEncoding`        | ✅ | ✅ | — | integer PTypes |
| `fastlanes.bitpacked`  | `BitpackedEncoding`    | ✅ | ✅ | — | unsigned integer PTypes |
| `vortex.null`          | `NullEncoding`         | ✅ | ✅ | — | Null |
| `vortex.bytebool`      | `ByteBoolEncoding`     | ✅ | trivial | `boolean[]` → 1 byte/elem | Bool |
| `vortex.zigzag`        | `ZigZagEncoding`       | ✅ | trivial | `(v<<1)^(v>>63)`, delegate | signed integer PTypes |
| `fastlanes.for`        | `FrameOfReferenceEncoding` | ✅ | low | find min, emit deltas child | integer PTypes |
| `vortex.runend`        | `RunEndEncoding`       | ✅ | low | scan runs → ends + values arrays | Primitive, Utf8/Binary, Bool |
| `vortex.constant`      | `ConstantEncoding`     | ✅ | low | validate uniform, emit `ScalarValue` proto | Primitive, Utf8, Binary, Bool, Null, Decimal, Extension |
| `vortex.sparse`        | `SparseEncoding`       | ✅ | medium | collect non-fill indices + values; needs fill-value detection | Primitive |
| `vortex.varbin`        | `VarBinEncoding`       | ✅ | medium | offsets buf + bytes buf + `VarBinMetadata` proto | Utf8, Binary |
| `vortex.sequence`      | `SequenceEncoding`     | ✅ | ❌ stub | detect arithmetic progression (base + i×step) | Primitive |
| `vortex.struct`        | `StructEncoding`       | ✅ | medium | encode each field, emit children | Struct |
| `vortex.ext`           | `ExtEncoding`          | ✅ | ✅ | — | Extension |
| `vortex.alp`           | `AlpEncoding`          | ✅ | hard | ALP float quantization + patch residuals | F64, F32 |
| `vortex.fsst`          | `FsstEncoding`         | ✅ | hard | FSST symbol-table building | Utf8, Binary |
| `vortex.varbinview`    | `VarBinViewEncoding`   | ✅ | hard | 16-byte view layout + inline vs heap split | Utf8, Binary |
| `vortex.pco`           | `PcoEncoding`          | ❌ stub | ❌ | very hard — ANS + bin tokenization not ported | Primitive |

### Missing

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
| `zstd.vortex`                    | ❌     | needs zstd native library     |
| `tpch_lineitem.compact.vortex`   | ❌     | `vortex.pco`                  |
| `tpch_lineitem.regular.vortex`   | ❌     | `vortex.pco`                  |
| `tpch_orders.compact.vortex`     | ❌     | `vortex.pco`                  |
| `tpch_orders.regular.vortex`     | ❌     | `vortex.pco`                  |
| `pco.vortex`                     | ❌     | `vortex.pco`                  |
| `clickbench_hits_5k.compact.vortex` | ❌  | `vortex.pco`                  |
| `clickbench_hits_5k.regular.vortex` | ❌  | `vortex.pco`                  |

**Score: 18/35** (including `for.vortex` scanned separately from `scan_fixture_decodesAllRows`)


