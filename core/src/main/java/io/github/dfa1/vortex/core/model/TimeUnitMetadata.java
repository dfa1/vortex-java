package io.github.dfa1.vortex.core.model;

import java.util.Objects;

/// The metadata of `vortex.date` and `vortex.time`: the [TimeUnit] their storage counts in.
///
/// @param unit the storage's time unit
public record TimeUnitMetadata(TimeUnit unit) implements ExtensionMetadata {

    /// Requires a unit.
    ///
    /// @param unit the storage's time unit
    public TimeUnitMetadata {
        Objects.requireNonNull(unit, "unit");
    }
}
