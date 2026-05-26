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

- [ ] **#7a Fix `fastlanes.bitpacked` JNI decode**
  - JNI Vortex 0.72.0 uses a different block-transpose layout than the Java writer
  - Step 1: write an XOR-differential probe (extend to vi=2,16,64,160) to map
    bit `b` of value at index `vi` → `(word_index, bit_position)` in the raw buffer
  - Step 2: derive the formula and rewrite `decodeJni()` in `BitpackedCodec`
  - Step 3: unit test with `BitpackedCodecTest` — round-trip JNI-encoded fixture

- [ ] **#7b Implement `fastlanes.for` decoder**
  - Frame-of-reference wrapper: metadata holds a scalar offset, single child is bitpacked
  - `decodeChild(0)` gives the residuals; add offset to each element
  - Used by JNI Vortex for small-range integer columns

- [ ] **#7c Implement `vortex.sparse` decoder**
  - Mostly-constant column: constant fill value + small list of (index, value) patches
  - Patches encoded in protobuf metadata (field 3), constant in field 1/2
  - Step 1: parse protobuf patches from metadata
  - Step 2: allocate output filled with constant, apply patches at their indices

- [ ] **#7d Implement `vortex.alp` decoder**
  - ALP float compression for F64 columns (used by JNI Vortex for price/float data)
  - Two children: encoded exponents + exceptions; apply ALP inverse transform
  - Unblock F64 columns in OHLC benchmark

## Cross-compatibility (blocked by: JNI bindings)

- [ ] **#8 Rust writes → Java reads**
  - Prerequisite: #7a (bitpacked JNI decode), #7b (for), #7c (sparse), #7d (alp)
  - Step 1: integer columns only — I64 round-trip through JNI writer → Java reader
  - Step 2: float columns — F64 (requires #7d ALP decoder)
  - Step 3: full OHLC file (all column types) — assert sum/close values match JNI reader
  - Tracked in `OhlcReadBenchmark`: `javaReadClose` and `javaReadSum` must equal `jniReadClose`

- [ ] **#9 Java writes → Rust reads**
  - Use `VortexWriter` to produce a `.vtx` file
  - Decode with JNI reader, assert decoded values match input

  **Module:** `integration/` — activate with `-Pintegration`. Tests `@Disabled` until JNI artifact coordinates are known.

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

## Project
- move the project in a dedicated organization
- create website
- publish benchmarks
- idea is to build something like hardwood.dev but for parquet files