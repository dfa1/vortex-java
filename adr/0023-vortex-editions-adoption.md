# ADR 0023: Adopt the Vortex editions model as a client-side write/read policy

- **Status:** Accepted
- **Date:** 2026-07-26
- **Deciders:** project maintainer
- **Supersedes:** —
- **Superseded by:** —

## Context

Upstream Vortex (`vortex-data/vortex` [PR #8871](https://github.com/vortex-data/vortex/pull/8871))
formalized **editions**: frozen, named, additive sets of encoding IDs, each carrying a forever
read-compatibility guarantee. Editions come in independently-versioned **families** (`core`,
`unstable` today); a writer targets at most one edition per family and may emit any encoding in
the union of its enabled editions' cumulative members. Every `unstable`-family edition is a draft,
with no compatibility guarantee.

vortex-java already implements every encoding in every frozen `core` edition through
`core2026.07.0` (issue #301). This ADR is not about chasing new encodings — it is about adopting
the *editions model itself*: a frozen registry, a writer fail-fast guard ("never write a file a
conforming reader can't read"), and reader error UX naming which edition an unknown encoding
belongs to. vortex-java had no equivalent of any of this before.

**Ground-truth data.** The published spec
([`docs/specs/editions.md`](https://github.com/vortex-data/vortex/blob/develop/docs/specs/editions.md))
documents the concept but its own "Edition registry" section says "Coming soon" — the concrete
data is not public yet. The catalog in this codebase (`core.model.Editions`) was built directly
from the real Rust source at PR #8871 (`vortex-edition/src/lib.rs`,
`vortex/src/editions/{core,unstable}/*.rs`), not guessed at. Cross-checked against vortex-java's
own `EncodingId.WellKnown`: all 31 `core`-family encodings across the 4 frozen editions already
have a `WellKnown` constant; of the 4 draft `unstable` editions (9 total ids), only
`fastlanes.delta` and `vortex.patched` do. `EncodingId.parse(rawId)` already falls back to
`EncodingId.Custom` for the rest, so the catalog stores every id uniformly and mirrors upstream
faithfully rather than being truncated to what is implemented today.

## Decision

1. **Three types, not Rust's four-plus-a-session.** Rust's model (`EditionId`, `Edition`,
   `EditionInclusion`, `EditionDeclaration`, plus a mutable cross-crate `EditionSession`) exists
   because any Rust crate can register a declaration into the session. vortex-java's registries
   are explicit and closed (no `ServiceLoader` — the same design decision already made for
   `ReadRegistry`/`WriteRegistry`), so this collapses to three `core.model` types:
   - `EditionId(EditionFamily family, YearMonth cutMonth, int version)` — reuses
     `java.time.YearMonth` instead of two raw ints (validates the month range for free, and is
     `Comparable`, simplifying `isAtOrBefore`); `toString()` matches Rust's `Display` exactly
     (`"core2025.05.0"`, month zero-padded, version not); `isAtOrBefore(EditionId)` orders
     editions within a family (cross-family is always `false`).
   - `Edition(EditionId id, Set<EncodingId> added)` — folds Rust's separate
     `Edition`/`EditionDeclaration` into one type; `added` is only what joins *at this exact
     edition*, matching Rust's field exactly (not cumulative). Deliberately drops Rust's
     `min_vortex_version`: that field records the minimum *Rust* Vortex reader release a frozen
     `core` edition requires — it refers to a different implementation's own release train, has no
     relationship to vortex-java's versioning, and isn't meaningful in any vortex-java API.
     Populated with the real `EncodingId.WellKnown` constants wherever one exists ("strings at the
     boundary, types inside" — these come from a hardcoded catalog, not wire input, so there is no
     boundary to parse a string at), falling back to `EncodingId.Custom(rawId)` only for the
     handful of `unstable` ids with no `WellKnown` constant yet.
   - `Editions` — a final utility class holding the 8 catalog constants, `ALL` (declaration
     order), `cumulativeMembers(Edition)` (union of `added()` for every same-family edition at or
     before the given one, seeded with the argument's own `added()` first), and
     `owningEdition(EncodingId)` (first declaration in `ALL` whose `added()` contains the id —
     correct because membership is additive and each encoding belongs to exactly one family).

2. **`EditionFamily` is a closed enum (`CORE`, `UNSTABLE`), not a sealed
   interface with a `Custom` fallback** — unlike `EncodingId`/`LayoutId`, which do allow a
   `Custom` variant. A first pass modeled `EditionFamily` on that exact sealed-interface pattern
   (arguing families are "open-ended" the same way encoding ids are), but the two are not
   analogous: an encoding id's `Custom` variant has a real use case (a private company's own
   proprietary encoding, decoded by *their own* reader — no cross-implementation agreement
   needed). An edition **family** is inherently a cross-implementation compatibility promise
   ("any reader supporting `core2026.07.0` can read this file"); a private, single-writer family
   would carry none of that benefit, since no other reader in the ecosystem would recognize it.
   There is no legitimate use case for a custom family, so the type is closed. A caller who wants
   to write an out-of-edition encoding reaches it by explicitly enabling that encoding's own
   family via `WriteOptions#withEdition(Edition)` (e.g. `withEdition(Editions.UNSTABLE_2025_05_0)`)
   — not a fabricated family, and (per Decision #7) not a guard-wide opt-out either.
   - `Edition` itself still cannot be made construction-restricted the same way: Java forbids a
     record's canonical constructor from being *more* restrictive than the record type's own
     visibility, and `Edition` must stay `public` for `WriteOptions`'s public API
     (`withEdition(Edition)`). So "only `Editions`'s 8 catalog constants should exist" is
     documented API contract, not something the compiler enforces — a real Java-language
     limitation, not a design choice.

3. **`WriteOptions.editions` defaults to `{CORE: Editions.CORE_2026_07_0}`, not off.** Verified
   safe before defaulting it on: `VortexWriter.buildCascadeCodecs` (the candidate list every
   default write uses) does not include `DeltaEncodingEncoder`/`PatchedEncodingEncoder` — the
   only two `unstable`-family encoders vortex-java implements — so no default-config write can
   emit an `unstable` encoding through the ordinary cascade path. vortex-java also already
   implements 100% of `core`, unlike upstream Rust (which lags "by a few versions" to hedge
   against readers not having caught up to encodings *it* just shipped) — so "latest frozen
   `core`" carries no analogous risk here and avoids an arbitrary "how many versions to lag"
   choice. `withEdition(Edition)` replaces any edition already enabled for that edition's family
   (a writer may target at most one edition per family, but multiple families at once); see
   Decision #7 for why there is deliberately no guard-wide opt-out.

4. **The guard filters candidates during selection, not just checks after encoding
   completes — a mid-implementation course correction.** The original design only checked the
   final `EncodeNode` tree in `VortexWriter.registerEncodingIds` and threw on a violation. Before
   shipping that, tracing actual reachability surfaced a real gap: `fastlanes.delta` is a
   hardcoded candidate inside `SparseEncodingEncoder.INDEX_CASCADE_CANDIDATES`, used by
   `MaskedEncodingEncoder.encodeValidity` — the code path that compresses **the validity mask of
   any nullable column** — whenever `WriteOptions#allowedCascading() > 0` (i.e.
   `WriteOptions.cascading(depth)`, an ordinary, encouraged configuration, not just the explicit
   custom-encoder-list overload the earlier "safe by default" check had audited). A pure
   after-the-fact check would turn a case that works fine today (silently using
   `fastlanes.delta`) into a hard write failure the moment the default guard shipped — not a
   graceful degradation.

   The fix reuses a mechanism `CascadingCompressor` already had for an unrelated purpose:
   `EncodeContext.excluded()` is consulted at every one of its candidate-selection sites
   (`competeAndEncode`, `measureBestChild`, `findPrimitiveEncoding`), including inside nested
   competitions like the one above, since `ctx` threads all the way down. `VortexWriter`'s
   constructor now precomputes `editionExcluded` (the concrete encoder ids this writer holds that
   fall outside the configured edition(s)' cumulative union) once, and seeds it into every
   `EncodeContext` built in `writeSegment` via two new `EncodeContext.of(...)`/`ofDepth(...)`
   overloads taking an initial exclusion set. `CascadingCompressor` then simply never considers
   an out-of-edition candidate, falling back to the best remaining one — the guard becomes
   invisible in the common case instead of a crash.

   The original after-the-fact check in `registerEncodingIds` is kept as a **backstop**, not
   removed: it still fires for any selection path that does not consult
   `EncodeContext#excluded()` at all (e.g. a forced `encodingOverride` via the explicit
   custom-encoder-list overload with a single out-of-edition candidate, or
   `MaskedEncodingEncoder#encodeValidity`'s own top-level Sparse/RunEnd/Rle/Bool choice — though
   those three are all `core`-family, so this never fires there in practice). "Never silently
   write a file outside the declared edition" needs a guarantee that holds regardless of which
   selection path was taken, not just the ones that happen to filter gracefully.

   **A caveat surfaced while building the test for this**: empirically,
   `DeltaEncodingEncoder`'s current implementation stores its transposed bases and deltas at full
   `ptype` width with no further cascading into bit-packing, so it never actually wins a real
   size competition against raw `vortex.primitive` storage — the concrete regression this course
   correction defends against does not appear to be reachable with today's encoder
   implementations. The filtering design is still the right one regardless: it is not contingent
   on any particular encoder's current cost profile, and a future `unstable` encoder (or a future
   improvement to `DeltaEncodingEncoder` itself) could make this exact path live at any time
   without anyone revisiting the guard.

5. **Reader UX**: `ReadRegistry`'s two "no decoder registered" throw sites (`decode`,
   `decodeAsSegment`) now consult `Editions.owningEdition(id)` before throwing — naming the
   edition it joined (adding ", unstable — no compatibility guarantee" when that edition's family
   is `UNSTABLE`) when the id is known to some edition, or saying it is unknown to all editions and
   pointing at `allowUnknown()` otherwise. Both sites also switch to `VortexException`'s attributed
   `(EncodingId, String)` constructor (which prefixes the id), dropping the now-redundant id from
   the message body — a small, unrelated bug the same lines already had.

6. **No wire-format changes.** The targeted edition(s) are a write-time-only policy check —
   matches upstream's own model exactly ("stored on the writer's session," not a file field).
   Resolves the issue's own open question ("persist edition into file metadata, or write-time
   only?"): don't. No `.fbs`/`.proto` schema touched anywhere in this feature.

7. **Follow-up correction (post-merge PR review): drop `WriteOptions#withoutEditionGuard()`,
   no guard-wide escape hatch.** The version described in Decisions #2–#3 above shipped with a
   `withoutEditionGuard()` method that cleared every enabled edition, disabling the guard
   entirely — modeled loosely on upstream's "opt out entirely" case. Review on the merged PR
   flagged this as too broad: it lets a caller silently emit encodings from *any* family,
   including ones with no relationship to the edition the caller actually meant to enable, with no
   record of which one they intended. The fix removes the method outright; a caller who needs an
   `unstable`-family encoding now has exactly one path — `withEdition(Editions.UNSTABLE_2025_05_0)`
   (or whichever `unstable` edition covers the encoding) — which both enables the guard-compatible
   path and self-documents which unstable surface the caller opted into. All call sites that used
   `withoutEditionGuard()` to reach `fastlanes.delta`/`vortex.patched` in tests were updated to the
   corresponding `withEdition(Editions.UNSTABLE_...)` call. There is now no way to disable the
   guard globally, only to widen it deliberately, one family at a time.

## Consequences

### Positive

- A write can no longer silently emit an encoding outside its declared compatibility guarantee —
  the failure (or graceful fallback) happens at write time, in the writer's own process, instead
  of surfacing as a confusing read error in someone else's environment later.
- The default (`core2026.07.0`) is a zero-cost guardrail today: it changes no default write's
  output, verified by full reactor build + unit + integration test runs before and after.
- The reader's unknown-encoding error becomes actionable ("joined edition core2025.06.0") instead
  of a bare, unhelpful id string.
- `EncodeContext.excluded()`'s reuse for edition filtering required zero changes to
  `CascadingCompressor`, `SparseEncodingEncoder`, or `MaskedEncodingEncoder` — the mechanism
  already generalized correctly the moment it was seeded from a new source.

### Negative

- `WriteOptions` gaining an 8th record component required mechanical (behavior-preserving)
  updates at 29 direct-constructor call sites across 12 files, plus 9 existing test call sites
  that deliberately exercise `unstable`-family encoders through the explicit-encoder-list
  overload and needed `.withEdition(Editions.UNSTABLE_...)` added (Decision #7).
- Two guard mechanisms (graceful filtering + after-the-fact backstop) is more moving parts than a
  single check, and the split is only obvious from having traced the actual reachability gap —
  a future contributor extending the guard needs to understand both halves.

### Risks to manage

- The filtering half depends on every future nested candidate-selection path continuing to
  thread `EncodeContext` through and consult `excluded()`, the way `CascadingCompressor` already
  does everywhere. A future encoder that runs its own ad hoc cost competition without accepting
  or forwarding `ctx` would silently bypass the graceful-fallback half (though the backstop check
  in `registerEncodingIds` still catches the final result either way).
- `Edition`'s "only construct via `Editions`'s catalog constants" rule is documentation, not a
  compiler guarantee (see Decision #2) — a future reviewer must catch a hand-rolled `Edition`
  in review rather than relying on the type system.
- The catalog is a point-in-time mirror of upstream's frozen/draft editions; if upstream freezes
  a new `core` edition or adds `unstable` cohorts, this file needs a manual re-sync — there is no
  automated check that it stays current (deliberately: this repo does not own the upstream
  registry).

## Alternatives considered

- **After-the-fact check only, no filtering.** The original design. Rejected after tracing
  `MaskedEncodingEncoder`'s validity-mask cascade: it would turn an ordinary `cascading(depth)`
  write that happens to prefer an out-of-edition encoding for a validity mask into a hard
  failure, not a graceful degradation to the next-best in-edition candidate.
- **`EditionFamily` as a sealed interface with a `Custom` fallback**, mirroring
  `EncodingId`/`LayoutId`. Rejected: unlike a custom encoding id, a custom edition *family*
  carries no real cross-implementation compatibility guarantee — no other reader in the
  ecosystem would recognize it — so there is no legitimate use case for one, and the closed
  `core`/`unstable` enum is simpler and matches what is actually true.
- **Lag the default edition by a few versions**, mirroring upstream's own default. Rejected:
  upstream lags to hedge against its own reader ecosystem not having caught up to encodings it
  *just* shipped; vortex-java implements 100% of `core` already, so there is no analogous risk to
  hedge against, and "lag by how many versions" has no principled answer here.
- **Persist the targeted edition into the file footer.** Rejected: the actual compatibility
  guarantee is always re-derivable after the fact from the encoding ids already present in the
  footer plus the (client-side, shared) edition catalog — nothing new needs to be known that
  isn't already computable from existing file content, so there is nothing to gain from a new
  wire field, only a schema change to maintain.

## References

- Upstream PR: [vortex-data/vortex#8871](https://github.com/vortex-data/vortex/pull/8871)
- Editions spec: <https://github.com/vortex-data/vortex/blob/develop/docs/specs/editions.md>
- Issue: [#301](https://github.com/dfa1/vortex-java/issues/301)
