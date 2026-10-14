# ADR 0024: JPMS adoption

- **Status:** Accepted (2026-10-14); first recorded as Proposed on 2026-09-06
- **Date:** 2026-09-06
- **Deciders:** project maintainer
- **Related:** [ADR 0017 — In-house FlatBuffers codegen](0017-in-house-flatbuffers-codegen.md),
  [PR #176 — feat(jpms): add module-info for core/reader/writer \[WIP\]](https://github.com/dfa1/vortex-java/pull/176) (superseded by PR #538)

## Context

`flatbuffers-java` used to make a real JPMS module graph impossible: it ships as an
automatic module with an unstable, jar-filename-derived name, and a named module
cannot reliably `requires` one. ADR 0017 removed that dependency in favor of the
in-house `fbs-gen`/`proto-gen` toolchain, which removed the blocker.

When this ADR was first written the decision was left open, leaning against: PR #176 got
`core`, `reader` and `writer` building as modules, but `writer`'s **test** sourceset failed
under `-Werror` because its round-trip tests decode with test-scope `reader` types while the
writer module (correctly) does not `require reader`. A second gap was that
`io.github.dfa1.zstd:zstd` (a separate project of this maintainer's) was an automatic module;
it now ships a real `module-info`, which closed it.

The case for doing it anyway: running the tests on the module path makes them check the module
graph (a missing `requires`, an accidental split package, a leaked internal) instead of the
classpath hiding all of it. The counter-argument stays true: JPMS adoption among consumers of a
Maven Central library is low, and the project's encapsulation goal (hiding the FlatBuffers/protobuf
runtime) was already met with ordinary `public`/package-private visibility. The decision is
therefore about what the project gets for its own build, not about downstream demand.

## Decision

Adopt JPMS for the published library modules and run surefire and failsafe on the module path
(PR #538, replacing the stale draft #176). The rules:

1. **Module name = package root.** `core`, `fsst`, `reader`, `writer`, `csv`, `jdbc` are
   `io.github.dfa1.vortex.<name>`; the inspector is `io.github.dfa1.vortex.inspect` because its
   package is `...inspect`, not `...inspector`. The artifact id may differ from the module name.
   The `dfa1` terminal-digit lint is suppressed with `@SuppressWarnings("module")` on each
   declaration: the name has to match the packages and the published group.
2. **A module with an automatic-module dependency stays descriptor-less.** A published named module
   must not `requires` a jar with no descriptor (its derived name is unstable). `parquet` (hardwood),
   `calcite` (calcite-core/babel), `cli` and the generators `fbs-gen`/`proto-gen` pin a stable
   `Automatic-Module-Name` in the jar manifest instead. Unpublished `performance`, `demo` and `fuzz`
   stay on the classpath (`performance` also declares a class in `core`'s `simd` package, a split
   package on the module path).
3. **Generated wire codecs are not public API.** `core` exports `fbs` and `proto` only to the modules
   that use them (a qualified export); a new consumer module is added to that list. Where a public
   signature must expose a generated type (`ArrayStats.fromFbs`, `VariantData`), the `exports` lint is
   suppressed on that one declaration with a comment rather than widening the export.
4. **The writer never requires the reader.** This is what blocked #176. Its round-trip tests see the
   reader on the test classpath, and the extra `--add-reads`/`--add-exports` live in the writer POM's
   `test.modules.args`, never in a `module-info`.
5. **`integration` has a code-less main descriptor.** Failsafe only uses the module path when the module
   has a descriptor, and a test-only descriptor would put every test dependency (including the
   invalid-named `zstd-native-*` resource jars) on the module path and fail at boot. A main descriptor
   requiring the named modules keeps the non-modular libraries (vortex-jni, hardwood, Arrow's netty
   bits, calcite) on the classpath.
6. **Shared test code cannot re-declare production packages.** `core`'s test-jar ships only
   `core.testing`; shipping its `*Test` classes was a split package.
7. **JVM flags are per module and named where possible.** The shared test `argLine` carries only the
   agent and Vector API flags; `test.native.args`, `test.unsafe.args` and `test.modules.args` are empty
   root properties that a module sets when its tests need them. A named module is enabled by name
   (`--enable-native-access=io.github.dfa1.zstd`), not `ALL-UNNAMED`.
8. **JDK 26 lints.** `-Xlint:-incubating` is set once in the root compiler config because every module
   resolving `core` sees `jdk.incubator.vector` (its `requires static`); `missing-explicit-ctor` is off
   in `reader`/`writer`, whose public encoders and decoders are stateless and registry-instantiated.

## Consequences

### Positive

- A broken or missing `requires`, an accidental split package or a leaked internal fails the unit
  tests, not a consumer.
- Native access is granted to the one module that needs it, so a new native caller is a visible warning.
- Module-path consumers and `jlink` users get stable names for every published artifact.

### Negative

- Tests that reach across modules need explicit flags (rule 4), and a new qualified-export consumer must
  be added by hand (rule 3).
- `integration` runs half on the module path and half on the classpath (rule 5), so it does not
  exercise a fully modular consumer.
- Public APIs that expose a generated type carry a lint suppression.
- Mockito and package-private test access are the standing friction of a real module boundary; they
  have been manageable so far because tests are patched into the module they test.

### Risks to manage

- The `Automatic-Module-Name` modules (rule 2) can become named modules once hardwood and calcite ship
  descriptors; revisit then.
- JDK lint categories change between releases (rule 8); CI runs on 25 and 26 to catch it.

## Alternatives considered

- **Keep the deferral.** Rejected: the blockers were closed, and classpath-only tests hide
  module-graph errors.
- **Descriptors for every module, `requires` of automatic modules.** Rejected for published modules
  (unstable derived names).
- **A test-side descriptor for `integration`.** Tried: it puts invalid-named native resource jars on the
  module path and fails at boot.
- **Widening `core`'s `fbs`/`proto` exports.** Rejected: it would publish the whole generated codec.
- **Partial modularization (core only).** Rejected: `reader` and `writer` are the modules whose
  FFM/zero-copy internals most benefit from encapsulation.

## References

- [PR #538 — JPMS modules, surefire and failsafe on the module path](https://github.com/dfa1/vortex-java/pull/538)
- [PR #176 — feat(jpms): add module-info for core/reader/writer \[WIP\]](https://github.com/dfa1/vortex-java/pull/176) (stale draft)
- [ADR 0017 — In-house FlatBuffers codegen + MemorySegment-native runtime](0017-in-house-flatbuffers-codegen.md)
- [ADR 0001 — Split read and write runtimes](0001-split-read-and-write-runtimes.md) — the
  writer-never-depends-on-reader rule
