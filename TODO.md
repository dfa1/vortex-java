# TODO

## Project

- [ ] **[by 2026-06-03 evening] Ask SpiralDB for feedback** — open GitHub discussion in `spiraldb/vortex`
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
- [ ] minimize `ctx.arena().allocate(...)` calls — prefer in-place decode when child buffer is writable (already done in ALP); audit all decoders for unnecessary off-heap allocs
- [ ] **Evaluate Vector API (JEP 469+) for hot decode loops** — candidates: FastLanes bitpacked unpack,
  FrameOfReference add-base, ZigZag decode, ALP F64 reconstruction, future pco offset+base loop. Measure
  vs scalar baseline with JMH; only adopt where speedup is material and code stays readable. Pin against
  a specific JDK build since Vector API is incubating until Valhalla lands.

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
- [ ] **Add missing Java-writes→Rust-reads interop tests** — `JavaWritesRustReadsIntegrationTest`
  currently covers: `vortex.primitive` (I32/I64/F32/F64/F16), `vortex.varbin`, `vortex.dict`,
  `vortex.fsst`, `fastlanes.*`/`vortex.alp`/`vortex.alprd` (via cascading OHLC).
  Missing encodings (prioritised by byte-ordering / proto risk):
    - **`vortex.varbinview`** — 16-byte view struct has inlined data + prefix field; byte-order bug
      would survive Java-only round-trip but break Rust read
    - **`vortex.list` / `vortex.listview`** — proto tag mismatch already found once (U32 not U16);
      write path exercises `elements_len` + `offset_ptype` serialisation
    - **`vortex.zstd`** — frame format + optional dict; Rust strict about frame magic
    - **`vortex.sparse`** — patches offset encoding has endianness assumptions
    - **`vortex.zigzag`** — signed int codec, simple but untested end-to-end
    - **`vortex.runend`** — RLE with varying ptypes
    - **`vortex.bool` / `vortex.bytebool`** — boolean columns
    - **`vortex.constant`** — constant-value arrays
    - **`vortex.null`** — null columns
    - **`fastlanes.rle`** — RLE encoding
  Pattern: `VortexWriter.create(ch, SCHEMA, WriteOptions.defaults(), List.of(new XxxEncoding()))`
  then `readStringColumn` / `readLongColumn` via JNI. Run with `./mvnw verify -pl integration -am`.
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
| `vortex.fsst`                | `FsstEncoding`             | ✅       | ✅       | —         | Utf8, Binary (bigram symbol table) |
| `vortex.varbinview`          | `VarBinViewEncoding`       | ✅       | ✅       | —         | Utf8, Binary |
| `vortex.pco`                 | `PcoEncoding` (stub)       | ❌       | ❌       | very hard | pure-Java port feasible (tANS = table lookup, modes = scalar arith, no SIMD/Unsafe needed); see plan below. Unblocks `pco.vortex`, tpch/clickbench fixtures |
| `vortex.chunked`             | `ChunkedEncoding`          | ✅       | ✅       | medium    | decode: primitive + struct concat; encode via ChunkedData |
| `fastlanes.rle`              | `RleEncoding`              | ✅       | ✅       | —         | chunk-based RLE; offset always < 1024 |
| `vortex.alprd`               | `AlpRdEncoding`            | ✅       | ✅       | —         | F64, F32; left ≤16 bits dict-coded (≤8 entries), right bitpacked; exceptions as patches |
| `vortex.decimal`             | `DecimalEncoding`          | ✅       | ✅       | —         | Decimal |
| `vortex.decimal_byte_parts`  | `DecimalBytePartsEncoding` | ✅       | ✅       | —         | Decimal byte parts |
| `vortex.datetimeparts`       | `DateTimePartsEncoding`    | ✅       | ✅       | —         | Timestamp parts |
| `vortex.list`                | `ListEncoding`             | ✅       | ✅       | —         | two children: elements + offsets (I64); `ListArray`; cascadable offsets via `decodeChildAs` |
| `vortex.listview`            | `ListViewEncoding`         | ✅       | ✅       | —         | three children: elements + offsets (len N) + sizes (len N); fixture uses U16 for both |
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

### `vortex.pco` implementation plan

Pure-Java decode port. No JNI. Skip encode (no consumer needs it).

**Refs**: [pcodec format.md](https://github.com/pcodec/pcodec/blob/main/docs/format.md),
[Rust vortex-pco](https://github.com/vortex-data/vortex/tree/develop/encodings/pco/src),
[pcodec repo](https://github.com/pcodec/pcodec), [paper](https://arxiv.org/html/2502.06112v1).

**Wire format** (Vortex layer):
- Metadata proto: `PcoMetadata { bytes header=1; repeated PcoChunkInfo chunks=2; }`,
  `PcoChunkInfo { repeated PcoPageInfo pages=1; }`, `PcoPageInfo { uint32 n_values=1; }`.
- Buffers: `chunk_metas[0..N]` then `pages[0..M]`. Optional child[0] = validity.
- Pco encodes only valid values; scatter back into full-length output on decode.

**Wire format** (pcodec layer):
- Header: 2 bytes (major.minor format version).
- Chunk meta: mode (4b) + extra mode bits + delta encoding (4b) + extra delta bits +
  per latent: `ans_size_log` (4b) + bin count (15b) + per bin `{weight-1, lower_bound, offset_bits}`.
- Page: initial latent state (delta `state_n` + 4 tANS state indices) → byte align →
  per 256-batch: tANS-decoded bin indices + offset bits.
- All bit packing little-endian.
- Modes: Classic, IntMult, FloatMult, FloatQuant, Dict.
- Deltas: None, Consecutive, Lookback, Conv1.

**Phases**:
- [ ] **Phase 0 — scoping**. Inspect `pco.vortex`, `tpch_lineitem.compact.vortex`,
  `tpch_orders.compact.vortex`, `clickbench_hits_5k.compact.vortex` via VortexInspector.
  Dump mode/delta/ptype per chunk. Drives prioritization.
- [ ] **Phase 1 — bit reader + header + proto**. New `encoding/pco/` pkg. `LeBitReader` over
  `MemorySegment`. Add `PcoMetadata`/`PcoChunkInfo`/`PcoPageInfo` to `encoding.proto`.
  `PcoEncoding.Decoder` skeleton.
- [ ] **Phase 2 — Classic mode, no delta, single chunk/page, non-null, I64**. `ChunkMetaReader`,
  tANS table builder (port from `pcodec/src/ans/`), `PageDecoder`, scalar reconstruct
  `value = bin.lower + offset`. Allocate output via `ctx.arena()`.
- [ ] **Phase 3 — all ptypes**. I16/I32/U16/U32/U64/F16/F32/F64.
- [ ] **Phase 4 — delta Consecutive**. Initial state read + cumulative sum.
- [ ] **Phase 5 — other modes per Phase 0 findings**. Likely FloatMult, IntMult, FloatQuant.
  Defer Dict/Lookback/Conv1 until fixture demands; fail fast with named mode in `VortexException`.
- [ ] **Phase 6 — multi-chunk, multi-page, nullable**. Iterate chunks; per-chunk decompressor;
  validity child decode via registry; scatter valid values into output.
- [ ] **Phase 7 — integration tests**. Add 4 blocked fixtures to `RustWritesJavaReadsIntegrationTest`
  with value-level assertions, not just `rowCount > 0`. Property test via pcodec CLI fixture-gen
  with `tries` low.
- [ ] **Phase 8 — close out**. Drop pco row from blocker list; update score 31/35 → 35/35;
  document supported modes/deltas + version range in `PcoEncoding` javadoc.

**Risks**:
- tANS state machine: subtle. Port table-build line-by-line from Rust; test byte-exact on toy inputs.
- Format version drift (pcodec pre-1.0). Pin to fixture-declared version; reject others explicitly.
- Mode coverage unknown until Phase 0. Dict would jump scope.

**Estimate**: ~12 working days full; ~6 days for Classic+Consecutive only (likely insufficient
for float fixtures).

### `vortex.pco` encode plan

Pure-Java encode. Only after decode is stable + a Java consumer asks for write. Not gated
on any S3 fixture (all fixtures are Rust-produced; decode unblocks them).

**Refs**: [pco/src/wrapped/chunk_compressor.rs](https://github.com/pcodec/pcodec/blob/main/pco/src/wrapped/chunk_compressor.rs),
[pco/src/bin_optimization.rs](https://github.com/pcodec/pcodec/blob/main/pco/src/bin_optimization.rs),
[pco/src/histograms.rs](https://github.com/pcodec/pcodec/blob/main/pco/src/histograms.rs),
[pco/src/ans/](https://github.com/pcodec/pcodec/tree/main/pco/src/ans),
[pco/src/sampling.rs](https://github.com/pcodec/pcodec/blob/main/pco/src/sampling.rs).

**Why harder than decode**:
- Encode chooses mode + bin layout + tANS weights; decode just executes a fixed program.
- Bin optimization is dynamic programming over partitions of a histogram (`bin_cost`).
- tANS encoding table differs from decode table (weight quantization → symbol table).
- Mode selection samples input, trial-compresses against candidates (Classic, FloatMult,
  IntMult, FloatQuant), picks best ratio. See `sampling.rs`.
- No oracle: encode is non-deterministic. Validation = round-trip Java→Java decode AND
  Java→Rust decode (existing `JavaWritesRustReadsIntegrationTest` harness).

**Reuse from decode**:
- `LeBitReader` (decode) ↔ `LeBitWriter` (encode, new). Same bit layout, opposite direction.
- tANS table structure (decode-built) ↔ tANS encode table (`ans/encoding.rs`).
- Mode constants, delta constants, proto types — shared.
- Bit-exact wire format already validated by decode tests; encode just emits same bytes.

**Phases**:
- [ ] **Phase E0 — gate**. Is there a consumer (CLI write path, vortex-arrow bridge)? If no,
  stop. Decode is enough.
- [ ] **Phase E1 — bit writer**. `LeBitWriter` over `Arena`-backed `MemorySegment`. Mirrors
  `pco/src/bit_writer.rs`. Property test: random bit sequences round-trip via `LeBitReader`.
- [ ] **Phase E2 — Classic mode, no delta, fixed bins, no optimization**. Hardcoded bin layout
  + uniform tANS weights. Emits a valid (suboptimal) pco stream. Validates: header write,
  chunk meta write, page write, byte alignment. Round-trip via Java decode.
- [ ] **Phase E3 — histogram + bin optimization**. Port `histograms.rs` (sort + bucket by
  latent prefix) and `bin_optimization.rs` (DP partitioning, `bin_cost`, `log2_approx`).
  Replace fixed bins with optimized layout. Compression ratio benchmark vs Rust on same
  input — accept if Java within 5% of Rust ratio.
- [ ] **Phase E4 — tANS weight quantization + encoding table**. Port `ans/spec.rs` weight
  quantizer and `ans/encoding.rs` symbol-table builder. Critical: ANS state values must
  match what Rust decoder expects.
- [ ] **Phase E5 — delta Consecutive encoder**. Compute consecutive differences; store
  initial state at page head. Mirrors Phase 4 of decode.
- [ ] **Phase E6 — mode selection**. Port `sampling.rs`: take stratified sample, trial-encode
  with each candidate mode, pick lowest bit count. Add FloatMult, IntMult (likely), FloatQuant.
  Skip Dict + Lookback + Conv1 unless requested.
- [ ] **Phase E7 — multi-chunk, multi-page, nullable**. Match decode: split into chunks of
  `DEFAULT_MAX_PAGE_N`, pages per `ChunkConfig.paging_spec`. For nullable input, strip
  nulls before encode, emit validity as child[0].
- [ ] **Phase E8 — integration tests**. `JavaWritesRustReadsIntegrationTest`: produce a
  `.vortex` with pco-encoded column, validate the Rust reference reader decodes it
  byte-identical to input. Property test with `tries` low.
- [ ] **Phase E9 — `EncodeResult` glue**. `PcoEncoding.Encoder.encode(dtype, data)` returns
  `EncodeNode(VORTEX_PCO, metadata, no children OR validity child, bufferIndices)` with
  `chunk_metas` then `pages` as separate buffer indices.

**Risks**:
- Bin optimization DP: bug → catastrophic ratio loss but still valid output. Symptom is
  silent — only benchmarks catch it. Test ratio against Rust on known inputs.
- tANS weight quantization: bug → Rust decoder rejects with checksum mismatch. Caught fast
  by Java→Rust integration test.
- Mode selection: wrong mode = valid output but poor ratio. Same silent failure as bin DP.
- `log2_approx` is a fast-math hack. Java port can use `Math.log` (slower but exact);
  measure JMH cost before chasing parity.
- Encode unit test oracle problem: easier to assert bit-exact against a recorded Rust output
  for fixed inputs than to assert "optimal encoding" — record golden encodings per ptype.

**Estimate**: ~20 working days full encode. ~8 days for Classic+Consecutive+I64-only "valid
but suboptimal" encoder (Phase E1+E2+E5+E8 partial). Decode is the prerequisite —
don't start before decode lands.

**Decision**: keep `Encoder` stub until a real write consumer materializes. Reassess
post-decode + post-`vortex-arrow` bridge.

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
| `alprd.vortex`                   | ✅     |                               |
| `decimal.vortex`                 | ✅     |                               |
| `decimal_byte_parts.vortex`      | ✅     |                               |
| `datetimeparts.vortex`           | ✅     |                               |
| `list.vortex`                    | ✅     |                               |
| `listview.vortex`                | ✅     |                               |
| `fixed_size_list.vortex`         | ✅     |                               |
| `zstd.vortex`                    | ✅     |                               |
| `tpch_lineitem.compact.vortex`   | ❌     | `vortex.pco`                  |
| `tpch_lineitem.regular.vortex`   | ✅     |                               |
| `tpch_orders.compact.vortex`     | ❌     | `vortex.pco`                  |
| `tpch_orders.regular.vortex`     | ✅     |                               |
| `pco.vortex`                     | ❌     | `vortex.pco`                  |
| `clickbench_hits_5k.compact.vortex` | ❌  | `vortex.pco`                  |
| `clickbench_hits_5k.regular.vortex` | ✅  |                               |

**Score: 31/35** (including `for.vortex` scanned separately from `scan_fixture_decodesAllRows`)


