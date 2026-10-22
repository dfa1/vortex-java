package io.github.dfa1.vortex.core.compute;

/// Checks that bytes are well-formed UTF-8, as a Utf8 array's bytes must be.
///
/// Rust validates a Utf8 array's bytes when it builds the array, so a writer that takes UTF-8
/// bytes from its caller must reject what Rust would. Well-formed is RFC 3629: no overlong forms,
/// no surrogates (U+D800..U+DFFF), nothing above U+10FFFF.
public final class Utf8Validation {

    private Utf8Validation() {
    }

    /// Whether `bytes[from, to)` is well-formed UTF-8.
    ///
    /// @param bytes the bytes
    /// @param from  the first byte, inclusive
    /// @param to    the last byte, exclusive
    /// @return `true` if the range is well-formed UTF-8
    public static boolean isWellFormed(byte[] bytes, int from, int to) {
        int i = from;
        while (i < to) {
            int lead = bytes[i] & 0xFF;
            if (lead < 0x80) {
                i++;
                continue;
            }
            int length;
            if (lead >= 0xC2 && lead <= 0xDF) {
                length = 2;
            } else if (lead >= 0xE0 && lead <= 0xEF) {
                length = 3;
            } else if (lead >= 0xF0 && lead <= 0xF4) {
                length = 4;
            } else {
                return false;
            }
            if (to - i < length || !secondByteFits(lead, bytes[i + 1] & 0xFF)) {
                return false;
            }
            for (int k = 2; k < length; k++) {
                if ((bytes[i + k] & 0xC0) != 0x80) {
                    return false;
                }
            }
            i += length;
        }
        return true;
    }

    /// The second byte's range narrows after the leads that would otherwise allow an overlong
    /// form (E0, F0), a surrogate (ED) or a code point above U+10FFFF (F4): RFC 3629 table 3.7.
    private static boolean secondByteFits(int lead, int second) {
        return switch (lead) {
            case 0xE0 -> second >= 0xA0 && second <= 0xBF;
            case 0xED -> second >= 0x80 && second <= 0x9F;
            case 0xF0 -> second >= 0x90 && second <= 0xBF;
            case 0xF4 -> second >= 0x80 && second <= 0x8F;
            default -> second >= 0x80 && second <= 0xBF;
        };
    }
}
