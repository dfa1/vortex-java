# ADR 0020: Jazzer fuzz testing infrastructure

- **Status:** Accepted — fuzz mode landed as a dedicated `fuzz` module with one target
  (`VortexReader` full-file open); direct `PostscriptParser.parseBlobs` fuzzing is deferred (it is
  package-private, so a separate module needs a cross-module access seam); seed corpus,
  per-encoding targets, differential fuzzing, regression-corpus wiring, nightly CI, and OSS-Fuzz
  submission still pending
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

Existing `.vortex` integration fixtures seed `fuzz/src/test/resources/fuzz-corpus/full-file/`.
A small extraction tool walks those fixtures and dumps each segment to
`core/src/test/resources/fuzz-corpus/<encoding>/`, producing per-encoding sub-corpora without
hand-crafting inputs.

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
