package io.github.dfa1.vortex.core.array;

import io.github.dfa1.vortex.core.DType;

/// Zero-length [Array]. Has no buffers or children.
///
/// @param dtype the logical type of this empty array
public record EmptyArray(DType dtype) implements Array {

    /// Creates an empty array with the given logical type.
    ///
    /// @param dtype logical type for the returned empty array
    /// @return a zero-length {@link Array} with the specified dtype
    public static Array of(DType dtype) {
        return new EmptyArray(dtype);
    }

    @Override
    public long length() {
        return 0;
    }

}
