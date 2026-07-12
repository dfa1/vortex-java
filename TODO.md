# TODO

## Project

- [ ] Move project to a dedicated organization
- [ ] Create website
   - build something like hardwood.dev but for vortex files

## Performance

- [ ] **Benchmark publishing** — see [ADR-0006](adr/0006-benchmark-publishing.md).
- [ ] Performance tests must be peer-reviewed
- [ ] Run performance tests on other machines (I have access only to Apple M5)
- [ ] **Vector API adoption** — see [ADR-0005](adr/0005-vector-api-adoption.md).

## Security

**Contract:** the reader memory-maps and parses untrusted binary input. Every malformed input must
throw `VortexException`, never `ArrayIndexOutOfBoundsException`, `NegativeArraySizeException`,
`OutOfMemoryError`, `StackOverflowError`, a raw FlatBuffer runtime exception, or a Protobuf
parser exception. Each entry below is either a known gap, a contract audit, or supporting infra.

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

- [ ] **Implement `ResourceLimits` + `ReadOptions`** — see [ADR-0004](adr/0004-resource-caps-read-options.md).

### Fuzz infrastructure

- [ ] **Jazzer fuzz testing** — see [ADR-0020](adr/0020-jazzer-fuzz-infrastructure.md).

## Build

- [ ] use JPMS, watch out for "dfa1" in package name
- [ ] **Docs compiler — remaining tiers** — `DocsConsistencyTest` (integration) now gates FQNs,
  method claims, service-file paths, and relative links in the living docs (first run caught 11
  fossils the same-day manual audit missed). Remaining:
  1. Golden-test or generate enumerable tables (encodings/extensions/layout ids in
     `docs/reference.md`, `docs/compatibility.md`) from `EncodingId.WellKnown`/`LayoutId.WellKnown`/
     service manifests — a declared capability the code lacks is a bug, in both directions.
  2. Compile ` ```java ` blocks from living docs (tutorial imports were stale for months).

## Tooling

- [ ] **`vortex-arrow` bridge module** — see [ADR-0016](adr/0016-vortex-arrow-bridge.md).

## API

- [ ] **`VortexException` message sanitization** — see [ADR-0003](adr/0003-vortex-exception-sanitization.md).
- [ ] **Domain primitives (unsigned integers via Valhalla)** — see [ADR-0008](adr/0008-domain-primitives-unsigned-integers.md).

## Encodings

See [docs/compatibility.md](docs/compatibility.md) for the full encoding support table and S3 fixture status.

