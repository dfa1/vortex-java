# TODO

## Encodings

- [ ] **#7 Additional encodings**
    - `pcodec` — float compression (only remaining gap; bitpacked, delta, for, sparse, alp, dict, fsst, sequence,
      varbin, constant, runend all landed)

## Performance

- [ ] **#10b Java vs JNI write benchmark** (`performance/` module, `-Pperformance`)
    - Add `RustVsJavaWriteBenchmark` mirroring read side: same 10M-row OHLC fixture, JMH throughput, both writers.
    - Old `WriteBenchmark.java` (Java-only) removed; rewrite from scratch using JNI bindings already on classpath
      (`dev.vortex:vortex-jni:0.72.0`).

- [ ] **#10c Publish reproducible perf artifacts**
    - Capture JMH JSON + JFR profile alongside README table; cite hardware (CPU model), JDK build (`java -version`),
      and benchmark commit SHA so numbers don't rot silently.

- [ ] Improve read speed
    - avoid switching on PType per element during a copy — use MemorySegment bulk copy
    - arena is a parameter: allocate once, pass down; all allocations go there
    - reuse buffers during decoding
    - don't allocate temp byte[]

- [ ] Improve write speed
    - use the algorithm described here: https://vortex.dev/blog/btrblocks-compressor
    - start with compressorContext allowedCascading=3
    - don't apply dict encode to dict encode

## Large-file support

- [ ] **#12 Test read/write of files > 2 GB**
    - [x] `SegmentSpec.length` widened from `int` to `long` (wire field is `uint32`, so values 2–4 GB were
      silently truncated to negative). `PostscriptParser`, `VortexWriter.SegRef`, and
      `ScanIterator.readFlatStats` follow the type through end-to-end. Covered by
      `PostscriptParserBigSegmentTest` (FlatBuffer footer with length = 3 GB round-trips correctly).
    - [x] `ScanIterator.readFlatStats` no longer materialises the whole segment as a `ByteBuffer`
      (2 GB cap); it slices the FlatBuffer tail off the `MemorySegment` first.
    - [ ] End-to-end multi-GB round-trip: write a file whose total segment data exceeds 2 GB
      (several wide I64 columns × enough rows), open + scan + verify. Run under a slow profile
      so it stays out of the default test suite.
    - [ ] Parquet baseline for comparison: same data should fail or require splitting when any
      column chunk exceeds 2 GB.

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
      `count = buffer.byteSize() / 8` so JIT eliminates per-element bounds check
    - `ArrayLong.get(long i)` — random access for non-bulk callers
    - `ArrayDouble.forEach(DoubleConsumer)` — same pattern for F64 columns

- [ ] Optional `vortex-arrow` bridge module for Arrow ecosystem interop
    - Primary API stays `ArrayLong`/`ArrayDouble` (zero-copy, no deps, no Unsafe)
    - Bridge wraps typed views into Arrow `BigIntVector`, `Float8Vector`, etc. for users who need
      Arrow Flight / DuckDB ADBC / pandas interop
    - Conversion involves a copy (MemorySegment → Arrow off-heap buffer) — cost is explicit and opt-in
    - Arrow JVM uses `sun.misc.Unsafe` / Netty internally; keeping it in a separate module means
      the core library stays Unsafe-free

## Code quality

- [ ] Use a dedicated `VortexException` (unchecked) instead of `IOException` for unrecoverable errors
- [ ] Introduce `CodecType` enum to replace stringly-typed error messages
- [ ] Avoid allocating intermediate `ByteBuffer` — always use `MemorySegment` from arena
- [ ] Domain primitives: `RowCount`, `Limit`/`Unlimited` (cannot be zero)

## Skills

- [ ] Keep `.claude/skills/improve-performance.md` and `.claude/skills/review-performance.md` in sync with
  `CLAUDE.md` and README perf notes. Re-audit whenever memory model, allocation rule, or benchmark layout changes.

## Project

- [ ] Move project to a dedicated organization
- [ ] Create website
- [ ] Publish benchmarks
- [ ] Build something like hardwood.dev but for vortex files
