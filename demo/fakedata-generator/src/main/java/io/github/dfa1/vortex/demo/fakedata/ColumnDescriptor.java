package io.github.dfa1.vortex.demo.fakedata;

import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;

/// One parsed column description: `name:type:generator(args)`.
///
/// @param name      the column's name
/// @param dtype     the column's declared, non-nullable logical type
/// @param generator the rule used to fill the column's values
public record ColumnDescriptor(ColumnName name, DType dtype, GeneratorSpec generator) {
}
