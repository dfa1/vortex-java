# ADR 0027: JPMS layout and rules

- **Status:** Accepted
- **Date:** 2026-10-14
- **Deciders:** project maintainer
- **Supersedes:** the Decision section of [ADR 0024](0024-jpms-adoption.md)
- **Superseded by:** —

## Context

[ADR 0024](0024-jpms-adoption.md) recorded the JPMS tradeoff and left the decision open, leaning against it. Two things
changed. The `io.github.dfa1.zstd` dependency now ships a real `module-info`, which closed the last blocker that was within the
maintainer's control. And running the tests on the module path became a goal in itself: it makes the unit and integration
tests check the module graph (missing `requires`, accidental split packages, leaked internals) instead of the classpath hiding
all of it. Draft PR #176 was stale and is replaced by PR #538.

## Decision

Adopt JPMS for the published library modules and run surefire and failsafe on the module path. The rules:

1. **Module name = package root.** `core`, `fsst`, `reader`, `writer`, `csv`, `jdbc` are `io.github.dfa1.vortex.<name>`;
   the inspector is `io.github.dfa1.vortex.inspect` because its package is `...inspect`, not `...inspector`. The artifact id may
   differ from the module name. The `dfa1` terminal-digit lint is suppressed with `@SuppressWarnings("module")` on each
   declaration: the name has to match the packages and the published group.
2. **A module with an automatic-module dependency stays descriptor-less.** A published named module must not `requires` a jar with
   no descriptor (its derived name is unstable). `parquet` (hardwood), `calcite` (calcite-core/babel), `cli` and the generators
   `fbs-gen`/`proto-gen` pin a stable `Automatic-Module-Name` in the jar manifest instead. Unpublished `performance`, `demo`, `fuzz`
   stay on the classpath (`performance` also declares a class in `core`'s `simd` package, a split package on the module path).
3. **Generated wire codecs are not public API.** `core` exports `fbs` and `proto` only to the modules that use them (a qualified
   export). A new consumer module is added to that list. Where a public signature must expose a generated type
   (`ArrayStats.fromFbs`, `VariantData`), the `exports` lint is suppressed on that one declaration with a comment, rather than
   widening the export.
4. **The writer never requires the reader.** Its round-trip tests see the reader on the test classpath, and the extra
   `--add-reads`/`--add-exports` live in the writer POM's `test.modules.args`, never in a `module-info`.
5. **`integration` has a code-less main descriptor.** Failsafe only uses the module path when the module has a descriptor, and a
   test-only descriptor would put every test dependency (including the invalid-named `zstd-native-*` resource jars) on the
   module path. A main descriptor requiring the named modules keeps the non-modular libraries (vortex-jni, hardwood, Arrow's
   netty bits, calcite) on the classpath.
6. **Shared test code cannot re-declare production packages.** `core`'s test-jar ships only `core.testing`; shipping its `*Test`
   classes was a split package.
7. **JVM flags are per module and named where possible.** The shared test `argLine` carries only the agent and Vector API flags;
   `test.native.args`, `test.unsafe.args` and `test.modules.args` are empty root properties that a module sets when its tests need
   them. A named module is enabled by name (`--enable-native-access=io.github.dfa1.zstd`), not `ALL-UNNAMED`.
8. **JDK 26 lints.** `-Xlint:-incubating` is set once in the root compiler config because every module resolving `core` sees
   `jdk.incubator.vector` (its `requires static`); `missing-explicit-ctor` is off in `reader`/`writer`, whose public encoders and
   decoders are stateless and registry-instantiated.

## Consequences

### Positive

- A broken or missing `requires`, an accidental split package or a leaked internal fails the unit tests, not a consumer.
- Native access is granted to the one module that needs it, so a new native caller is a visible warning.
- Module-path consumers and `jlink` users get stable names for every published artifact.

### Negative

- Tests that reach across modules need explicit flags (rule 4), and a new qualified-export consumer must be added by hand (rule 3).
- `integration` runs half on the module path and half on the classpath (rule 5), so it does not exercise a fully modular consumer.
- Public APIs that expose a generated type carry a lint suppression.

### Risks to manage

- The `Automatic-Module-Name` modules (rule 2) can become named modules once hardwood and calcite ship descriptors; revisit then.
- JDK lint categories change between releases (rule 8); CI runs on 25 and 26 to catch it.

## Alternatives considered

- **Keep ADR 0024's deferral.** Rejected: the main blocker was closed, and classpath-only tests hide module-graph errors.
- **Descriptors for every module, `requires` of automatic modules.** Rejected for published modules (unstable derived names).
- **A test-side descriptor for `integration`.** Tried: it puts invalid-named native resource jars on the module path and fails at boot.
- **Widening `core`'s `fbs`/`proto` exports.** Rejected: it would publish the whole generated codec.

## References

- [ADR 0024 — JPMS adoption](0024-jpms-adoption.md), [ADR 0017 — In-house FlatBuffers codegen](0017-in-house-flatbuffers-codegen.md)
- PR #538, superseding draft PR #176
