package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.proto.Scalar;

/// Input data for encoding a constant `vortex.variant` column (Layer A).
///
/// Every row holds the same variant value `constantValue` — a typed inner scalar
/// wrapped as a variant, mirroring Rust `Scalar::variant(inner)`. The column is
/// physically stored as a single `vortex.constant` child (`core_storage`) under the
/// variant container, with no shredded child and no buffers beyond the constant scalar.
///
/// @param length        number of rows in the column
/// @param constantValue the inner typed scalar repeated on every row
public record VariantData(long length, Scalar constantValue) {

    /// Validates the constant variant input.
    public VariantData {
        if (length < 0) {
            throw new IllegalArgumentException("length must be non-negative, got " + length);
        }
        if (constantValue == null) {
            throw new IllegalArgumentException("constantValue must not be null");
        }
    }
}
