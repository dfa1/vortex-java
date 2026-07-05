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
| Unit | surefire | ~2,690 | One class/behavior, in-memory, no I/O |
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

- **core** (256) — `DType`/`PType` modeling, `IoBounds` guards, `PTypeIO` little-endian
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
  bin optimizer, and ANS/patch paths over mixed distributions.

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

### Load test (`LargeCsvRoundTripLoadIntegrationTest`)

A CSV → Vortex → CSV round-trip whose data is a **deterministic seeded generator** that also
serves as the oracle: exported rows are diffed against regenerated rows, so no second copy is
stored. Two methods share one pipeline:
- a 1 000-row case runs in every integration build (guards the generator/diff logic);
- a load case gated on `-Dvortex.load.rows=<n>` (scratch dir via `-Dvortex.load.dir`).

The daily `.github/workflows/load.yml` cron runs it at ~100 M rows (~10 GB) on `ubuntu-latest`,
writing to `/mnt` (the runner's large disk). Byte-exact round-trip holds because the generator
emits only canonical `Long`/`Double`/`Boolean.toString` + plain-ASCII forms that
`CsvExporter` reproduces exactly. Trigger manually via the workflow's `workflow_dispatch`
(`rows` input) or locally: `./mvnw verify -pl integration -am
-Dit.test=LargeCsvRoundTripLoadIntegrationTest -Dvortex.load.rows=1000000`.

## Mutation testing (PIT, `-P pitest`)

Opt-in, bound to `verify`, scoped via `<targetClasses>` to the **security-critical
bounds/parse classes** — not the whole codebase. It measures whether the tests actually
*catch* faults, not just execute lines.

```bash
./mvnw -pl core   -P pitest verify              # IoBounds, PTypeIO
./mvnw -pl reader -am -P pitest verify -DskipITs # Footer, Trailer, PostscriptParser,
                                                 # SegmentSpec, Layout, SerializedArrayDecoder
./mvnw -pl writer -am -P pitest verify -DskipITs # ChunkImpl, WriteRegistry
```

Reports land in `<module>/target/pit-reports/`. Read a surviving mutant as a
**simplify-first** signal: an equivalent mutant often marks a clause that can never change
the outcome (dead code) — delete it rather than writing an unkillable test. Only add a test
when the mutated bound is a genuine independent edge. These classes currently sit at
99–100% kill rate.

## Benchmarks (`./bench ClassName.methodName`)

JMH benchmarks under `performance/` measure throughput against the Rust reference
(`JavaVsJniReadBenchmark`, `…WriteBenchmark`, `…FilterBenchmark`, `ParquetVsVortexReadBenchmark`).
They are **performance signal, not correctness** — never gated in CI, always run with an
explicit `ClassName.methodName` filter.

## Coverage and quality gate

Coverage (JaCoCo, aggregated across surefire + failsafe) is ~81% and is reported to
SonarCloud daily. Generated `fbs/`/`proto/` sources and the `performance/` benchmark module
are excluded — they have no hand-written behavior worth covering. The quality gate requires
zero bugs and zero vulnerabilities; the build itself fails on any javac warning
(`-Xlint:all -Werror`), zero Checkstyle violations, and zero Javadoc warnings.

## Reading the signals: Sonar and PIT as data, not verdicts

SonarCloud and PIT both report facts, not judgments. A Sonar finding ("this line is
uncovered", "these blocks are duplicated") is a pointer to look, not a defect by itself —
the interpretation is the engineering work. Two patterns recur often enough to be worth
naming.

### An uncovered line is one of three things

When Sonar flags a line as not covered, it is exactly one of:

1. **Missing test** — reachable by valid input, just never exercised. Add the test.
2. **Dead code** — unreachable by any input. Delete it; a test would only pin behavior
   that can never run.
3. **Defensive-by-contract** — reachable only if an invariant is already broken: the
   `default -> throw new VortexException(...)` arms, the `catch (IOException)` on metadata
   decode, the `logicalIdx < 0 || >= rowCount` guards on malformed offsets. Not dead (it
   guards a real corruption case), but unreachable through the *writer*, which only emits
   valid files. Keep it, and either cover it with a hand-crafted malformed-input test or
   leave a comment stating the invariant it defends.

Coverage alone cannot tell these apart — it only says "not executed". The deciding question
is *can any input reach this line?* **Mutation testing answers it where line coverage cannot:**
a mutant that survives on a covered line is either an untested-reachable edge (bucket 1) or
an equivalent mutant on a clause that can never change the outcome (bucket 2, dead code).
That is why PIT is scoped to the bounds/parse classes — those are dense with bucket-3 guards,
and the kill rate tells us which guards are genuinely load-bearing. Read a survivor
**simplify-first**: prefer deleting the clause over writing an unkillable test.

### Duplication can be real or deliberate

Sonar's duplication metric is also a pointer, not an order. Most flagged duplication is real
and should be factored out — e.g. the four `unpackLoop8/16/32/64` methods in
`BitpackedEncodingDecoder` each rebuilt an identical per-row schedule, now hoisted into one
`schedule(typeBits, bitWidth)` helper. But some duplication is the price of a hard
constraint: the per-element inner unpack loops in those same methods stay specialized per
width on purpose, because a generic `ValueLayout`/accessor would stop C2 from constant-folding
the typed access and block superword vectorization (the hot-loop rule). When duplication and a
performance or safety invariant conflict, the invariant wins — factor out the cold,
run-once part and leave the hot, specialized part alone, with a comment saying why.

The throughline: let the tools point at the data, then decide with the context they do not
have.

## Documentation fitness functions

Two [architectural fitness functions](https://www.thoughtworks.com/insights/books/building-evolutionary-architectures)
(atomic / triggered / static / automated) guard the living docs, both in the integration module:

- `DocsConsistencyTest` — **accuracy**: FQNs resolve, method claims name real methods,
  `META-INF/services` mentions exist, relative links don't dangle.
- `EncodingTableFitnessTest` — **completeness**: the encodings table in `docs/compatibility.md`
  lists exactly the encodings the code registers via `ServiceLoader` (id, decoder/encoder class,
  ✅/❌ flags), asserted not generated — on drift it prints the exact rows to add.

## Docs consistency gate

`DocsConsistencyTest` (integration module, plain surefire) machine-checks the living docs
(`CLAUDE.md`, `README.md`, `docs/*.md`) against the codebase: project FQNs must resolve,
`META-INF/services` mentions must name real files, relative links must not dangle, and
backticked `Class.method(...)` claims must name real methods (external receivers live on a
conscious allowlist in the test). Historical records (`adr/`, released CHANGELOG sections)
are exempt. Run it alone with:

```bash
./mvnw test -pl integration -am -Dtest=DocsConsistencyTest
```
