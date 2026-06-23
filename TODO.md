# TODO

## Project

- [ ] Move project to a dedicated organization
- [ ] Create website
   - build something like hardwood.dev but for vortex files
- [ ] **Benchmark publishing** — drop CI workflow, add `bench-publish` script; see [ADR-0006](docs/adr/0006-benchmark-publishing.md).

## Performance

- [ ] Performance tests must be peer reviewed
- [ ] Run performance tests on other machines (I have access only to Apple M5)
- [ ] **Vector API adoption** — deferred; see [ADR-0005](docs/adr/0005-vector-api-adoption.md) for adoption criteria and candidate loops.

## Security

**Contract:** the reader memory-maps and parses untrusted binary input. Every malformed input must
throw `VortexException`, never `ArrayIndexOutOfBoundsException`, `NegativeArraySizeException`,
`OutOfMemoryError`, `StackOverflowError`, a raw FlatBuffer runtime exception, or a Protobuf
parser exception. Each entry below is either a known gap, a contract audit, or supporting infra.

### Parser hardening

- [ ] **Checkstyle rule rejecting raw `MemorySegment.asSlice` on untrusted offsets** —
  the `IoBounds.slice(seg, off, len)` helper shipped (ADR-0003 Phase E) and the untrusted
  file-structure + decode call sites already route through it. Remaining: add the Checkstyle
  rule blocking raw `asSlice` in the reader file-structure / decode packages, then a final
  `grep -rn 'asSlice' core/src/main reader/src/main` sweep to catch any site the migration missed.

### Per-encoding adversarial tests

Each encoding's `decode(DecodeContext)` should be exercised against:
- `bufferIndices[i] >= ctx.bufferCount()` → centralize check in `DecodeContext.buffer(i)`.
- Crafted metadata that decodes but disagrees with the buffer payload.

Per-encoding gotchas:
- [ ] **VarBin**: offsets non-monotonic, negative, past data-buffer length.
- [ ] **Dict**: `codes[i] >= values.length`; `codes` ptype declared u8 but values count > 256.
- [ ] **Bitpacked**: `bit_width < 0 || > 64`; `packed_len < n * bit_width / 8`.
- [ ] **ALP**: `dim < 0`, `f_or_d` byte out of enum range; `exceptions_count > n`.
- [ ] **Sparse**: indices non-sorted or `indices[i] >= length`; values count
  mismatches indices count.
- [ ] **Chunked**: zero children with non-zero `row_count`; child layout self-referencing
  (already protected by depth limit, but add explicit test).
- [ ] **Struct**: `fieldNames.size() != children.size()`; field name UTF-8 invalid.
- [ ] **RLE / RunEnd**: `run_ends` non-monotonic; last `run_end` ≠ `row_count`.
- [ ] **Constant**: protobuf scalar value missing or type-mismatched against declared `DType`.
- [ ] **Zoned**: zone-map min > max; zone count ≠ child chunk count.
- [ ] **Pco**: `bits_per_offset > 64`; `bin_count == 0` with non-empty page; per-page
  `n` greater than `DEFAULT_MAX_PAGE_N`; ANS state values inconsistent with weight table.

### Resource caps

- [ ] **Implement `ResourceLimits` + `ReadOptions`** — see [ADR-0004](docs/adr/0004-resource-caps-read-options.md) for design, defaults, and enforcement points. Also covers Pco page/bin caps.

### Fuzz infrastructure

- [ ] **Jazzer + JUnit 5** — add `com.code-intelligence:jazzer-junit` test dep. Two modes:
  regression (`./mvnw test`, replays saved corpus + crashes) and fuzz
  (`JAZZER_FUZZ=1`, nightly profile). See research notes in branch
  `worktree-security-fuzz` commit history.
- [ ] **Seed corpus from integration fixtures** — drop existing `.vortex` test files into
  `reader/src/test/resources/fuzz-corpus/full-file/`. Per-encoding sub-corpora extracted via
  a small tool that walks fixtures and dumps each segment to
  `core/src/test/resources/fuzz-corpus/<encoding>/`.
- [ ] **Fuzz targets**: `VortexReader.open(byte[])`, `PostscriptParser.parseBlobs`, and one
  `@FuzzTest` per encoding `Encoding.decode`. Crash oracle: `ignore = {VortexException.class}`.
- [ ] **Differential fuzz (Java vs Rust)** — round-trip random bytes through Java decode
  and `vortex-jni`; assert both throw or both return identical row count + values. Reuse
  `RustWritesJavaReadsIntegrationTest` harness.
- [ ] **OSS-Fuzz submission** — Jazzer is a first-class OSS-Fuzz engine; submit the project
  once the corpus + targets stabilise. Free continuous fuzzing.

## Build

- [ ] switch back to module-path, but keep in mind these 2 blockers:
-    [ ] 'dfa1' in package name is rejected by maven central
-    [ ] automatic module names for flatbuffers is rejected by maven central

## Tooling

- [ ] Optional `vortex-arrow` bridge module for Arrow ecosystem interop — see [ADR-0016](docs/adr/0016-vortex-arrow-bridge.md)

## API

- [ ] **Error messages — structural sanitization of `VortexException`** —
  Phase E (bounds typing via `IoBounds`) shipped; remaining is Phases A–D (the `Sanitize`
  helper + `VortexError` catalog). See [ADR-0003](docs/adr/0003-vortex-exception-sanitization.md)
  for design and phasing.
- [ ] Use domain primitives (`UInt32`, `UInt64`, etc.) as value classes via Project Valhalla instead of raw `long`/`int`
    - See [ADR-0008](docs/adr/0008-domain-primitives-unsigned-integers.md) and https://dfa1.github.io/articles/rethink-domain-primitives-with-valhalla
    - Candidates: `PType` integer kinds, buffer offsets, row indices, byte lengths
    - Goal: type-safety at zero cost (value class = no heap alloc, no boxing)
- [ ] **Generalize `Array.limited(rows)` → `Array.slice(offset, length)`** — single verb, matches `MemorySegment.asSlice` / `ByteBuffer.slice` / `List.subList`; `limited(rows)` becomes `slice(0, rows)`.
    - Zero-copy: buffer-backed (`Materialized*`, `length == elementCount`) slices the segment via `asSlice` (no per-element indirection); broadcast + view families fall back to `Offset*` wrappers
    - Fold nested slices (`slice` of an `Offset*` composes offsets — never stack `Offset(Offset(...))`)
    - Validate, fail fast (no silent clamp): consumer-facing `slice` throws `IllegalArgumentException` on `offset < 0 || length < 0 || offset + length > length()`; scan-internal `Offset*` construction fed by untrusted layout metadata throws `VortexException`
    - Add the matching compact-constructor bounds guard to all `Offset*` records (currently document `inner length >= offset + length` but enforce nothing — broadcast inner silently wraps via `i % cap`)

## Compute

- [ ] **Compute primitives — masks, kernels, no-materialise** — pushdown filter/compare/aggregate
  kernels operating on Lazy arrays without materialising. See [ADR-0013](docs/adr/0013-compute-primitives.md)
  (Proposed). Gate: a concrete downstream consumer (e.g. the vortex-arrow bridge or filter pushdown).

## Encodings

See [docs/compatibility.md](docs/compatibility.md) for the full encoding support table and S3 fixture status.

### `vortex.zstd` known limitations

- [ ] **Multi-frame encode** — `ZstdEncoding.Encoder` always produces a single frame for the whole array.
  Fix: accept a `valuesPerFrame` parameter (default: all values in one frame). Split the raw byte buffer at frame
  boundaries (`valuesPerFrame * byteWidth`), compress each slice independently, emit one `ZstdFrameMetadata` per frame.
  Enables partial decompression during slice scans.

- [ ] **Nullable arrays (encode)** — `ZstdEncoding.Encoder` has no null handling.
  Fix: accept nullable input (e.g. `Integer[]` or a validity mask alongside the data array). Strip null positions before
  compression. Encode the validity bitmap as a Bool child (child[0]) in the `EncodeNode`. Mirrors what Rust does: only
  valid values go into the compressed payload.

- [ ] **`vortex.pco` encode — FloatMult / FloatQuant modes** — Classic + Consecutive delta + IntMult ship; FloatMult/FloatQuant deferred. Marginal gain over existing Classic+ALP cascade; significant complexity (approx pair-GCD, false position root finder, trailing-zero detector).

