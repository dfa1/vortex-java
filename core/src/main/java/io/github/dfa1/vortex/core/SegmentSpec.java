package io.github.dfa1.vortex.core;

/// Byte range and properties of one data segment in the file.
///
/// {@code length} is {@code long} because the wire format encodes it as
/// {@code uint32} (up to 4 GB per segment). Storing it as a Java {@code int}
/// silently truncates segments larger than 2 GB into negative values.
public record SegmentSpec(
		long offset,
		long length,
		byte alignmentExponent,
		CompressionScheme compression
) {
}
