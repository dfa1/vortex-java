package io.github.dfa1.vortex.writer.encode;

/// Input data for encoding a `vortex.listview` column.
///
/// `elements` is the flat array of all inner values (may be non-contiguous on disk).
/// `offsets[i]` is the start index of list `i` in elements.
/// `sizes[i]` is the number of elements in list `i`.
/// Both `offsets` and `sizes` have length `outerLen`.
///
/// @param elements flat array of all inner values (may be non-contiguous)
/// @param offsets  start index of each list in `elements`; length must equal `outerLen`
/// @param sizes    element count for each list; length must equal `outerLen`
/// @param outerLen number of outer list elements
@SuppressWarnings("java:S6218") // internal data carrier; record components are arrays of immutable primitives or refs that flow through pipelines without ever being compared.
public record ListViewData(Object elements, int[] offsets, int[] sizes, long outerLen) {
}
