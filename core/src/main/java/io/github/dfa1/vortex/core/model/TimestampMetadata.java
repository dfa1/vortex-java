package io.github.dfa1.vortex.core.model;

import java.time.ZoneId;
import java.util.Objects;

/// The metadata of `vortex.timestamp`, Rust's `TimestampOptions`: the [TimeUnit] its `I64` storage
/// counts in and an optional timezone.
///
/// @param unit the storage's time unit
/// @param zone the timezone, or `null` for none
public record TimestampMetadata(TimeUnit unit, ZoneId zone) implements ExtensionMetadata {

    /// Requires a unit.
    ///
    /// @param unit the storage's time unit
    /// @param zone the timezone, or `null` for none
    public TimestampMetadata {
        Objects.requireNonNull(unit, "unit");
    }
}
