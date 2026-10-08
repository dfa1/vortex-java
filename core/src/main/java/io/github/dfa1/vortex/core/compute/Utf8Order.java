package io.github.dfa1.vortex.core.compute;

/// The string order Vortex uses everywhere: by UTF-8 bytes, which is code-point order.
///
/// Rust compares strings by their bytes, so its zone-map and array min/max are in this order.
/// [String#compareTo] instead compares UTF-16 code units, which disagrees for supplementary
/// characters: U+10000 is a surrogate pair below U+E000 in UTF-16 but sorts above it in UTF-8.
/// Mixing the two orders between the writer's stats and the reader's predicates prunes chunks that
/// hold matching rows.
public final class Utf8Order {

    private Utf8Order() {
    }

    /// Compares two strings in UTF-8 byte (code-point) order.
    ///
    /// @param a the first string
    /// @param b the second string
    /// @return a negative, zero, or positive integer as `a` sorts before, equal to, or after `b`
    public static int compare(String a, String b) {
        int n = Math.min(a.length(), b.length());
        for (int i = 0; i < n; i++) {
            char ca = a.charAt(i);
            char cb = b.charAt(i);
            if (ca != cb) {
                // The first differing unit decides; reading the code point there puts a surrogate
                // pair above every BMP character, as its 4-byte UTF-8 form sorts.
                return Integer.compare(a.codePointAt(i), b.codePointAt(i));
            }
        }
        return Integer.compare(a.length(), b.length());
    }
}
