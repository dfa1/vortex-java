package io.github.dfa1.vortex.core;

/// Byte range and properties of one data segment in the file.
public record SegmentSpec(
    long              offset,
    int               length,
    byte              alignmentExponent,
    CompressionScheme compression
) {}
