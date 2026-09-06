# ADR 0024: JPMS adoption for core/reader/writer

- **Status:** Proposed
- **Date:** 2026-09-06
- **Deciders:** project maintainer
- **Related:** [ADR 0017 — In-house FlatBuffers codegen](0017-in-house-flatbuffers-codegen.md),
  [PR #176 — feat(jpms): add module-info for core/reader/writer \[WIP\]](https://github.com/dfa1/vortex-java/pull/176)

## Context

`flatbuffers-java` used to make a real JPMS module graph impossible: it ships as an
automatic module with an unstable, jar-filename-derived name, and a named module
cannot reliably `requires` one. ADR 0017 removed that dependency in favor of the
in-house `fbs-gen`/`proto-gen` toolchain, which removed the blocker — but nobody has
finished adding `module-info.java` since. TODO.md has carried a single unqualified
line ("use JPMS, watch out for 'dfa1' in package name") since before that ADR, with
no record of *why* it might be worth doing or what it would cost.

PR #176 is the in-progress attempt, parked as draft ("do not merge yet"). It already
gives a concrete, evidence-based account of both sides:

**What works today:** `core`, `reader`, and `writer` (main sourcesets) are all
modular with clean, green builds (359 / 857 tests respectively for core/reader).
`writer` correctly does not `require reader`, preserving the architecture rule from
ADR 0001. Downstream modules (`calcite`, `cli`, `csv`, `jdbc`, `parquet`,
`inspector`) are left non-modular by design, consumed as automatic modules.

**What's blocking it:** `writer`'s **test** sourceset fails under `-Werror`.
Writer's tests decode-verify round-trips using test-scope `reader` types, but they
patch into the `writer` module, which — correctly — does not `require reader` in
its main module graph. The workaround attempted (`testCompile
--add-reads=ALL-MODULE-PATH` plus Surefire `useModulePath=false`) still leaves a
residual exports/reads lint warning. There is, as of this ADR, no clean way to let
the test module read `reader` without polluting the main module graph — this is
the actual reason #176 is still a draft, not the historically-cited flatbuffers
blocker.

A second, smaller gap: `reader`/`writer` both `requires static zstd` against
`io.github.dfa1.zstd:zstd` (a separate project of this maintainer's), which itself
resolves as an automatic module with no `module-info.java` of its own. Unlike the
former `flatbuffers-java` situation, this one is fully within this maintainer's
control to close — but it hasn't been done, so it remains a live gap today.

Separately from the mechanics: **JPMS adoption for consumed libraries is low in
practice.** Most consumers put jars on the classpath regardless of whether a
`module-info.java` exists; strict encapsulation and `jlink` custom runtime images
matter far more for applications than for a library shipped to Maven Central. The
actual encapsulation goal this project cares about — internal FlatBuffers/protobuf
runtime classes (`FbsTable`, `FbsMemorySegment`, the proto codec) staying hidden —
is already achieved today via ordinary `public`/package-private visibility, with no
module system required.

## Decision

Not yet made — this ADR records the tradeoff for a decision that is still open.
Recorded here for future reference so the same ground doesn't need re-deriving.

The maintainer's current lean: **do not prioritize finishing PR #176** absent a
concrete downstream request for a strict module graph or a `jlink`-based runtime
image. Keep it parked exactly as drafted (do not merge) rather than force through
the test-sourceset workaround under time pressure, and revisit if a real consumer
need surfaces — mirroring the "capture the design now, defer implementation until
a concrete need" posture already used in [ADR 0016](0016-vortex-arrow-bridge.md).

## Consequences

### Positive

- If finished: a verified, reliable module graph for `core`/`reader`/`writer`,
  catching missing/duplicate dependency issues at compile time instead of runtime.
- If finished: unlocks `jlink` custom runtime images for anyone embedding
  vortex-java in a minimal-footprint deployment (e.g. the `cli` module).
- The investigation itself (PR #176) already validated that ADR 0017's dependency
  removal was sufficient to make the main-sourceset module graph work cleanly.

### Negative

- **Test friction is the dominant cost.** Package-private internals that tests
  reach into (this project's own convention — see CLAUDE.md's `FbsTable`/proto
  runtime pattern) turn into split-package errors or `--patch-module`/`--add-reads`
  Surefire configuration once a real module boundary exists, as PR #176's own
  blocker demonstrates concretely.
- Mockito-based mocking of non-trivial classes needs per-package `opens` directives
  — ongoing bookkeeping unrelated to the domain.
- Per-module `module-info.java` maintenance scales with this project's module
  count (16 modules today), for a payoff that mostly benefits application-style
  consumers, which vortex-java mostly isn't.

### Risks to manage

- The `io.github.dfa1.zstd:zstd` automatic-module gap is self-inflicted (this
  maintainer's own dependency) and cheap to close independently of the rest of
  this decision — giving it a real `module-info.java` removes one blocker
  regardless of what happens with #176.
- If revisited later, re-verify the FlatBuffers-derived blocker is still fully
  gone (ADR 0017 must not have regressed) before resuming from #176's state.

## Alternatives considered

- **Finish #176 now.** Rejected for the moment: the test-sourceset blocker needs
  a real solution (not a suppressed warning) to land cleanly, and no consumer has
  asked for strict modularity yet.
- **Partial modularization (core only).** Would sidestep writer's test-sourceset
  blocker, but reader/writer are exactly the modules whose FFM/zero-copy internals
  most benefit from encapsulation guarantees — modularizing only the least
  interesting module captures little of the value.
- **Abandon JPMS entirely, close #176, delete the TODO item without replacement.**
  Rejected: the low-adoption argument is about *downstream* JPMS adoption, not
  about whether the maintainer might still want it for `jlink`/internal reasons
  someday; parking costs nothing, closing forecloses the option.

## References

- [PR #176 — feat(jpms): add module-info for core/reader/writer \[WIP\]](https://github.com/dfa1/vortex-java/pull/176)
- [ADR 0017 — In-house FlatBuffers codegen + MemorySegment-native runtime](0017-in-house-flatbuffers-codegen.md)
- [ADR 0016 — vortex-arrow bridge](0016-vortex-arrow-bridge.md) — precedent for "capture now, defer until a concrete need"
- [ADR 0001 — Split read and write runtimes](0001-split-read-and-write-runtimes.md) — the writer-never-depends-on-reader rule PR #176 preserves and that its test sourceset struggles against
