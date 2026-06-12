package io.github.dfa1.vortex.writer.encode;

import java.util.List;

/// Input data for encoding a struct-typed column.
/// {@code fieldArrays} is parallel to {@link io.github.dfa1.vortex.core.DType.Struct#fieldTypes()}.
///
/// @param fieldArrays per-field data arrays in the same order as the struct's field types
public record StructData(List<Object> fieldArrays) {
    /// Validates and defensively copies the field arrays list.
    public StructData {
        fieldArrays = List.copyOf(fieldArrays);
    }
}
