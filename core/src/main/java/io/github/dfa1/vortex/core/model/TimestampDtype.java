package io.github.dfa1.vortex.core.model;

import io.github.dfa1.vortex.core.error.VortexException;

import java.time.ZoneId;
import java.util.Optional;

/// Static factories and metadata accessors for `vortex.timestamp` extension dtypes, whose metadata
/// is a [TimestampMetadata].
public final class TimestampDtype {

    private TimestampDtype() {
    }

    /// Default dtype: milliseconds resolution, no timezone.
    ///
    /// @param nullable whether the column allows nulls
    /// @return matching extension dtype
    public static DType.Extension of(boolean nullable) {
        return of(TimeUnit.Milliseconds, null, nullable);
    }

    /// Returns the extension dtype for the given unit and timezone.
    ///
    /// @param unit     time resolution
    /// @param zone     IANA timezone, or `null` for none
    /// @param nullable whether the column allows nulls
    /// @return matching extension dtype
    public static DType.Extension of(TimeUnit unit, ZoneId zone, boolean nullable) {
        return new DType.Extension(
                ExtensionId.VORTEX_TIMESTAMP.id(),
                new DType.Primitive(PType.I64, nullable),
                new TimestampMetadata(unit, zone),
                nullable);
    }

    /// Returns the [TimeUnit] the dtype's metadata records.
    ///
    /// @param ext declared extension dtype
    /// @return the recorded time unit
    /// @throws VortexException if the metadata is not a [TimestampMetadata]
    public static TimeUnit readUnit(DType.Extension ext) {
        return metadata(ext).unit();
    }

    /// Returns the timezone the dtype's metadata records.
    ///
    /// @param ext declared extension dtype
    /// @return parsed zone id, or empty when no timezone is recorded
    /// @throws VortexException if the metadata is not a [TimestampMetadata]
    public static Optional<ZoneId> timezone(DType.Extension ext) {
        return Optional.ofNullable(metadata(ext).zone());
    }

    private static TimestampMetadata metadata(DType.Extension ext) {
        if (!(ext.metadata() instanceof TimestampMetadata metadata)) {
            throw new VortexException("missing timestamp metadata for " + ext.extensionId());
        }
        return metadata;
    }
}
