package io.github.dfa1.vortex.writer.encode;

/// Input data for encoding a {@code vortex.listview} column.
///
/// {@code elements} is the flat array of all inner values (may be non-contiguous on disk).
/// {@code offsets[i]} is the start index of list {@code i} in elements.
/// {@code sizes[i]} is the number of elements in list {@code i}.
/// Both {@code offsets} and {@code sizes} have length {@code outerLen}.
///
/// @param elements flat array of all inner values (may be non-contiguous)
/// @param offsets  start index of each list in {@code elements}; length must equal {@code outerLen}
/// @param sizes    element count for each list; length must equal {@code outerLen}
/// @param outerLen number of outer list elements
@SuppressWarnings("java:S6218") // internal data carrier; record components are arrays of immutable primitives or refs that flow through pipelines without ever being compared.
public record ListViewData(Object elements, int[] offsets, int[] sizes, long outerLen) {
}
