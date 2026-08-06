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

### FSST follow-ups

Out of scope for the #287 rewrite (which, together with the follow-up hot-path pass, closed the
`vortex-jni` gap with a scalar, branch-free algorithm — encode at parity, decode ~1.16x faster on
`JavaVsJniFsstBenchmark` — see [ADR-0022](adr/0022-fsst-module-extraction.md)):

- [ ] **True per-row lazy/random-access decompression** exploiting FSST's headline random-access
  property — today `FsstEncodingDecoder.decode()` eagerly materializes the whole column up front
  regardless of what is queried. Connects to [ADR-0010](adr/0010-lazy-decode.md) (Lazy decode) but
  is a separate initiative.
- [ ] **OptFSST** (2026 arXiv follow-up: DP-based training instead of greedy, ~4x slower training
  for 7–17% better compression) — a documented future option, not adopted, since it moves off the
  classic greedy-FSST speed/compression tradeoff this rewrite targets (matching what `vortex-jni`
  itself uses).

## Security

See [CLAUDE.md §Security contract](CLAUDE.md) for the invariant. Each entry below is either a
known gap, a contract audit, or supporting infra.

### Per-encoding adversarial tests

Each encoding's `decode(DecodeContext)` should be exercised against crafted metadata that
decodes but disagrees with the buffer payload. `bufferIndices[i] >= ctx.bufferCount()` (and the
equivalent child-index check) is centralized in `DecodeContext.buffer(i)`/`decodeChild(i)`.
VarBin, Dict, Bitpacked, ALP, Sparse, Chunked, Struct, RunEnd, Constant, Zoned, and Pco are done.

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

