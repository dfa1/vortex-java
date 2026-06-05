package io.github.dfa1.vortex.core;

/// Byte range and properties of one data segment in the file.
///
/// {@code length} is {@code long} because the wire format encodes it as
/// {@code uint32} (up to 4 GB per segment). Storing it as a Java {@code int}
/// silently truncates segments larger than 2 GB into negative values.
///
/// @param offset             byte offset from the start of the file
/// @param length             byte length of the segment (unsigned, stored as {@code long})
/// @param alignmentExponent  log2 of the required byte alignment; 0 means no alignment constraint
/// @param compression        compression scheme applied to this segment's bytes
public record SegmentSpec(
		long offset,
		long length,
		byte alignmentExponent,
		CompressionScheme compression
) {
}
