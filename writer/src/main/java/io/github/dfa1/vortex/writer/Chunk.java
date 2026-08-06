package io.github.dfa1.vortex.writer;

import io.github.dfa1.vortex.core.model.ColumnName;

/// Typed chunk builder for [VortexWriter#writeChunk(java.util.function.Consumer)].
///
/// Validates each `put` against the writer's schema:
/// - Column name must exist in the schema.
/// - Array type must match the column [io.github.dfa1.vortex.core.model.DType].
/// - Non-nullable columns reject boxed arrays containing `null`.
///
/// All schema columns must be supplied before the consumer returns; all column
/// arrays must share the same length. The writer enforces both at lambda close.
///
/// Type mapping:
///
/// | DType | Non-nullable array | Nullable array |
/// |---|---|---|
/// | Primitive(I8/U8) | `byte[]` | `Byte[]` |
/// | Primitive(I16/U16) | `short[]` | `Short[]` |
/// | Primitive(I32/U32) | `int[]` | `Integer[]` |
/// | Primitive(I64/U64) | `long[]` | `Long[]` |
/// | Primitive(F32) | `float[]` | `Float[]` |
/// | Primitive(F64) | `double[]` | `Double[]` |
/// | Utf8 | `String[]` | `String[]` (nulls allowed) |
/// | Bool | `boolean[]` | `Boolean[]` |
public interface Chunk {

    /// Adds a column's data to the chunk, addressing it by its validated [ColumnName].
    ///
    /// @param column the column name; must exist in the writer's schema
    /// @param data   the column data; type must match the schema (see class javadoc)
    /// @return this builder
    /// @throws IllegalArgumentException if `column` is not in the schema or
    ///         `data` is of the wrong type for the column
    Chunk put(ColumnName column, Object data);
}
