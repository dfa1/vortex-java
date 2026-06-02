package io.github.dfa1.vortex.encoding;

/// Time unit for timestamp values. Ordinals match Rust's {@code TimeUnit} enum.
public enum TimeUnit {
    Nanoseconds,   // 0
    Microseconds,  // 1
    Milliseconds,  // 2
    Seconds,       // 3
    Days;          // 4

    public static TimeUnit fromTag(byte tag) {
        int i = Byte.toUnsignedInt(tag);
        TimeUnit[] values = values();
        if (i >= values.length) {
            throw new IllegalArgumentException("unknown TimeUnit tag: " + i);
        }
        return values[i];
    }

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
