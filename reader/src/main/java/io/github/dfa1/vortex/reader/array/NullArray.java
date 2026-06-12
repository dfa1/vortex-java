package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.DType;

/// Concrete [Array] for all-null columns ({@code DType.Null}). Holds only a row count.
///
/// @param dtype  the null dtype
/// @param length number of null elements
public record NullArray(DType dtype, long length) implements Array {


}
