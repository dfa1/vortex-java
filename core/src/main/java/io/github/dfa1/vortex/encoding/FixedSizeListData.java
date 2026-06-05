package io.github.dfa1.vortex.encoding;

/// Input data for encoding a {@code vortex.fixed_size_list} column.
///
/// <p>{@code elements} is the flat array of inner values; its length must equal
/// {@code outerLen * fixedSize}. The element type is determined by the DType passed to
/// {@link FixedSizeListEncoding#encode}.
///
/// @param elements flat array of all inner values; length must equal {@code outerLen * fixedSize}
/// @param outerLen number of outer list elements
public record FixedSizeListData(Object elements, long outerLen) {
}
