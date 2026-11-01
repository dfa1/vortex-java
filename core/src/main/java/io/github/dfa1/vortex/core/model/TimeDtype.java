package io.github.dfa1.vortex.core.model;

import io.github.dfa1.vortex.core.error.VortexException;

/// Static factories and metadata accessors for `vortex.time` extension dtypes.
///
/// The metadata is a [TimeUnitMetadata]. Storage width is derived from the unit (I32 for s/ms, I64
/// for μs/ns).
public final class TimeDtype {

    private TimeDtype() {
    }

    /// Default dtype: milliseconds resolution over I32 storage.
    ///
    /// @param nullable whether the column allows nulls
    /// @return matching extension dtype
    public static DType.Extension of(boolean nullable) {
        return of(TimeUnit.Milliseconds, nullable);
    }

    /// Returns the extension dtype for the given [TimeUnit].
    ///
    /// @param unit     time resolution; controls storage width (I32 for s/ms, I64 for μs/ns)
    /// @param nullable whether the column allows nulls
    /// @return matching extension dtype
    /// @throws IllegalArgumentException if `unit` is [TimeUnit#Days]
    public static DType.Extension of(TimeUnit unit, boolean nullable) {
        PType storage = switch (unit) {
            case Seconds, Milliseconds -> PType.I32;
            case Microseconds, Nanoseconds -> PType.I64;
            case Days -> throw new IllegalArgumentException("Days unit not valid for vortex.time");
        };
        return new DType.Extension(
                ExtensionId.VORTEX_TIME,
                new DType.Primitive(storage, nullable),
                new TimeUnitMetadata(unit),
                nullable);
    }

    /// Returns the [TimeUnit] the dtype's metadata records.
    ///
    /// @param ext declared extension dtype
    /// @return the recorded time unit
    /// @throws VortexException if the metadata is not a [TimeUnitMetadata]
    public static TimeUnit readUnit(DType.Extension ext) {
        if (!(ext.metadata() instanceof TimeUnitMetadata(TimeUnit unit))) {
            throw new VortexException("missing TimeUnit metadata for " + ext.extensionId());
        }
        return unit;
    }
}
