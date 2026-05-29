# TODO

## Encodings

- [ ] **#7 Additional encodings**
    - `pcodec` — PCodec numerical compression. Currently a stub (`PcoCodec`) that throws
      `VortexException(VORTEX_PCO, "not implemented")` so affected files fail with clear attribution.
      Full decoder requires a port of ANS, delta predictions, and bin tokenization from the
      upstream Rust `pco` crate (https://github.com/mwlon/pcodec) — no mainstream Java port exists.
      Multi-day effort; not on the critical path while ALP covers typical float workloads.

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

- [ ] **#13 BtrBlocks-style cascading compressor** (`core`, `writer`)
    - Reference: https://vortex.dev/blog/btrblocks-compressor
    - Rust source: `vortex-sampling-compressor` crate in spiraldb/vortex
    - **Scope (first cut)**: integers + floats only. Strings stay on current first-match.
    - **Rollout**: opt-in via `WriteOptions.cascading(int allowedCascading)`. Default `allowedCascading=0`
      preserves today's `findCodec()` first-match behaviour so existing tests are unaffected.
    - Exclusion rule: don't apply dict encode to dict encode, FoR to FoR, etc. — codec ids exclude
      themselves on recursion via `CompressorContext.excluded`.

    **Sub-tasks**

    - [ ] **a. `CompressorContext` record** (`core/encoding`)
        - Fields: `int allowedCascading`, `Set<CodecId> excluded`, `long sampleSeed`,
          `int minSampleSize` (default 1024), `double sampleFraction` (default 0.01).
        - Immutable; `withDecrementedDepth()` and `withExcluded(CodecId)` helpers.

    - [ ] **b. Cascade-aware encode API** (`core/encoding/Codec.java`)
        - Add default method:
          ```java
          default CascadeStep encodeCascade(DType dtype, Object data, CompressorContext ctx) {
              EncodeResult r = encode(dtype, data);
              return CascadeStep.terminal(r);
          }
          ```
        - New record `CascadeStep(EncodeNode partialRoot, List<ByteBuffer> ownedBuffers,
          List<ChildSlot> openChildren, byte[] statsMin, byte[] statsMax)`.
        - `ChildSlot(DType childDtype, Object childData, int parentChildIdx)` — slot the
          compressor recursively encodes, then splices result into `partialRoot.children[parentChildIdx]`
          while remapping its `bufferIndices` to follow `ownedBuffers`.
        - Codecs that don't expose intermediates use the default (terminal); they get tried as
          leaf candidates only.

    - [ ] **c. Refactor cascading-friendly codecs to emit children**
        - `AlpCodec`: F64 → `(metadata{expE,expF,patches}, child long[] encodedInts)` +
          optional patch children. Today's hard-coded `EncodeNode.leaf(VORTEX_PRIMITIVE, 0)` for
          the I64 child becomes an open `ChildSlot(I64, encodedArr)`.
        - `DictCodec`: codes child (currently emitted as `VORTEX_PRIMITIVE` leaf) becomes an
          open `ChildSlot(unsigned int, codes[])` so cascade can bit-pack it. Values child stays
          primitive (or recurses too if `allowedCascading>0`).
        - `FrameOfReferenceCodec`: **implement encode** (currently decode-only, throws on encode).
          Produces `(reference, child long[] deltas)` → `ChildSlot(I64, deltas)`. Pure intermediate;
          requires `allowedCascading>0` because raw FoR without downstream pack is no win.
        - `DeltaCodec`: refactor to emit `(bases child, deltas child)` matching Rust's 2-child
          wire format (currently 1-buffer flat — incompatible with Rust reader, see commit 09685a2).
          Blocks cross-compat for delta cascades; track as sub-task c.4, can land separately.
        - `BitpackedCodec`: stays terminal (no open children). Patch indices/values already handled.
        - `PrimitiveCodec`: terminal.

    - [ ] **d. `CascadingCompressor`** (`core/encoding`)
        - Constructor: `(List<Codec> codecs, CompressorContext rootCtx)`.
        - `EncodeResult encode(DType dtype, Object data)` algorithm:
            1. Build sample: stratified contiguous slices, total ≈ `max(minSampleSize,
               sampleFraction * n)`. Fixed-seed `Random` for reproducibility.
            2. Build candidate list: every codec where `accepts(dtype)` and id ∉ `ctx.excluded`.
            3. For each candidate: invoke `encodeCascade(dtype, sample, ctx)`. If the step has
               open children, recurse on each with `ctx.withDecrementedDepth().withExcluded(thisCodec.id())`.
               Sum total byte size across all owned buffers.
            4. Pick winner by smallest total size; require ratio ≥ 1.0 vs raw primitive baseline,
               else fall through to `PrimitiveCodec`.
            5. Re-run winner on **full** data (sample-time stats are estimates only).
        - Splicing: when a child recursion returns its own `EncodeResult`, append its buffers to
          the parent's flat list and remap the child's `bufferIndices` by the offset.
        - Terminal stop: when `ctx.allowedCascading == 0`, only codecs whose default-API
          `encodeCascade` returns no open children are considered.

    - [ ] **e. `WriteOptions.cascading(int)`** (`writer/WriteOptions.java`)
        - Add field `int allowedCascading` (default `0`).
        - `WriteOptions.cascading(int n)` factory mirrors `defaults()`.
        - `VortexWriter.writeSegment` (writer/VortexWriter.java:212) branches:
          if `options.allowedCascading() > 0`, use `CascadingCompressor`; else current `findCodec()`.

    - [ ] **f. Tests** (`core` + `writer` + `integration`)
        - Unit: `CascadingCompressorTest` — sample stratification, exclusion list propagation,
          ratio-based selection, depth=0 terminates, depth>0 picks cascade.
        - Round-trip: each cascade combo (ALP+Bitpacked F64, FoR+Bitpacked I64, Dict+Bitpacked I32,
          Delta+Bitpacked I64) round-trips bit-exact through `VortexReader`.
        - Cross-compat: `JavaWritesRustReadsIntegrationTest` runs with `allowedCascading=3`,
          confirms Rust JNI reader decodes the same OHLC data (gates DeltaCodec wire-format fix).
        - `WriteFileSizeIntegrationTest`: assert Java file ≤ JNI file size with cascade on.

    - [ ] **g. Benchmark**
        - Extend `RustVsJavaWriteBenchmark` (TODO #10b) with cascading-off vs cascading-on variants.
        - Capture: ratio of compressed bytes (Java cascade / JNI), write throughput, decode
          throughput on the resulting file.

## Remote / HTTP reads

- [x] **Extract `VortexHandle` interface** (`reader` module, prerequisite for HTTP reader)
    - Interface: `dtype()`, `layout()`, `footer()`, `version()`, `fileSize()`, `registry()`,
      `slice(long offset, long length)`.
    - `VortexReader` implements it (mmap path, unchanged behaviour).
    - `VortexInspector.inspect()` and `ScanIterator` accept `VortexHandle` — both transports work
      transparently without overloads.

- [x] **`VortexHttpReader`** (`reader` module, requires `VortexHandle`)
    - Constructor takes `URI`; uses JDK `HttpClient` (no extra deps).
    - On open: fetch last 65 KB via `Range: bytes=-65536`, parse trailer + postscript.
    - `slice(offset, length)`: fires a targeted `Range: bytes=<offset>-<end>` request,
      allocates result off-heap via `Arena`, returns `MemorySegment`.
    - Segments fetched lazily — no full-file download.
    - Rust/Java mapping for reference:

      | Rust (`vortex-io`)       | Java                                      |
      |--------------------------|-------------------------------------------|
      | `VortexReadAt` trait     | `VortexHandle` interface                  |
      | `read_at(offset, len)`   | `slice(offset, length) → MemorySegment`   |
      | async `BoxFuture`        | sync `HttpClient` (blocking)              |
      | `object_store` crate     | raw `HttpClient` (JDK built-in, no deps)  |
      | `BufferHandle` heap alloc| `ctx.arena().allocate(n)` (off-heap)      |
      | `CoalesceConfig`         | not yet — future optimisation             |

- [x] **Integration test: read real Vortex files over HTTPS**
    - Source: public S3 bucket `vortex-compat-fixtures` (no auth required).
    - `VortexHttpReaderIT`: parses metadata, verifies layout row count, scans `for.vortex`
      (frame-of-reference, 10 columns, 1024 rows) end-to-end. Skipped when network unavailable.
    - `tpch_lineitem.compact.vortex` metadata reads fine; full scan blocked by `vortex.pco` (not implemented).

## Large-file support

- [ ] **#12 Test read/write of files > 2 GB**
    - [x] `SegmentSpec.length` widened from `int` to `long` (wire field is `uint32`, so values 2–4 GB were
      silently truncated to negative). `PostscriptParser`, `VortexWriter.SegRef`, and
      `ScanIterator.readFlatStats` follow the type through end-to-end. Covered by
      `PostscriptParserBigSegmentTest` (FlatBuffer footer with length = 3 GB round-trips correctly).
    - [x] `ScanIterator.readFlatStats` no longer materialises the whole segment as a `ByteBuffer`
      (2 GB cap); it slices the FlatBuffer tail off the `MemorySegment` first.
    - [x] End-to-end multi-GB scan benchmark: `RustWritesJavaReadsBigFileBenchmark.javaScan` —
      JNI writes ~3 GB of random I64 columns (random data defeats bit-packing so segments stay
      large), Java reader scans via `VortexReader`. Skip the JNI fixture build by passing
      `-Dvortex.bench.bigfile=<path>`.
    - [ ] Wire a real correctness assertion alongside the benchmark (e.g. compare summed columns
      against JNI reader) so any regression in the >2 GB path surfaces even without measuring
      throughput.
    - [ ] Parquet baseline for comparison: same data should fail or require splitting when any
      column chunk exceeds 2 GB.

## Array API

### Typed accessors (in progress)

Replace raw `arr.buffer(0).getAtIndex(layout, i)` with `arr.getLong(i)` /
`arr.getDouble(i)` / `arr.forEachLong(c)`. Codecs pick a concrete subtype so
the JIT can specialise the hot path with a constant `ValueLayout`.

**Design**

- `ArrayOperations` (interface, `core`): primitive accessors — `getBoolean`,
  `getByte`, `getShort`, `getInt`, `getLong`, `getFloat`, `getDouble`,
  `getBytes`, plus `forEachLong`/`forEachDouble`/... bulk variants. All
  default to `throw VortexException("<class> does not support <op>")`.
- `Array` (sealed interface, `core`) extends `ArrayOperations`. Keeps
  public `buffer(int)` / `child(int)` for codec internals during migration.
- Concrete subtypes in `core.array` sub-package:
    - `LongArray`, `DoubleArray`, `IntArray`, `FloatArray`,
      `ShortArray`, `ByteArray` — single `MemorySegment` + length +
      `static final ValueLayout`. Tight `getXxx` / `forEachXxx`.
    - `BoolArray` — bitmap.
    - `VarBinArray` — bytes segment + offsets segment + offsets ptype.
    - `NullArray` — length only.
    - `StructArray` — `Map<String, Array>` columns.
    - `GenericArray` — fallback that holds the current record shape
      (`MemorySegment[]` + `Array[]`) and dispatches via dtype switch;
      lets codecs migrate one at a time.

**Codec dispatch**

| Codec                                  | Returns                                 |
|----------------------------------------|-----------------------------------------|
| `vortex.primitive` (I64/U64)           | `LongArray`                             |
| `vortex.primitive` (I32/U32)           | `IntArray`                              |
| `vortex.primitive` (F64)               | `DoubleArray`                           |
| `fastlanes.bitpacked`, `for`, `delta`, `vortex.sparse`, `vortex.dict` (int values) | `LongArray` / `IntArray` per dtype |
| `vortex.alp` (F64)                     | `DoubleArray`                           |
| `vortex.alp` (F32)                     | `FloatArray`                            |
| `vortex.varbin`, `vortex.fsst`, `vortex.constant` (string) | `VarBinArray`         |
| `vortex.bool`                          | `BoolArray`                             |
| `vortex.null`                          | `NullArray`                             |
| layout `vortex.struct`                 | `StructArray`                           |

**Migration phases**

- [x] **Phase A**: interface + sealed `Array` + `GenericArray` shim. All
      existing codecs keep current behaviour. Migrate `PrimitiveCodec`
      and `AlpCodec` so `volume` (Primitive I64) returns `LongArray` and
      `close` (ALP F64) returns `DoubleArray`. Update
      `RustVsJavaReadBenchmark.javaReadVolume`/`javaReadClose` to use
      `forEachLong` / `forEachDouble`. Confirm no regression vs raw
      MemorySegment loop.
- [x] **Phase B**: migrate remaining leaf codecs (Bitpacked, FoR, Delta,
      Sparse, Dict, Bool, Constant, VarBin, FSST, RunEnd, Sequence)
      one at a time, each with a bench check.
- [ ] **Phase C**: once all leaf codecs return concrete subtypes,
      consider tightening `buffer(int)` / `child(int)` to
      package-private or moving to an internal helper interface.


- [ ] Optional `vortex-arrow` bridge module for Arrow ecosystem interop
    - Primary API stays `ArrayLong`/`ArrayDouble` (zero-copy, no deps, no Unsafe)
    - Bridge wraps typed views into Arrow `BigIntVector`, `Float8Vector`, etc. for users who need
      Arrow Flight / DuckDB ADBC / pandas interop
    - Conversion involves a copy (MemorySegment → Arrow off-heap buffer) — cost is explicit and opt-in
    - Arrow JVM uses `sun.misc.Unsafe` / Netty internally; keeping it in a separate module means
      the core library stays Unsafe-free

## Skills

- [ ] Keep `.claude/skills/improve-performance.md` and `.claude/skills/review-performance.md` in sync with
  `CLAUDE.md` and README perf notes. Re-audit whenever memory model, allocation rule, or benchmark layout changes.

## Naming alignment

- [ ] **Rename `Codec` → `Encoding` throughout Java codebase**
    - Vortex uses "encoding" everywhere: `VortexEncoding` trait, `vortex-encodings` crate, encoding IDs (`vortex.primitive`, etc.).
    - Java currently uses `Codec`, `CodecId`, `CodecRegistry`, `DecodeContext`, `EncodeResult` — drifts from domain vocabulary.
    - Rename: `Codec` → `Encoding`, `CodecId` → `EncodingId`, `CodecRegistry` → `EncodingRegistry`,
      `DecodeContext` → `DecodingContext` (or keep as-is), `EncodeResult` → `EncodingResult`.
    - Also rename `CODECS.md` → `ENCODINGS.md`.
    - Large mechanical refactor; no behaviour change. Good first-issue candidate.

## Project

- [ ] Move project to a dedicated organization
- [ ] Create website
- [ ] Publish benchmarks
- [ ] Build something like hardwood.dev but for vortex files
