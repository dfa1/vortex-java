# TODO

## Done

- [x] **#1 Generate FlatBuffer + Protobuf sources**
- [x] **#2 Implement `PostscriptParser.parse()`**
- [x] **#3 Implement `ScanIterator.hasNext()` / `next()`** (layout traversal + flat decode; zone-map pruning pending)
- [x] **#4 Implement `VortexWriter.writeChunk()` + `close()`** (primitive + bool encodings, full file format)
- [x] **#5 Round-trip unit tests** (`VortexWriterTest`: 5 tests; `VortexFileTest`: 17 tests)

## Open

- [x] **#6 Zone-map pruning in `ScanIterator`**
  - Writer embeds min/max per flat segment in `ArrayNode.stats` (Protobuf ScalarValue)
  - Reader peeks stats from segment FlatBuffer tail, skips chunks excluded by `RowFilter`
  - Supports `Gte`, `Lte`, `Eq`, `And` predicates on I8/I16/I32/I64, U8/U16/U32/U64, F32/F64

- [ ] **#7 Additional encodings**
  - `fastlanes.bitpacked` — integer bit-packing (Java write/read done; JNI read broken — see #7a)
  - `fastlanes.delta` — delta encoding for monotonic sequences
  - `dict` — dictionary encoding for low-cardinality columns
  - `pcodec` — float compression

- [x] **#7a Fix `fastlanes.bitpacked` — spec-compliant rewrite**
  - Root cause: current code guesses format by metadata byte size (9 = Java, 2 = JNI). Wrong.
    The spec always uses protobuf metadata regardless of writer origin.
  - **Spec** (from `encodings/fastlanes/src/bitpacking/vtable/mod.rs`):
    - Metadata: protobuf `BitPackedMetadata` — `bit_width u32` (tag 1), `offset u32` (tag 2, 0≤offset<1024), `patches PatchesMetadata` (tag 3, optional)
    - Buffer size: `ceil((len + offset) / 1024) * 128 * bit_width` bytes
    - FastLanes block layout: `LANES = 1024 / T` (T = element bit-width); `FL_ORDER = [0,4,2,6,1,5,3,7]`; logical index for `(row, lane)` = `FL_ORDER[row/8]*16 + (row%8)*128 + lane` — same formula for all types
  - Step 1: delete `decodeJni()` and `decodeJava()`; parse metadata as protobuf (tags 1+2)
  - Step 2: implement single unified `unpack(buf, bitWidth, offset, T, rowCount) → long[]` using the FastLanes algorithm above
  - Step 3: handle patches — decode child slots (indices + values), overwrite output at patch indices
  - Step 4: align `encode()` to write protobuf metadata (tags 1+2) instead of the 9-byte custom format
  - Step 5: update `BitpackedCodecTest` for round-trip with spec-compliant metadata
  - Reference: `spiraldb/vortex` `encodings/fastlanes/src/bitpacking/`, `spiraldb/fastlanes-rs` `src/bitpacking.rs` + `src/macros.rs`

- [x] **#7b Implement `fastlanes.for` decoder**
  - **Spec** (from `encodings/fastlanes/src/for/vtable/mod.rs`):
    - Metadata: raw `ScalarValue` protobuf bytes (the reference/minimum value; no wrapper message)
    - Child slot 0: encoded array (typically `fastlanes.bitpacked` residuals)
    - Decode: `output[i] = encoded[i].wrapping_add(reference)`
  - Step 1: parse `ScalarValue` bytes from metadata using existing proto classes
  - Step 2: decode child array recursively via `DecodeContext`
  - Step 3: add reference to each element (wrapping add for unsigned types)

- [x] **#7c Implement `vortex.sparse` decoder**
  - **Spec** (from `encodings/sparse/src/lib.rs`):
    - Metadata: protobuf `SparseMetadata` — `patches PatchesMetadata` (tag 1, required)
    - Buffer 0: fill value serialized as `ScalarValue` protobuf bytes
    - Child slot 0: patch indices; slot 1: patch values
    - Decode: allocate output filled with fill_value, then apply patches at their indices
  - Step 1: parse `SparseMetadata` from metadata bytes
  - Step 2: read fill value from `ctx.buffer(0)` as `ScalarValue` proto bytes
  - Step 3: decode patch indices + values from child slots
  - Step 4: allocate output, fill with constant, overwrite at patch positions

- [x] **#7d Implement `vortex.alp` decoder**
  - **Spec** (from `encodings/alp/src/alp/array.rs`):
    - Metadata: protobuf `ALPMetadata` — `exp_e u32` (tag 1), `exp_f u32` (tag 2), `patches PatchesMetadata` (tag 3, optional)
    - Child slot 0: encoded integers (I32 for F32 columns, I64 for F64 columns)
    - Child slots 1–3 (optional): patch indices, patch values, patch chunk offsets
    - Decode: apply ALP inverse transform to encoded integers → floats; then apply patches
  - Step 1: parse `ALPMetadata` from metadata bytes
  - Step 2: decode encoded child (I32/I64)
  - Step 3: apply ALP inverse: `value = encoded / 10^e * 10^f` (integer → float reconstruction)
  - Step 4: apply patches for exceptions that don't fit the ALP transform
  - Reference: `encodings/alp/src/alp/decompress.rs`

## Cross-compatibility

- [x] **#8 Rust writes → Java reads** (`RustWritesJavaReadsIT`, `-Pintegration`)
  - JNI writes I64+F64 file; Java reader decodes via `DecoderRegistry.loadAll()`
  - Fixed: added `SequenceCodec` (`vortex.sequence` = `A[i] = base + i * multiplier`)
  - Covers: single chunk, multiple chunks (JNI may merge), column projection

- [x] **#9 Java writes → Rust reads** (`JavaWritesRustReadsIT`, `-Pintegration`)
  - Java writer produces file; JNI reader decodes via Arrow C Data Interface
  - Fixed writer: `Buffer.alignment_exponent = 6` + `SegmentSpec.alignment_exponent = 6` + pre-segment 64-byte padding
  - Root cause: Rust decoder tracks logical alignment from FlatBuffer field; Arrow rejects buffers with alignment < 64 bytes
  - Covers: single chunk, multiple chunks

## Performance (blocked by: JNI bindings for comparison baseline)

- [ ] **#10 Write-speed benchmark** (`performance/` module, `-Pperformance`)
  - JMH: `VortexWriter` throughput (rows/s, MB/s) vs JNI writer
  - Fixture: 1M rows, int64 + float64 columns; single-chunk and multi-chunk variants
  - Java-only half already runnable; JNI half stubbed until bindings available

- [ ] **#11 Read-speed benchmark** (`performance/` module, `-Pperformance`)
  - JMH: `VortexFile` + `ScanIterator` throughput vs JNI reader
  - Same fixture as #10; full-scan and projected-column variants

## Code cleanups 

- use a dedicated exception instead of IOException? 
-   runtime exception like VortexException, indicating an non-recoverable error 
- avoid allocating too many intermediate ByteBuffer => always use a MemorySegment from arena
    pass the arena as part of the EncodeContext, to have more deterministic release of memory
- use domain primitive like RowCount or Limit/Unlimited (they cannot be zero)
- rename VortexFile to VortexReader (same as module name)
- avoid use of IOException like:
  if (footerSeg == null) {
    throw new IOException("vortex: postscript missing footer segment");
  }
  this is an unrecoverable exception
- drop BufferDesc if not used

## Performance
- in BitpackedCodec, there are a lot of extra allocation like: 
   try {
   byte[] bytes = new byte[rawMeta.remaining()];
   rawMeta.duplicate().get(bytes);
   meta = EncodingProtos.BitPackedMetadata.parseFrom(bytes);
   } catch (InvalidProtocolBufferException e) {
   throw new IllegalStateException("fastlanes.bitpacked: invalid metadata", e);
   }
=> just use ByteBuffer in this case
- read path should always avoid byte[] allocations or similar
- use MethodHandle to read a long array from a ByteBuffer

## Project
- move the project in a dedicated organization
- create website
- publish benchmarks
- idea is to build something like hardwood.dev but for parquet files