package io.github.dfa1.vortex.writer.encode;

import java.util.List;

/// Input data for encoding a struct-typed column.
/// `fieldArrays` is parallel to [io.github.dfa1.vortex.core.model.DType.Struct#fieldTypes()].
///
/// @param fieldArrays per-field data arrays in the same order as the struct's field types
public record StructData(List<Object> fieldArrays) {
    /// Validates and defensively copies the field arrays list.
    public StructData {
        fieldArrays = List.copyOf(fieldArrays);
    }
}
