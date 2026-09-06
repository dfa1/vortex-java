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

### Resource caps

- [ ] **Implement `ResourceLimits` + `ReadOptions`** — see [ADR-0004](adr/0004-resource-caps-read-options.md).

### Fuzz infrastructure

- [ ] **Jazzer fuzz testing, remaining scope** — see [ADR-0020](adr/0020-jazzer-fuzz-infrastructure.md).
  A fuzz-mode target for `VortexReader` full-file open already landed (`fuzz` module,
  `@Tag("fuzz")`, opt-in via `JAZZER_FUZZ=1`), seeded with five existing reader fixtures (measured:
  175→550 edges in a 30s run vs. an unseeded start). Remaining: direct fuzzing of
  `PostscriptParser.parseBlobs` (deferred — it is package-private, so a target in the separate
  `fuzz` module needs a cross-module access seam such as a reader test-jar wrapper, and neither a
  split package nor a wider public surface is acceptable), an automated seed-corpus extraction tool
  producing per-encoding sub-corpora from the full integration-fixture set (the five hand-picked
  seeds are a stopgap), one `@FuzzTest` per encoding `decode()`, differential fuzzing vs
  `vortex-jni`, regression-corpus wiring into routine `./mvnw test`, nightly CI profile, OSS-Fuzz
  submission.

## Build

- [ ] **Docs compiler — remaining tier** — `DocsConsistencyTest` (integration) now gates FQNs,
  method claims, service-file paths, and relative links in the living docs (first run caught 11
  fossils the same-day manual audit missed); `EncodingTableFitnessTest` now golden-tests the
  encodings table in `docs/compatibility.md` against `ReadRegistry`/`WriteRegistry`. Remaining:
  compile ` ```java ` blocks from living docs (tutorial imports were stale for months).

## Tooling

- [ ] **`vortex-arrow` bridge module** — see [ADR-0016](adr/0016-vortex-arrow-bridge.md).

## API

- [ ] **`VortexException` message sanitization** — see [ADR-0003](adr/0003-vortex-exception-sanitization.md).
- [ ] **Domain primitives (unsigned integers via Valhalla)** — see [ADR-0008](adr/0008-domain-primitives-unsigned-integers.md).

## Encodings

See [docs/compatibility.md](docs/compatibility.md) for the full encoding support table and S3 fixture status.

