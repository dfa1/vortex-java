package io.github.dfa1.vortex.core.model;


import java.lang.foreign.MemorySegment;

/// Static factories and metadata accessors for `vortex.time` extension dtypes.
///
/// Wire format: a single byte holding the [TimeUnit] tag. Storage width is
/// derived from the unit (I32 for s/ms, I64 for μs/ns).
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
        MemorySegment meta = MemorySegment.ofArray(new byte[]{(byte) unit.ordinal()});
        return new DType.Extension(
                ExtensionId.VORTEX_TIME.id(),
                new DType.Primitive(storage, nullable),
                meta,
                nullable);
    }

    /// Reads the [TimeUnit] tag from the metadata's first byte.
    ///
    /// @param ext declared extension dtype
    /// @return the recorded time unit
    /// @throws io.github.dfa1.vortex.core.error.VortexException if metadata is missing or empty
    public static TimeUnit readUnit(DType.Extension ext) {
        return TimeUnit.ofExtension(ext);
    }
}
