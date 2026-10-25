package io.github.dfa1.vortex.inspect;

import java.util.List;
import java.util.Locale;

/// Formats a byte count as a short human-readable string using binary (1024-based) units.
public final class ByteSize {

    /// Every unit a `long` can reach: its maximum is just under 8 EiB, so no byte count runs past
    /// the end of the list.
    private static final List<String> UNITS = List.of("KB", "MB", "GB", "TB", "PB", "EB");

    private ByteSize() {
    }

    /// Formats `bytes` as `B` below 1 KiB, else in the largest unit (`KB` up to `EB`) it is at least
    /// one of, with one decimal place. Uses [Locale#ROOT] so the decimal separator is stable
    /// regardless of the default locale.
    ///
    /// @param bytes the byte count
    /// @return a human-readable size such as `512 B`, `1.5 KB`, `2.0 MB` or `2.1 GB`
    public static String format(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        // Each unit is 10 more bits: the highest set bit picks the unit, with no table of bounds.
        int unit = (Long.SIZE - 1 - Long.numberOfLeadingZeros(bytes)) / 10;
        return String.format(Locale.ROOT, "%.1f %s", bytes / (double) (1L << (10 * unit)), UNITS.get(unit - 1));
    }
}
