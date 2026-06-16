package io.github.dfa1.vortex.reader;

/// Block-level compression scheme applied to flat segments in the Vortex file format.
public enum CompressionScheme {
    /// No compression.
    NONE(0),
    /// LZ4 block compression.
    LZ4(1),
    /// Zlib deflate compression.
    ZLIB(2),
    /// Zstandard compression.
    ZSTD(3);

    /// Numeric code as stored in the file postscript.
    public final int code;

    CompressionScheme(int code) {
        this.code = code;
    }

    /// Returns the enum constant matching the given numeric code.
    ///
    /// @param code numeric compression code from the file postscript
    /// @return the matching {@link CompressionScheme}
    /// @throws IllegalArgumentException if `code` does not correspond to any known scheme
    public static CompressionScheme of(int code) {
        return switch (code) {
            case 0 -> NONE;
            case 1 -> LZ4;
            case 2 -> ZLIB;
            case 3 -> ZSTD;
            default -> throw new IllegalArgumentException("unknown compression code: " + code);
        };
    }
}
