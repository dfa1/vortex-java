# ADR 0020: Jazzer fuzz testing infrastructure

- **Status:** Accepted — fuzz mode landed as a dedicated `fuzz` module with one target
  (`VortexReader` full-file open), seeded with a handful of existing small reader fixtures so the
  mutator starts past the trailer magic instead of having to guess it; direct
  `PostscriptParser.parseBlobs` fuzzing is deferred (it is package-private, so a separate module
  needs a cross-module access seam); automated per-encoding corpus extraction, per-encoding
  targets, differential fuzzing, regression-corpus wiring, nightly CI, and OSS-Fuzz submission
  still pending
- **Date:** 2026-07-12
- **Deciders:** project maintainer
- **Related:** [ADR 0003 — VortexException sanitization](0003-vortex-exception-sanitization.md),
  [ADR 0004 — Resource caps and `ReadOptions`](0004-resource-caps-read-options.md),
  [TODO.md §Security](../TODO.md)

## Context

The reader's security contract (TODO.md §Security) requires every malformed input to fail as
`VortexException`, never a raw JVM crash class (`ArrayIndexOutOfBoundsException`,
`OutOfMemoryError`, `StackOverflowError`, a raw FlatBuffer/Protobuf parser exception). Per-encoding
adversarial unit tests cover known gotchas by hand (non-monotonic offsets, out-of-range codes,
negative dimensions, …), but hand-written cases only exercise inputs someone already thought of —
they don't explore the input space the way a coverage-guided fuzzer does.

[Jazzer](https://github.com/CodeIntelligenceTesting/jazzer) is a coverage-guided fuzzer for the
JVM built on libFuzzer, with first-class JUnit 5 integration (`@FuzzTest`) and is also a supported
OSS-Fuzz engine.

## Decision

Adopt Jazzer via the `com.code-intelligence:jazzer-junit` test dependency, in two modes:

- **regression** — `./mvnw test` replays the saved corpus and crash reproducers, so every
  fuzz-discovered bug becomes a permanent part of the ordinary test run.
- **fuzz** — `JAZZER_FUZZ=1`, run on a nightly profile; this is the mode that actually explores
  new inputs. It must not run on every normal build.

### Seed corpus

**Landed (minimal):** five existing `reader/src/test/resources/fixtures/*.vortex` files
(`null`, `varbin`, `booleans`, `primitives`, `chunked` — real, valid, already-committed fixtures
used by other reader tests) are copied as-is into
`fuzz/src/test/resources/io/github/dfa1/vortex/fuzz/VortexReaderFuzzTestInputs/openMalformedFile/`,
Jazzer's standard JUnit seed-corpus location (`<TestClass>Inputs/<method>/<file>` under
`src/test/resources`, mirroring the test class's package). Measured effect: a 30 s `JAZZER_FUZZ=1`
run went from 175 edges / ~19.7k exec/s (guessing the 4-byte `VTXF` trailer magic essentially
never happens) to ~550 edges / 1099 features, with the auto-dictionary recovering real encoding-id
strings (`vortex.bool`, `vortex.fixed_size_list`, …) out of the FlatBuffer footer — confirms the
mutator is now past the trailer and genuinely walking the footer/layout/dtype structure.

**Still pending (automated):** a small extraction tool walking the full integration-fixture set,
dumping each segment to per-encoding sub-corpora (originally scoped to
`core/src/test/resources/fuzz-corpus/<encoding>/`), for when the per-encoding `@FuzzTest` targets
below land — the five hand-picked files above are a stopgap, not a replacement for that.

### Fuzz targets

- `VortexReader.open(byte[])` — full-file parse path.
- `PostscriptParser.parseBlobs` — postscript/footer parsing boundary.
- One `@FuzzTest` per encoding `Encoding.decode` — per-encoding decode boundary.

Crash oracle: `ignore = {VortexException.class}` — anything else is a real finding, per the
security contract in TODO.md §Security.

### Differential fuzz (Java vs Rust)

Round-trip random bytes through Java decode and `vortex-jni`; assert both throw or both return
identical row count + values. Reuses the `RustWritesJavaReadsIntegrationTest` harness — this mode
catches semantic divergence from the Rust reference, not just crashes.

### OSS-Fuzz submission

Once the corpus and targets stabilize, submit the project to OSS-Fuzz. Jazzer is a first-class
OSS-Fuzz engine, giving free continuous fuzzing beyond what CI runs.

## Consequences

### Positive

- Explores the untrusted-input space beyond hand-written adversarial tests; the per-encoding
  gotcha list in TODO.md §Security becomes a floor, not a ceiling.
- Crash reproducers become permanent regression tests via the JUnit 5 integration.
- Differential mode catches semantic divergence from the Rust reference, not just crashes.
- OSS-Fuzz gives continuous fuzzing without maintaining dedicated infrastructure.

### Negative

- New test dependency (`jazzer-junit`) and a nightly CI profile to maintain.
- The fuzz corpus needs periodic curation as encodings evolve, or it silently stops finding
  anything new.

### Risks to manage

- `JAZZER_FUZZ=1` must stay opt-in (nightly profile only) — active fuzzing on every
  `./mvnw test` would make the normal build slow and non-deterministic.
- Crash reproducers must be triaged into either a real fix or an explicit `VortexException`
  classification before landing in the corpus, or noise accumulates.

## Alternatives considered

- **Hand-written adversarial tests only (status quo)** — cheap, but bounded by what a human
  enumerates; the per-encoding gotcha list in TODO.md §Security exists precisely because this
  approach misses things.
- **AFL/libFuzzer via a custom JNI harness** — more mature native fuzzing ecosystem, but requires
  building and maintaining a harness that bridges into JVM bytecode. Jazzer already does this and
  ships JUnit 5 integration out of the box.

## References

- [Jazzer](https://github.com/CodeIntelligenceTesting/jazzer)
- [TODO.md §Security](../TODO.md)
- [ADR 0003 — VortexException sanitization](0003-vortex-exception-sanitization.md)
- [ADR 0004 — Resource caps and `ReadOptions`](0004-resource-caps-read-options.md)
