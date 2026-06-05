# TODO

## Project

- [ ] Move project to a dedicated organization
- [ ] Create website
- [ ] Publish benchmarks — run `./bench` locally, push JMH JSON to gh-pages via `bench-publish` script, view at `https://jmh.morethan.io/?source=https://dfa1.github.io/vortex-java/benchmark-result.json`; dated files for history comparison via `?source=url1,url2`; then drop `.github/workflows/benchmark.yml`
- [ ] Build something like hardwood.dev but for vortex files
- [ ] Publish to Maven Central (OSSRH/SONATYPE setup, GPG signing, coordinates, CI release pipeline)

## Performance

- [ ] Publish reproducible perf artifacts
    - Capture JMH JSON + JFR profile alongside README table; cite hardware (CPU model), JDK build (`java -version`),
      and benchmark commit SHA so numbers don't rot silently.
- [ ] performance tests must be peer reviewed
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

## Build

- [ ] **Merge `core`/`reader`/`writer` into a single library jar** — the three modules are tightly
  coupled (every `Encoding` class has both encode + decode; format constants are shared). A single
  `vortex-java` artifact simplifies client dependency management and removes artificial module
  boundaries. Keep `integration`, `performance`, and `cli` as separate modules. Package structure
  (`encoding`, `io`, `writer`) already enforces internal boundaries without Maven.
- [ ] prefix all modules with "vortex-"
- [ ] add BOM module
- [ ] deploy to maven central
- [ ] switch back to module-path, but keep in mind these 2 blockers

## Documentation

- [ ] Format specification: byte-exact diagrams for file layout and each encoding, with annotated examples (Arrow spec style)

## Tooling


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

- [ ] **Audit runtime pluggability vs Rust impl** — maintainer (2026-06-04) flagged that Rust supports
  runtime registration for: Encodings, DTypes, Compute, Layouts. Java status:
    - Encodings: ✅ `ServiceLoader` + `EncodingRegistry.register()`; ✅ `allowUnknown()` passthrough for unregistered encodings (mirrors `VortexSession::allow_unknown()`)
    - DTypes: ❌ sealed hierarchy — no user-extensible type. If a downstream consumer needs a custom
      logical type (e.g. UUID, IP address) they can't register one. Decide: keep sealed (simpler) or
      open via SPI mirroring `EncodingRegistry`.
    - Layouts: ❌ fixed set (Flat/Chunked/Zoned/Struct). Same trade-off as DTypes.
    - Compute: ❌ no compute layer yet. Out of scope until reader feature-complete.
  Action: short design note weighing sealed-vs-pluggable for DType + Layout; revisit when Java impl
  has a real downstream consumer asking for it. Don't pre-open these without a use case.


## Encodings

See [docs/compatibility.md](docs/compatibility.md) for the full encoding support table and S3 fixture status.

### `vortex.zstd` known limitations


- [ ] **Multi-frame encode** — `ZstdEncoding.Encoder` always produces a single frame for the whole array.
  Fix: accept a `valuesPerFrame` parameter (default: all values in one frame). Split the raw byte buffer at frame boundaries (`valuesPerFrame * byteWidth`), compress each slice independently, emit one `ZstdFrameMetadata` per frame. Enables partial decompression during slice scans.

- [ ] **Nullable arrays (encode)** — `ZstdEncoding.Encoder` has no null handling.
  Fix: accept nullable input (e.g. `Integer[]` or a validity mask alongside the data array). Strip null positions before compression. Encode the validity bitmap as a Bool child (child[0]) in the `EncodeNode`. Mirrors what Rust does: only valid values go into the compressed payload.


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


