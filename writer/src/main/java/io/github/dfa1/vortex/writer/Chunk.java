package io.github.dfa1.vortex.writer;

/// Typed chunk builder for {@link VortexWriter#writeChunk(java.util.function.Consumer)}.
///
/// Validates each `put` against the writer's schema:
/// <ul>
///   <li>Column name must exist in the schema.</li>
///   <li>Array type must match the column [DType].</li>
///   <li>Non-nullable columns reject boxed arrays containing {@code null}.</li>
/// </ul>
///
/// All schema columns must be supplied before the consumer returns; all column
/// arrays must share the same length. The writer enforces both at lambda close.
///
/// Type mapping:
///
/// <table>
///   <tr><th>DType</th><th>Non-nullable array</th><th>Nullable array</th></tr>
///   <tr><td>Primitive(I8/U8)</td><td>{@code byte[]}</td><td>{@code Byte[]}</td></tr>
///   <tr><td>Primitive(I16/U16)</td><td>{@code short[]}</td><td>{@code Short[]}</td></tr>
///   <tr><td>Primitive(I32/U32)</td><td>{@code int[]}</td><td>{@code Integer[]}</td></tr>
///   <tr><td>Primitive(I64/U64)</td><td>{@code long[]}</td><td>{@code Long[]}</td></tr>
///   <tr><td>Primitive(F32)</td><td>{@code float[]}</td><td>{@code Float[]}</td></tr>
///   <tr><td>Primitive(F64)</td><td>{@code double[]}</td><td>{@code Double[]}</td></tr>
///   <tr><td>Utf8</td><td>{@code String[]}</td><td>{@code String[]} (nulls allowed)</td></tr>
///   <tr><td>Bool</td><td>{@code boolean[]}</td><td>{@code Boolean[]}</td></tr>
/// </table>
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
