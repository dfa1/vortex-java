package io.github.dfa1.vortex.writer;

/// Typed chunk builder for {@link VortexWriter#writeChunk(java.util.function.Consumer)}.
///
/// Validates each `put` against the writer's schema:
/// - Column name must exist in the schema.
/// - Array type must match the column [DType].
/// - Non-nullable columns reject boxed arrays containing {@code null}.
///
/// All schema columns must be supplied before the consumer returns; all column
/// arrays must share the same length. The writer enforces both at lambda close.
///
/// Type mapping:
///
/// | DType | Non-nullable array | Nullable array |
/// |---|---|---|
/// | Primitive(I8/U8) | {@code byte[]} | {@code Byte[]} |
/// | Primitive(I16/U16) | {@code short[]} | {@code Short[]} |
/// | Primitive(I32/U32) | {@code int[]} | {@code Integer[]} |
/// | Primitive(I64/U64) | {@code long[]} | {@code Long[]} |
/// | Primitive(F32) | {@code float[]} | {@code Float[]} |
/// | Primitive(F64) | {@code double[]} | {@code Double[]} |
/// | Utf8 | {@code String[]} | {@code String[]} (nulls allowed) |
/// | Bool | {@code boolean[]} | {@code Boolean[]} |
public interface Chunk {

    /// Adds a column's data to the chunk.
    ///
    /// @param column the column name; must exist in the writer's schema
    /// @param data   the column data; type must match the schema (see class javadoc)
    /// @return this builder
    /// @throws IllegalArgumentException if {@code column} is not in the schema or
    ///         {@code data} is of the wrong type for the column
    Chunk put(String column, Object data);
}
