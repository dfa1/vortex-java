# ADR 0025: Centralize zone-map MIN/MAX stats computation in the writer

- **Status:** Accepted
- **Date:** 2026-09-13
- **Deciders:** project maintainer
- **Supersedes:** —
- **Superseded by:** —

## Context

Between #382 and #387, thirteen `EncodingEncoder` implementations (`AlpRd`, `Constant`, `RunEnd`,
`ZigZag`, `Sequence`, `Pco`, `Rle`, `Sparse`, `Patched`, `Zstd`, `Fsst`, `VarBinView`,
`Ext`'s cascade path, `DateTimeParts`) were found hardcoding `null, null` for zone-map `MIN`/`MAX`
stats in their `EncodeResult`/`CascadeStep`, regardless of input. Each bug was independent —
different encoder, different author intent, no shared cause beyond "an encoder is responsible for
computing and reporting its own stats, and this one didn't." Two things made this bug class
dangerous in this specific way:

1. **No test could catch it structurally.** The entire existing test suite (`RoundTripPropertyTest`,
   the Rust-interop integration tests) asserts decoded *values* are correct. Broken pruning changes
   nothing about what a scan returns — only how much it reads — so a test suite built around value
   correctness is blind to it by construction. The eventual fix
   (`ZoneMapStatsCoverageTest`) had to explicitly assert stats *presence* per encoder to close this,
   which is itself evidence the underlying design had no structural guarantee.
2. **It kept recurring.** Thirteen instances across two PRs is not "one encoder had a bug" — it is
   an architecture where the correct behavior is a convention every encoder must independently
   remember, with nothing enforcing it.

**How Rust avoids this.** The reference implementation (`spiraldb/vortex`) has no per-encoding
stats special-casing at all for most encodings. `StatsSet::compute_stat` computes `MIN`/`MAX` via
one generic aggregate reduction (`min_max`) over the array's canonical/decoded form — checked
directly against `encodings/datetime-parts/src/` (no stats code in `array.rs`, no aggregate kernel
in `compute/`) and `encodings/zigzag/src/array.rs`'s `test_compute_statistics`, which asserts a
zigzag-encoded array's computed stats equal the underlying array's. A few encodings (e.g. constant)
*do* provide a cheaper override, but the generic path is what makes stats a guarantee rather than a
convention — an encoding that provides no override still gets correct stats for free.

## Decision

Move the required computation to the one choke point every written segment already passes
through — `VortexWriter#writeSegment` (private) — instead of trusting each encoder's own
`EncodeResult`/`CascadeStep`:

```java
if (result.hasStats()) {
    lastStatsMin = result.statsMin();
    lastStatsMax = result.statsMax();
} else {
    byte[][] fallback = ZoneMapStatCodec.columnMinMax(dtype, data);
    lastStatsMin = fallback != null ? fallback[0] : null;
    lastStatsMax = fallback != null ? fallback[1] : null;
}
```

`ZoneMapStatCodec.columnMinMax(DType, Object)` is the new generic fallback, computed from the
segment's original `(dtype, data)` — untouched by whichever encoding wins — mirroring
`columnSum`, which already worked this way and, not coincidentally, never had this bug. It dispatches
on the same dtype shapes `zoneMinMaxDtype` already recognizes (`Primitive`, `Extension` with
`Primitive` storage, `Utf8`), compacting a nullable primitive column to its valid-only elements
first via `PrimitiveArrays#compact` (an invalid slot's placeholder, commonly `0`, is not a min/max
identity the way it is a sum identity — the #381 bug this generalizes) and skipping `null` entries
directly for a nullable Utf8 column, matching `VarBinEncodingEncoder#minMaxStats`'s own null-safe
loop.

This makes stats coverage an **encoder-independent guarantee**: an encoder MAY still report a
cheaper override when it can (kept for `Constant`/`Sequence`, whose extremes are O(1) known from
what they already validated, and `RunEnd`/`ZigZag`, whose stats are folded into a single-pass loop
they must run anyway), but never MUST. The nine other fixed encoders (`Pco`, `Rle`, `Sparse`,
`Patched`, `Zstd`, `Fsst`, `VarBinView`, `Ext`'s cascade path, `DateTimeParts`) had their explicit
stats computation *removed*, not kept: each was a second, unfused pass over the same input with no
efficiency benefit over the one generic pass the fallback already does.

**The `ComparableValues` escape hatch.** Most encoders receive a plain typed array
(`long[]`/`String[]`/...) as `data`, but `DateTimePartsData` is a carrier holding pre-split
day/second/subsecond values — its *comparable* form (the original combined timestamp) isn't its
literal shape. `ComparableValues` (`ptype()` + `values()`) lets such a carrier expose its
comparable form; `columnMinMax` checks for it before falling back to dtype-shape dispatch. This is
the write-side mirror of what Rust's own generic fallback does for the exact same encoding: Rust
has no `DateTimeParts`-specific stats code either — it decodes to canonical form
(`canonical.rs`'s `decode_to_temporal`, recombining `days * 86400 * divisor + seconds * divisor +
subseconds`) and runs the generic reduction over that. Java's writer never needs to decode to get
there; the pre-split values are already sitting in the carrier.

## Consequences

### Positive

- A future encoder that forgets to compute stats now gets them anyway, for free, correctly. The
  bug class this ADR responds to cannot recur for any dtype shape `columnMinMax` already handles.
- Nine encoders got simpler (explicit stats computation deleted), not more complex — the
  centralization was a net code reduction, not a new abstraction layered on top of the old one.
- `ZoneMapStatsCoverageTest`'s guarantee moved from "every encoder reports its own stats" (a
  per-encoder enumeration that grows forever and is easy to under-cover) to "`columnMinMax` handles
  every dtype shape it's supposed to" (`ZoneMapStatCodecTest`, a fixed, small surface: `Primitive`
  signed/unsigned, `Extension`+`Primitive` storage, `Extension`+`ComparableValues`, `Utf8`, each
  nullable, plus the excluded shapes — `Decimal`, `Bool`, `Binary`, structural types).

### Negative

- Two sources of truth for stats now exist in principle (an encoder's own override vs. the
  fallback), even though only four encoders use the override today. A reviewer adding stats logic
  to a new encoder must know the fallback exists and ask whether the override is actually cheaper
  before adding one — a `Sonar`/review-time judgment call, not something enforced by a type.
- `columnMinMax`'s dispatch (`Primitive`, `Extension`+`Primitive` storage, `Utf8`,
  `ComparableValues`) is a second place (alongside `zoneMinMaxDtype`) that must stay in sync with
  which dtype shapes are stats-eligible. They already agree today; nothing forces them to keep
  agreeing if `zoneMinMaxDtype` gains a new case later.

### Risks to manage

- If a future encoding needs a *cheaper-than-generic* override (mirroring `Constant`/`Sequence`),
  the reviewer must resist the temptation to skip it "since the fallback handles it anyway" when
  the fallback would in fact cost a real second pass over large data — the fallback removes the
  *correctness* risk, not the *performance* one.

## Alternatives considered

- **Keep per-encoder stats mandatory, close the gap with only a coverage test.** This is what
  #387 shipped first (`ZoneMapStatsCoverageTest` asserting every registry-selectable encoder has a
  case). It works, but only as long as every future PR remembers to add a case — a test that must
  itself be remembered is a weaker guarantee than a fallback that runs unconditionally. Superseded
  by this ADR once the central fallback existed to make the coverage test's job largely moot for
  new encoders.
- **Fully centralize: remove the override capability entirely, no encoder ever reports its own
  stats.** Rejected — `Constant`/`Sequence`'s O(1) shortcuts and `RunEnd`/`ZigZag`'s single-pass
  fusion are real, free efficiency wins the generic fallback cannot replicate (it would need a
  second full-array pass). Matches Rust's own shape: a required generic path plus optional
  cheaper overrides, not an all-or-nothing choice.
- **Have `columnMinMax` itself decode `DateTimePartsData` (or any future composite carrier) via
  its own split-specific logic**, instead of a `ComparableValues` marker interface. Rejected: it
  would make the generic writer-level function aware of one specific encoding's internal
  representation, coupling in the wrong direction. The marker interface lets the carrier own the
  knowledge of its own comparable form, matching how Rust's `canonical.rs` lives inside the
  `datetime-parts` encoding crate, not in the generic stats module.

## References

- [#382](https://github.com/dfa1/vortex-java/issues/382), [#384](https://github.com/dfa1/vortex-java/issues/384), [#385](https://github.com/dfa1/vortex-java/issues/385), [#386](https://github.com/dfa1/vortex-java/issues/386), [#387](https://github.com/dfa1/vortex-java/pull/387) — the thirteen encoders this generalizes.
- `spiraldb/vortex`: `vortex-array/src/stats/array.rs` (`StatsSet::compute_stat`, `min_max` generic reduction), `encodings/datetime-parts/src/canonical.rs` (`decode_to_temporal`), `encodings/zigzag/src/array.rs` (`test_compute_statistics`).
