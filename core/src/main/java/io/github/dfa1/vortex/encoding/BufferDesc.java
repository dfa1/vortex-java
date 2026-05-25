package io.github.dfa1.vortex.encoding;

/// Describes one data buffer within an array segment.
public record BufferDesc(
    short padding,
    byte  alignmentExponent,
    byte  compression,
    int   length
) {
    public static final byte COMPRESSION_NONE = 0;
    public static final byte COMPRESSION_LZ4  = 1;
}
