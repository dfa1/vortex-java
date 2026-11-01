package io.github.dfa1.vortex.core.model;

import java.time.ZoneId;
import java.util.Objects;
import java.util.Optional;

/// The metadata of `vortex.timestamp`, Rust's `TimestampOptions`: the [TimeUnit] its `I64` storage
/// counts in and an optional timezone.
///
/// The timezone is the string the file stores, as Rust keeps it, not a [ZoneId]: a file opens
/// whatever zone it names, even one this JDK's zone database does not know, and writing it back
/// keeps the exact bytes (`ZoneId` normalizes, e.g. `+05` to `+05:00`). [#zone()] parses it.
///
/// @param unit     the storage's time unit
/// @param timezone the timezone, or `null` for none
public record TimestampMetadata(TimeUnit unit, String timezone) implements ExtensionMetadata {

    /// Requires a unit; an empty timezone is none, as on the wire.
    ///
    /// @param unit     the storage's time unit
    /// @param timezone the timezone, or `null` for none
    public TimestampMetadata {
        Objects.requireNonNull(unit, "unit");
        if (timezone != null && timezone.isEmpty()) {
            timezone = null;
        }
    }

    /// Metadata for `zone`, stored by its id.
    ///
    /// @param unit the storage's time unit
    /// @param zone the timezone, or `null` for none
    /// @return the metadata
    public static TimestampMetadata of(TimeUnit unit, ZoneId zone) {
        return new TimestampMetadata(unit, zone == null ? null : zone.getId());
    }

    /// The timezone as a [ZoneId].
    ///
    /// @return the zone, or empty when none is recorded
    /// @throws java.time.DateTimeException if the stored timezone is not a zone id this JDK knows
    public Optional<ZoneId> zone() {
        return Optional.ofNullable(timezone).map(ZoneId::of);
    }
}
