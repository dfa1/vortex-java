# Testing strategy

How vortex-java is tested, layer by layer, and why each layer exists. Counts are a
snapshot of the current `main` (test *executions*, i.e. after `@ParameterizedTest`
expansion) and move with the code; treat them as orders of magnitude, not contracts.

## Why these layers

The reader memory-maps and parses **untrusted binary input**, and the writer must
produce files a *different* implementation (the Rust reference) can read back exactly.
Two properties dominate the strategy:

- **Correctness is defined by interop, not by us.** There is no separate written spec;
  the Rust implementation is ground truth. Anything that crosses the wire format is
  pinned by a Java↔Rust round-trip.
- **Malformed input must fail safely.** Every bad file throws `VortexException`, never a
  raw `IndexOutOfBoundsException`, `OutOfMemoryError`, or FlatBuffer/Protobuf runtime
  exception. The bounds/parse paths that enforce this are the most heavily verified code
  in the project.

The layers below go from fast-and-narrow to slow-and-end-to-end. Most defects should be
caught at the lowest layer that can see them.

## Layers at a glance

| Layer | Runner | ~Executions | Scope |
|-------|--------|-------------|-------|
| Unit | surefire | ~2,690 | One class/behaviour, in-memory, no I/O |
| Property-based | surefire | (subset of unit) | Seeded-random sweeps over encode/decode |
| Integration | failsafe | ~271 | Java↔Rust interop + real files + CLI end-to-end |
| Mutation | PIT (opt-in) | — | Adequacy of tests for bounds/parse classes |
| Benchmarks | JMH (`./bench`) | — | Performance, not correctness |

Per-module unit counts: core 256, proto-gen 9, reader 780, writer 1,419, cli 154,
inspector 34, parquet 24, jdbc 9, csv 7. ~174 test classes total.

## Unit tests (`./mvnw test`)

The base of the pyramid. JUnit 5 + Mockito (BDDMockito) + AssertJ. Rules: fast, no file
I/O, no network, no sleep — mock or use in-memory `MemorySegment`s. Each test follows
`// Given` / `// When` / `// Then`; the class under test is `sut`.

What they cover, by module:

- **core** (256) — `DType`/`PType` modelling, `IoBounds` guards, `PTypeIO` little-endian
  segment reads/writes, proto record encode/decode.
- **reader** (780) — every `EncodingDecoder` and `Array` subtype, the file-structure
  parsers (`Footer`, `Trailer`, `PostscriptParser`, `Layout`), and the lazy/chunked/dict
  array families. Largest suite because decode has the most branches.
- **writer** (1,419) — every `EncodingEncoder`, the `CascadingCompressor` selection logic,
  `WriteRegistry`, and the extension encoders. Largest module overall.
- **cli / inspector / parquet / jdbc / csv** — command parsing, TUI rendering, importers.

Each encoding aims to cover the happy path, negative cases (invalid input → `VortexException`),
and corners (empty, zero, max, boundaries).

## Property-based tests

A subset of the unit layer that replaces hand-picked cases with **seeded-random
generators**, because example-based tests miss corners. The generators target the
distributions that exercise distinct code paths (constant runs, low cardinality, tight
clusters, monotone, full-range, sparse outliers) and assert lossless round-trips
(`decode(encode(x)) == x`, bit-exact for floats via ±0-collapse canonicalisation).

Seeds are fixed so any failure reproduces. Current property suites:

- `RoundTripPropertyTest` — Delta, FrameOfReference, ZigZag, AlpRd (i32/i64/f32/f64).
- `BitpackedEncodingEncoderTest` — bit-width sweep across all widths.
- `CascadingCompressorTest.RoundTripProperty` — the full encoder-selection + nesting
  pipeline, every codec at cascade depth 0–3.
- `PcoEncodingEncoderTest` / `PcoEncodingDecoderTest` — Pco mode pickers (delta, IntMult),
  bin optimiser, and ANS/patch paths over mixed distributions.

## Integration tests (`./mvnw verify -pl integration -am`)

The **ground truth** layer: failsafe (`*IntegrationTest`), not surefire. These cross the
JNI boundary to the Rust reference and read/write real files.

- **`RustWritesJavaReadsIntegrationTest`** — Rust writes, Java reads; verifies our decoders
  against the canonical writer.
- **`JavaWritesRustReadsIntegrationTest`** (212 cases) — Java writes, Rust reads; verifies
  our encoders produce spec-correct files. Per-encoding round-trips are generated from the
  seeded `RandomArrays` source.
- **`RustJavaReaderComparisonIntegrationTest`** — both read the same file; values must match.
- **`Variant…`, `Parquet…`, `Taxi…Oracle…`** — variant interop, Parquet import, and a
  real-world dataset (NYC taxi / ClickBench-shaped) oracle comparison.
- **`CliIT`, `VortexInspector…`** — the built CLI/inspector exercised end-to-end.

There is **one integration round-trip per encoding and per file-format boundary** — this is
where a wire-format regression surfaces.

## Mutation testing (PIT, `-P pitest`)

Opt-in, bound to `verify`, scoped via `<targetClasses>` to the **security-critical
bounds/parse classes** — not the whole codebase. It measures whether the tests actually
*catch* faults, not just execute lines.

```bash
./mvnw -pl core   -P pitest verify              # IoBounds, PTypeIO
./mvnw -pl reader -am -P pitest verify -DskipITs # Footer, Trailer, PostscriptParser,
                                                 # SegmentSpec, Layout, FlatSegmentDecoder
./mvnw -pl writer -am -P pitest verify -DskipITs # ChunkImpl, WriteRegistry
```

Reports land in `<module>/target/pit-reports/`. Read a surviving mutant as a
**simplify-first** signal: an equivalent mutant often marks a clause that can never change
the outcome (dead code) — delete it rather than writing an unkillable test. Only add a test
when the mutated bound is a genuine independent edge. These classes currently sit at
99–100% kill rate.

## Benchmarks (`./bench ClassName.methodName`)

JMH benchmarks under `performance/` measure throughput against the Rust reference
(`RustVsJavaReadBenchmark`, `…WriteBenchmark`, `…FilterBenchmark`, `ParquetVsVortexReadBenchmark`).
They are **performance signal, not correctness** — never gated in CI, always run with an
explicit `ClassName.methodName` filter.

## Coverage and quality gate

Coverage (JaCoCo, aggregated across surefire + failsafe) is ~81% and is reported to
SonarCloud daily. Generated `fbs/`/`proto/` sources and the `performance/` benchmark module
are excluded — they have no hand-written behaviour worth covering. The quality gate requires
zero bugs and zero vulnerabilities; the build itself fails on any javac warning
(`-Xlint:all -Werror`), zero Checkstyle violations, and zero Javadoc warnings.
