package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.model.PType;

/// Implemented by a write-side data carrier whose logical comparable value differs from the
/// literal `Object data` an [EncodingEncoder] receives — e.g. [DateTimePartsData] splits a
/// timestamp into day/second/subsecond parts, so its comparable form for zone-map min/max is the
/// original combined timestamp, not the carrier itself.
///
/// Mirrors how the Rust reference computes stats for `vortex.datetimeparts`: it has no
/// per-encoding stats override at all, so a generic min/max reduction runs after
/// `canonical.rs`'s `decode_to_temporal` recombines the parts back into a plain `i64` array —
/// the exact same `day * ticksPerDay + seconds * divisor + subseconds` arithmetic this
/// encoding's own writer already does in reverse. On the write side there's no need to
/// decode anything to get there: the pre-split values are already sitting in the carrier.
///
/// `ZoneMapStatCodec#columnMinMax` (writer package) checks for this before falling back to
/// dtype-shape dispatch.
public interface ComparableValues {

    /// The primitive type of {@link #values()}.
    ///
    /// @return the comparable primitive type
    PType ptype();

    /// The original, pre-transform primitive array (`long[]`, `int[]`, ...) suitable for
    /// [PrimitiveEncodingEncoder#minMaxStats].
    ///
    /// @return the comparable values, in `ptype()`'s array shape
    Object values();
}
