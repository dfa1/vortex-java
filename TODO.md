# TODO

## Done

- [x] **#1 Generate FlatBuffer + Protobuf sources**
- [x] **#2 Implement `PostscriptParser.parse()`**
- [x] **#3 Implement `ScanIterator.hasNext()` / `next()`** (layout traversal + flat decode; zone-map pruning pending)
- [x] **#4 Implement `VortexWriter.writeChunk()` + `close()`** (primitive + bool encodings, full file format)
- [x] **#5 Round-trip unit tests** (`VortexWriterTest`: 5 tests; `VortexFileTest`: 17 tests)
- [x] **#6 Zone-map pruning in `ScanIterator`**
    - Writer embeds min/max per flat segment in `ArrayNode.stats` (Protobuf ScalarValue)
    - Reader peeks stats from segment FlatBuffer tail, skips chunks excluded by `RowFilter`
    - Supports `Gte`, `Lte`, `Eq`, `And` predicates on I8/I16/I32/I64, U8/U16/U32/U64, F32/F64
- [x] **#7a `fastlanes.bitpacked` — spec-compliant rewrite** (all 5 steps incl. patches)
    - Protobuf `BitPackedMetadata` (tags 1+2+3); unified FastLanes `unpack` for I8/I16/I32/I64 (signed + unsigned);
      patches decoded from children slots 0 (indices) + 1 (values), overwritten by absolute index.
    - Encoder writes spec-compliant protobuf metadata; round-trip + patch decode covered by tests.
- [x] **#7b `fastlanes.for` decoder** (reference + bitpacked residuals)
- [x] **#7c `vortex.sparse` decoder** (fill value + patches)
- [x] **#7d `vortex.alp` decoder** (ALP inverse + patches)
- [x] **#8 Rust writes → Java reads** (`RustWritesJavaReadsIT`, `-Pintegration`)
    - JNI writes I64+F64 file; Java reader decodes via `DecoderRegistry.loadAll()`
    - Added `SequenceCodec` (`vortex.sequence` = `A[i] = base + i * multiplier`)
- [x] **#9 Java writes → Rust reads** (`JavaWritesRustReadsIT`, `-Pintegration`)
    - Java writer produces file; JNI reader decodes via Arrow C Data Interface
    - `Buffer.alignment_exponent = 6` + `SegmentSpec.alignment_exponent = 6` + pre-segment 64-byte padding

## Open

- [ ] **#7 Additional encodings**
    - `pcodec` — float compression (only remaining gap; bitpacked, delta, for, sparse, alp, dict, fsst, sequence,
      varbin, constant, runend all landed)

## Performance

- [x] **#10a Java vs JNI read benchmark** — `RustVsJavaReadBenchmark` covers volume (I64), close (F64 ALP), symbol
  (varbin) on 10M OHLC rows. Drives README perf table.

- [ ] **#10b Java vs JNI write benchmark** (`performance/` module, `-Pperformance`)
    - Add `RustVsJavaWriteBenchmark` mirroring read side: same 10M-row OHLC fixture, JMH throughput, both writers.
    - Old `WriteBenchmark.java` (Java-only) removed; rewrite from scratch using JNI bindings already on classpath
      (`dev.vortex:vortex-jni:0.72.0`).

- [ ] **#10c Publish reproducible perf artifacts**
    - Capture JMH JSON + JFR profile alongside README table; cite hardware (CPU model), JDK build (`java -version`),
      and benchmark commit SHA so numbers don't rot silently.

## Large-file support

- [ ] **#12 Test read/write of files > 2 GB**
    - Write a vortex file whose total segment data exceeds 2 GB (e.g., several wide int64 columns × enough rows)
    - Verify `VortexFile.open()` maps correctly and `ScanIterator` decodes without offset truncation
    - Parquet baseline for comparison: same data fails or requires splitting when any column chunk > 2 GB
    - Confirm no `int` casts silently truncate `uint64` offsets or `uint32` lengths in `SegmentSpec`

## Improve read speed

- avoid switching on PType per element during a copy => embrace MemorySegment
- the arena is a parameter: it can be allocated once and pass down, all allocations should be done there
- reuse buffers during decoding
- don't allocate temp byte[]

## Improve write speed

- this is important but focus will be firs the reading part
- use the algorithm described here: https://vortex.dev/blog/btrblocks-compressor
    - start with compressorContext allowedCascading=3
    - don't apply dict encode to dict encode

## Checkstyle

- [ ] Re-enable `TodoComment` in `checkstyle.xml` once all inline TODOs are resolved:
    - `VortexReader.java:73` — explain why reader should be closed
    - `PType.java:38`, `PTypeIO.java:49`, `DictCodec.java:228` — implement F16

## Array API

- [ ] Add typed accessors to `Array`: `getLong(i)`, `getDouble(i)`, `getInt(i)`, `getFloat(i)`, etc.
  - All leaf codecs (primitive, bitpacked, alp, sparse, for, delta, dict) decode into `buffers[0]` = flat LE primitives — typed accessors work uniformly
  - `vortex.struct` is never a leaf; always split into per-column arrays before element access
  - Make `buffers`/`children` package-private once accessors cover all callers

- [ ] Introduce specialized typed array views: `ArrayLong`, `ArrayDouble`, etc.
  - `ScanResult.columns().get("volume", PType.I64)` returns `ArrayLong` instead of raw `Array`
  - `ArrayLong.forEach(LongConsumer)` — zero-allocation bulk iteration; internally hoists
    `count = buffer.byteSize() / 8` so JIT eliminates per-element bounds check (see `javaReadVolume` benchmark)
  - `ArrayLong.get(long i)` — random access for non-bulk callers
  - `ArrayDouble.forEach(DoubleConsumer)` — same pattern for F64 columns

- [ ] Optional `vortex-arrow` bridge module for Arrow ecosystem interop
  - Primary API stays `ArrayLong`/`ArrayDouble` (zero-copy, no deps, no Unsafe)
  - Bridge wraps typed views into Arrow `BigIntVector`, `Float8Vector`, etc. for users who need
    Arrow Flight / DuckDB ADBC / pandas interop
  - Conversion involves a copy (MemorySegment → Arrow off-heap buffer) — cost is explicit and opt-in
  - Arrow JVM uses `sun.misc.Unsafe` / Netty internally; keeping it in a separate module means
    the core library stays Unsafe-free
  - Arrow JVM is actively moving to FFM (`MemorySegment`-based allocator); once that lands
    the bridge can become zero-copy (share the mmap region directly, no copy needed)

## Code cleanups

- use a dedicated exception instead of IOException?
- runtime exception like VortexException, indicating an non-recoverable error
-
    - introduce CodecType enum: this can be used to replace the string throw new IllegalStateException("$codec:
      message")
- VertexException should have CodecType
- avoid allocating too many intermediate ByteBuffer => always use a MemorySegment from arena
  pass the arena as part of the EncodeContext, to have more deterministic release of memory
- use domain primitive like RowCount or Limit/Unlimited (they cannot be zero)
- avoid use of IOException like:
  if (footerSeg == null) {
  throw new IOException("vortex: postscript missing footer segment");
  }
  this is an unrecoverable exception

## Skills

- [ ] Keep `.claude/skills/improve-performance.md` and `.claude/skills/review-performance.md` in sync with
  `CLAUDE.md` and README perf notes. Re-audit whenever the memory model, allocation rule, or benchmark
  layout changes — skills drift silently and start producing wrong guidance.

## Project

- move the project in a dedicated organization
- create website
- publish benchmarks
- idea is to build something like hardwood.dev but for parquet files

