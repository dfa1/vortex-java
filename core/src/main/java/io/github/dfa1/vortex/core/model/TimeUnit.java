package io.github.dfa1.vortex.core.model;

import io.github.dfa1.vortex.core.error.VortexException;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/// Time unit for timestamp values. Ordinals match Rust's `TimeUnit` enum.
public enum TimeUnit {
    /// Nanoseconds — ordinal 0.
    Nanoseconds,
    /// Microseconds — ordinal 1.
    Microseconds,
    /// Milliseconds — ordinal 2.
    Milliseconds,
    /// Seconds — ordinal 3.
    Seconds,
    /// Days — ordinal 4. Not sub-dividable; [#divisor()] throws for this value.
    Days;

    /// Returns the `TimeUnit` for the given wire tag byte.
    ///
    /// @param tag wire tag byte (unsigned ordinal)
    /// @return the corresponding `TimeUnit`
    public static TimeUnit fromTag(byte tag) {
        int i = Byte.toUnsignedInt(tag);
        return switch (i) {
            case 0 -> Nanoseconds;
            case 1 -> Microseconds;
            case 2 -> Milliseconds;
            case 3 -> Seconds;
            case 4 -> Days;
            default -> throw new IllegalArgumentException("unknown TimeUnit tag: " + i);
        };
    }

    /// The unit a time or timestamp extension records in its metadata's first byte.
    ///
    /// @throws VortexException if the metadata is missing or empty
    static TimeUnit ofExtension(DType.Extension ext) {
        MemorySegment meta = ext.metadata();
        if (meta == null || meta.byteSize() == 0) {
            throw new VortexException("missing TimeUnit metadata byte for " + ext.extensionId());
        }
        return fromTag(meta.get(ValueLayout.JAVA_BYTE, 0));
    }

    /// Returns the number of this time unit's divisions in one second (e.g. 1_000_000_000 for nanoseconds).
    /// Throws [IllegalArgumentException] for [#Days].
    ///
    /// @return divisor for converting between this unit and seconds
    public long divisor() {
        return switch (this) {
            case Nanoseconds -> 1_000_000_000L;
            case Microseconds -> 1_000_000L;
            case Milliseconds -> 1_000L;
            case Seconds -> 1L;
            case Days -> throw new IllegalArgumentException("Days cannot be split");
        };
    }
}
