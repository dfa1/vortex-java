package io.github.dfa1.vortex.inspect;

import java.util.Locale;

/// Formats a byte count as a short human-readable string using binary (1024-based) units.
public final class ByteSize {

    private static final long KIB = 1024L;
    private static final long MIB = KIB * KIB;

    private ByteSize() {
    }

    /// Formats `bytes` as `B` below 1 KiB, `KB` below 1 MiB, otherwise `MB`, with one decimal
    /// place for the KB/MB forms. Uses [Locale#ROOT] so the decimal separator is stable regardless
    /// of the default locale.
    ///
    /// @param bytes the byte count
    /// @return a human-readable size such as `512 B`, `1.5 KB`, or `2.0 MB`
    public static String format(long bytes) {
        if (bytes < KIB) {
            return bytes + " B";
        }
        if (bytes < MIB) {
            return String.format(Locale.ROOT, "%.1f KB", bytes / (double) KIB);
        }
        return String.format(Locale.ROOT, "%.1f MB", bytes / (double) MIB);
    }
}
