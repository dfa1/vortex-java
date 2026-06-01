package io.github.dfa1.vortex.encoding;

import java.util.List;

/// Input data for encoding a struct-typed column.
/// {@code fieldArrays} is parallel to {@link io.github.dfa1.vortex.core.DType.Struct#fieldTypes()}.
public record StructData(List<Object> fieldArrays) {
    public StructData {
        fieldArrays = List.copyOf(fieldArrays);
    }
}
