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

## Tooling

- [ ] **`vortex-arrow` bridge module** — see [ADR-0016](adr/0016-vortex-arrow-bridge.md).

## API

- [ ] **`VortexException` message sanitization** — see [ADR-0003](adr/0003-vortex-exception-sanitization.md).
- [ ] **Domain primitives (unsigned integers via Valhalla)** — see [ADR-0008](adr/0008-domain-primitives-unsigned-integers.md).

## Encodings

See [docs/compatibility.md](docs/compatibility.md) for the full encoding support table and S3 fixture status.

