package io.github.dfa1.vortex.writer.encode;

/// Input data for encoding a {@code vortex.list} column.
///
/// <p>{@code elements} is the flat array of all inner values concatenated.
/// {@code offsets} has length {@code outerLen + 1}; {@code offsets[i]..offsets[i+1]}
/// is the range of elements for list {@code i}. {@code offsets[0]} must be 0.
///
/// @param elements flat array of all concatenated inner values
/// @param offsets  offset array of length {@code outerLen + 1}; {@code offsets[0]} must be 0
/// @param outerLen number of outer list elements
public record ListData(Object elements, long[] offsets, long outerLen) {
}
