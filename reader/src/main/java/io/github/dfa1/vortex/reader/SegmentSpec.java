package io.github.dfa1.vortex.reader;

/// Byte range and properties of one data segment in the file.
///
/// `length` is `long` because the wire format encodes it as
/// `uint32` (up to 4 GB per segment). Storing it as a Java `int`
/// silently truncates segments larger than 2 GB into negative values.
///
/// @param offset            byte offset from the start of the file
/// @param length            byte length of the segment (unsigned, stored as `long`)
/// @param alignmentExponent log2 of the required byte alignment; 0 means no alignment constraint
/// @param compression       compression scheme applied to this segment's bytes
public record SegmentSpec(
        long offset,
        long length,
        byte alignmentExponent,
        CompressionScheme compression
) {
}
