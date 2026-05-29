# TODO

## Encodings

- [ ] **#7 Additional encodings**
    - `pcodec` — PCodec numerical compression. Currently a stub (`PcoEncoding`) that throws
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

- [ ] **#13 BtrBlocks-style cascading compressor** (`core`, `writer`)
    - Reference: https://vortex.dev/blog/btrblocks-compressor
    - Rust source: `vortex-sampling-compressor` crate in spiraldb/vortex
    - **Scope (first cut)**: integers + floats only. Strings stay on current first-match.
    - **Rollout**: opt-in via `WriteOptions.cascading(int allowedCascading)`. Default `allowedCascading=0`
      preserves today's `findEncoding()` first-match behaviour so existing tests are unaffected.
    - Exclusion rule: don't apply dict encode to dict encode, FoR to FoR, etc. — encoding ids exclude
      themselves on recursion via `CompressorContext.excluded`.

    **Sub-tasks**

    - [ ] **a. `CompressorContext` record** (`core/encoding`)
        - Fields: `int allowedCascading`, `Set<EncodingId> excluded`, `long sampleSeed`,
          `int minSampleSize` (default 1024), `double sampleFraction` (default 0.01).
        - Immutable; `withDecrementedDepth()` and `withExcluded(EncodingId)` helpers.

    - [ ] **b. Cascade-aware encode API** (`core/encoding/Encoding.java`)
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
        - Encodings that don't expose intermediates use the default (terminal); they get tried as
          leaf candidates only.

    - [ ] **c. Refactor cascading-friendly encodings to emit children**
        - `AlpEncoding`: F64 → `(metadata{expE,expF,patches}, child long[] encodedInts)` +
          optional patch children. Today's hard-coded `EncodeNode.leaf(VORTEX_PRIMITIVE, 0)` for
          the I64 child becomes an open `ChildSlot(I64, encodedArr)`.
        - `DictEncoding`: codes child (currently emitted as `VORTEX_PRIMITIVE` leaf) becomes an
          open `ChildSlot(unsigned int, codes[])` so cascade can bit-pack it. Values child stays
          primitive (or recurses too if `allowedCascading>0`).
        - `FrameOfReferenceEncoding`: **implement encode** (currently decode-only, throws on encode).
          Produces `(reference, child long[] deltas)` → `ChildSlot(I64, deltas)`. Pure intermediate;
          requires `allowedCascading>0` because raw FoR without downstream pack is no win.
        - `DeltaEncoding`: refactor to emit `(bases child, deltas child)` matching Rust's 2-child
          wire format (currently 1-buffer flat — incompatible with Rust reader, see commit 09685a2).
          Blocks cross-compat for delta cascades; track as sub-task c.4, can land separately.
        - `BitpackedEncoding`: stays terminal (no open children). Patch indices/values already handled.
        - `PrimitiveEncoding`: terminal.

    - [ ] **d. `CascadingCompressor`** (`core/encoding`)
        - Constructor: `(List<Encoding> encodings, CompressorContext rootCtx)`.
        - `EncodeResult encode(DType dtype, Object data)` algorithm:
            1. Build sample: stratified contiguous slices, total ≈ `max(minSampleSize,
               sampleFraction * n)`. Fixed-seed `Random` for reproducibility.
            2. Build candidate list: every encoding where `accepts(dtype)` and id ∉ `ctx.excluded`.
            3. For each candidate: invoke `encodeCascade(dtype, sample, ctx)`. If the step has
               open children, recurse on each with `ctx.withDecrementedDepth().withExcluded(thisEncoding.id())`.
               Sum total byte size across all owned buffers.
            4. Pick winner by smallest total size; require ratio ≥ 1.0 vs raw primitive baseline,
               else fall through to `PrimitiveEncoding`.
            5. Re-run winner on **full** data (sample-time stats are estimates only).
        - Splicing: when a child recursion returns its own `EncodeResult`, append its buffers to
          the parent's flat list and remap the child's `bufferIndices` by the offset.
        - Terminal stop: when `ctx.allowedCascading == 0`, only encodings whose default-API
          `encodeCascade` returns no open children are considered.

    - [ ] **e. `WriteOptions.cascading(int)`** (`writer/WriteOptions.java`)
        - Add field `int allowedCascading` (default `0`).
        - `WriteOptions.cascading(int n)` factory mirrors `defaults()`.
        - `VortexWriter.writeSegment` (writer/VortexWriter.java:212) branches:
          if `options.allowedCascading() > 0`, use `CascadingCompressor`; else current `findEncoding()`.

    - [ ] **f. Tests** (`core` + `writer` + `integration`)
        - Unit: `CascadingCompressorTest` — sample stratification, exclusion list propagation,
          ratio-based selection, depth=0 terminates, depth>0 picks cascade.
        - Round-trip: each cascade combo (ALP+Bitpacked F64, FoR+Bitpacked I64, Dict+Bitpacked I32,
          Delta+Bitpacked I64) round-trips bit-exact through `VortexReader`.
        - Cross-compat: `JavaWritesRustReadsIntegrationTest` runs with `allowedCascading=3`,
          confirms Rust JNI reader decodes the same OHLC data (gates DeltaEncoding wire-format fix).
        - `WriteFileSizeIntegrationTest`: assert Java file ≤ JNI file size with cascade on.

    - [ ] **g. Benchmark**
        - Extend `RustVsJavaWriteBenchmark` (TODO #10b) with cascading-off vs cascading-on variants.
        - Capture: ratio of compressed bytes (Java cascade / JNI), write throughput, decode
          throughput on the resulting file.

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

## Skills

- [ ] Keep `.claude/skills/improve-performance.md` and `.claude/skills/review-performance.md` in sync with
  `CLAUDE.md` and README perf notes. Re-audit whenever memory model, allocation rule, or benchmark layout changes.

## Project

- [ ] Move project to a dedicated organization
- [ ] Create website
- [ ] Publish benchmarks
- [ ] Build something like hardwood.dev but for vortex files
