# TODO

## Project

- [ ] **[by 2026-06-06] Ask SpiralDB for feedback** — open GitHub discussion in `spiraldb/vortex`
  linking the Java impl (24/35 fixtures), asking: interest in an official Java reader? plans to
  stabilize encoding metadata proto? undocumented invariants? any JVM plans of their own?
  Check their Discord too. Decision gate: invest further only if signal is positive.
- [ ] Move project to a dedicated organization
- [ ] Create website
- [ ] Publish benchmarks
- [ ] Build something like hardwood.dev but for vortex files
- [ ] Publish to Maven Central (OSSRH/SONATYPE setup, GPG signing, coordinates, CI release pipeline)

## Performance

- [ ] Publish reproducible perf artifacts
    - Capture JMH JSON + JFR profile alongside README table; cite hardware (CPU model), JDK build (`java -version`),
      and benchmark commit SHA so numbers don't rot silently.
- [ ] performance tests must be peer reviewed
- [ ] run performance tests on other machines (I have access only to Apple M5)

## Testing

- [ ] **Security review + adversarial tests** — the reader parses untrusted binary input (file
  trailer, FlatBuffers, Protobuf, per-segment data). Attack surface:
    - Malformed trailer: wrong magic, negative lengths, offsets past EOF
    - FlatBuffer bombs: deeply nested layout trees, circular references, huge vectors
    - Proto bombs: enormous `values_len`/`indices_len` in metadata triggering OOM allocations
    - Integer overflows in offset arithmetic (`offset + length` wraps to negative)
    - Out-of-bounds buffer reads via crafted `bufferIndices` arrays
    - Zip-bomb style: tiny file that claims huge row counts
  Add a fuzz corpus of malformed `.vortex` files; all must throw `VortexException`, never
  `ArrayIndexOutOfBoundsException`, `NegativeArraySizeException`, or `OutOfMemoryError`.
- [ ] **Verify proto compatibility with upstream** — `dtype.proto` and `scalar.proto` exist in
  `spiraldb/vortex/vortex-proto/proto/` and should be kept in sync with our copies. Encoding
  metadata (e.g. `RLEMetadata`, `RunEndMetadata`) has no upstream `.proto`; tag mismatches
  silently produce zero/default values in proto3. Add value-level assertions (not just
  `rowCount > 0`) to integration tests to catch silent corruption.
- [ ] **Verify ListEncoding and ListViewEncoding metadata fields** — integration tests currently
  only assert `rowCount > 0`. Add assertions that verify the decoded proto fields match
  the fixture: `elements_len` equals actual element count, `offset_ptype` / `size_ptype` match
  the PType used by the offsets/sizes child arrays. Catches silent proto tag mismatches
  (proto3 defaults to 0, which maps to `PType.U8` — wrong type decodes silently instead of failing).
- [ ] lots of repetitions like in every test
```java
   private static final DType I64 = new DType.Primitive(PType.I64, false);
	private static final ValueLayout.OfLong LE_LONG =
			ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
```

## Build

- [ ] prefix all modules with "vortex-"
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

- [ ] Use Diátaxis (https://diataxis.fr/) to structure docs: tutorials, how-to guides, reference, explanation
- [ ] Format specification: byte-exact diagrams for file layout and each encoding, with annotated examples (Arrow spec style)
- [ ] how to use the library and the cli

## Tooling

- [ ] hardwood to convert parquet files to vortex
- [ ] Optional `vortex-arrow` bridge module for Arrow ecosystem interop
    - Primary API stays `ArrayLong`/`ArrayDouble` (zero-copy, no deps, no Unsafe)
    - Bridge wraps typed views into Arrow `BigIntVector`, `Float8Vector`, etc. for users who need
      Arrow Flight / DuckDB ADBC / pandas interop
    - Conversion involves a copy (MemorySegment → Arrow off-heap buffer) — cost is explicit and opt-in
    - Arrow JVM uses `sun.misc.Unsafe` / Netty internally; keeping it in a separate module means
      the core library stays Unsafe-free

## Large-file support

- [ ] **Test read/write of files > 2 GB**
    - [ ] Parquet baseline for comparison: same data should fail or require splitting when any
      column chunk exceeds 2 GB.

## API

- [ ] Use domain primitives (`UInt32`, `UInt64`, etc.) as value classes via Project Valhalla instead of raw `long`/`int`
    - See https://dfa1.github.io/articles/rethink-domain-primitives-with-valhalla
    - Candidates: `PType` integer kinds, buffer offsets, row indices, byte lengths
    - Goal: type-safety at zero cost (value class = no heap alloc, no boxing)


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
| `vortex.struct`              | `StructEncoding`           | ✅       | ✅       | —         | Struct |
| `vortex.fsst`                | `FsstEncoding`             | ✅       | ❌ stub  | —         | Utf8, Binary |
| `vortex.varbinview`          | `VarBinViewEncoding`       | ✅       | ❌ stub  | —         | Utf8, Binary |
| `vortex.pco`                 | `PcoEncoding` (stub)       | ❌       | ❌       | very hard | ANS + bin tokenization not ported; unblocks `pco.vortex`, tpch/clickbench fixtures |
| `vortex.chunked`             | `ChunkedEncoding`          | ✅       | ✅       | medium    | decode: primitive + struct concat; encode via ChunkedData |
| `fastlanes.rle`              | `RleEncoding`              | ✅       | ✅       | —         | chunk-based RLE; offset always < 1024 |
| `vortex.alprd`               | —                          | ❌       | ❌       | hard      | ALP-RD: splits float bit pattern into left (dict-compressed, ≤8 entries, 3-bit indices) + right (bitpacked residuals); split point per-array in metadata; two separately-encoded children; harder than ALP; unblocks `alprd.vortex` |
| `vortex.decimal`             | `DecimalEncoding`          | ✅       | ✅       | —         | Decimal |
| `vortex.decimal_byte_parts`  | `DecimalBytePartsEncoding` | ✅       | ✅       | —         | Decimal byte parts |
| `vortex.datetimeparts`       | `DateTimePartsEncoding`    | ✅       | ❌ stub  | —         | Timestamp parts |
| `vortex.list`                | `ListEncoding`             | ✅       | ✅       | —         | two children: elements + offsets (I64); `ListArray`; cascadable offsets via `decodeChildAs` |
| `vortex.listview`            | —                          | ❌       | ❌       | hard      | unblocks `listview.vortex` |
| `vortex.fixed_size_list`     | `FixedSizeListEncoding`    | ✅       | ✅       | —         | one child: flat elements; no offsets |
| `vortex.zstd`                | `ZstdEncoding`             | ✅       | ✅       | —         | Primitive, Utf8, Binary (no dict, no nullable); uses airlift/aircompressor |

### `vortex.zstd` known limitations

- [ ] **Nullable arrays (decode)** — `ZstdEncoding.Decoder` throws when `node.children().length > 0` (validity child present).
  Fix: if child[0] exists, decode it as a validity bitmap (Bool array). Zstd stores only the _valid_ values compactly — after decompressing, scatter them back into a full-length array using the validity positions. For strings, reconstruct null slots as zero-length entries or handle at the `VarBinArray` level.

- [ ] **Dictionary support (decode)** — `ZstdEncoding.Decoder` throws when `metadata.dictionary_size != 0`.
  Fix: when `dictionary_size > 0`, buffer[0] is the dictionary bytes and frames start at buffer[1]. Pass dict bytes to `new ZstdDecompressor()` — check if aircompressor supports dictionary-mode decompression; if not, switch to `com.github.luben:zstd-jni` for this path only (native, dictionary-trained zstd is hard to replicate in pure Java).

- [ ] **Multi-frame encode** — `ZstdEncoding.Encoder` always produces a single frame for the whole array.
  Fix: accept a `valuesPerFrame` parameter (default: all values in one frame). Split the raw byte buffer at frame boundaries (`valuesPerFrame * byteWidth`), compress each slice independently, emit one `ZstdFrameMetadata` per frame. Enables partial decompression during slice scans.

- [ ] **Nullable arrays (encode)** — `ZstdEncoding.Encoder` has no null handling.
  Fix: accept nullable input (e.g. `Integer[]` or a validity mask alongside the data array). Strip null positions before compression. Encode the validity bitmap as a Bool child (child[0]) in the `EncodeNode`. Mirrors what Rust does: only valid values go into the compressed payload.

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
| `chunked.vortex`                 | ✅     |                               |
| `rle.vortex`                     | ✅     |                               |
| `alprd.vortex`                   | ❌     | `vortex.alprd` missing        |
| `decimal.vortex`                 | ✅     |                               |
| `decimal_byte_parts.vortex`      | ✅     |                               |
| `datetimeparts.vortex`           | ✅     |                               |
| `list.vortex`                    | ✅     |                               |
| `listview.vortex`                | ❌     | `vortex.listview` missing     |
| `fixed_size_list.vortex`         | ✅     |                               |
| `zstd.vortex`                    | ✅     |                               |
| `tpch_lineitem.compact.vortex`   | ❌     | `vortex.pco`                  |
| `tpch_lineitem.regular.vortex`   | ❌     | `vortex.pco`                  |
| `tpch_orders.compact.vortex`     | ❌     | `vortex.pco`                  |
| `tpch_orders.regular.vortex`     | ❌     | `vortex.pco`                  |
| `pco.vortex`                     | ❌     | `vortex.pco`                  |
| `clickbench_hits_5k.compact.vortex` | ❌  | `vortex.pco`                  |
| `clickbench_hits_5k.regular.vortex` | ❌  | `vortex.pco`                  |

**Score: 23/35** (including `for.vortex` scanned separately from `scan_fixture_decodesAllRows`)


