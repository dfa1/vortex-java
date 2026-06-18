package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.proto.Scalar;

import java.util.Collections;
import java.util.List;

/// Input data for encoding a `vortex.variant` column.
///
/// Holds one inner typed scalar per row, each wrapped as a variant value (mirroring
/// Rust `Scalar::variant(inner)`). The encoder coalesces adjacent equal values into
/// constant runs: an all-equal column becomes a single `vortex.constant` child, while
/// a column with varying values becomes a `vortex.chunked` of per-run constants. There
/// is no shredded child.
///
/// @param values one inner scalar per row, in row order
public record VariantData(List<Scalar> values) {

    /// Validates and defensively copies the per-row values. Rejects empty input and
    /// `null` elements.
    public VariantData {
        values = List.copyOf(values);
        if (values.isEmpty()) {
            throw new IllegalArgumentException("values must not be empty");
        }
    }

    /// Creates input for a constant variant column: `length` rows all holding `value`.
    ///
    /// @param length number of rows; must be positive
    /// @param value  the inner scalar repeated on every row
    /// @return variant input describing a constant column
    public static VariantData constant(int length, Scalar value) {
        if (length <= 0) {
            throw new IllegalArgumentException("length must be positive, got " + length);
        }
        if (value == null) {
            throw new IllegalArgumentException("value must not be null");
        }
        return new VariantData(Collections.nCopies(length, value));
    }

    /// Returns the number of rows in the column.
    ///
    /// @return row count
    public long length() {
        return values.size();
    }
}
