package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.proto.ProtoScalarValue;

/// A column's zone-map minimum and maximum, kept typed until the writer serializes them.
///
/// @param min the smallest value
/// @param max the largest value
// Generated ProtoScalarValue, qualified-exported by core: see EncodeResult.
@SuppressWarnings("exports")
public record MinMax(ProtoScalarValue min, ProtoScalarValue max) {
}
