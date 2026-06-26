package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.proto.ProtoScalar;

import java.util.Collections;
import java.util.List;

/// Input data for encoding a `vortex.variant` column.
///
/// `values` holds one inner typed scalar per row, each wrapped as a variant value
/// (mirroring Rust `ProtoScalar::variant(inner)`). The encoder coalesces adjacent equal
/// values into constant runs: an all-equal column becomes a single `vortex.constant`
/// child, while a varying column becomes a `vortex.chunked` of per-run constants.
///
/// Optionally, `shreddedData` carries a row-aligned typed column pulled out for fast
/// typed access; when present it is encoded as the container's `shredded` child and its
/// `shreddedDtype` is recorded in `VariantMetadata.shredded_dtype`. `shreddedData` uses
/// the same encoder-input shape as the matching encoding (e.g. `int[]` for I32,
/// `String[]` for Utf8). Both `shreddedData` and `shreddedDtype` must be set together or
/// both left `null`.
///
/// @param values       one inner scalar per row, in row order
/// @param shreddedData  optional typed column data for the shredded child, or `null`
/// @param shreddedDtype dtype of `shreddedData`, or `null`
// Variant authoring takes inner scalars as the generated ProtoScalar (mirroring Rust), which core
// only qualified-exports. Suppress the exports-leak lint rather than widen the core proto export
// (which would publish the whole generated codec) or invent a parallel public scalar type.
@SuppressWarnings("exports")
public record VariantData(List<ProtoScalar> values, Object shreddedData, DType shreddedDtype) {

    /// Validates and defensively copies the per-row values; rejects empty input and a
    /// half-specified shredded column.
    public VariantData {
        values = List.copyOf(values);
        if (values.isEmpty()) {
            throw new IllegalArgumentException("values must not be empty");
        }
        if ((shreddedData == null) != (shreddedDtype == null)) {
            throw new IllegalArgumentException("shreddedData and shreddedDtype must be both set or both null");
        }
    }

    /// Creates unshredded input from per-row scalars.
    ///
    /// @param values one inner scalar per row, in row order
    public VariantData(List<ProtoScalar> values) {
        this(values, null, null);
    }

    /// Creates input for a constant variant column: `length` rows all holding `value`.
    ///
    /// @param length number of rows; must be positive
    /// @param value  the inner scalar repeated on every row
    /// @return variant input describing a constant column
    public static VariantData constant(int length, ProtoScalar value) {
        if (length <= 0) {
            throw new IllegalArgumentException("length must be positive, got " + length);
        }
        if (value == null) {
            throw new IllegalArgumentException("value must not be null");
        }
        return new VariantData(Collections.nCopies(length, value));
    }

    /// Creates input with a row-aligned shredded typed column.
    ///
    /// @param values        one inner scalar per row, in row order
    /// @param shreddedData  typed column data (encoder-input shape for `shreddedDtype`)
    /// @param shreddedDtype dtype of `shreddedData`
    /// @return variant input carrying a shredded child
    public static VariantData shredded(List<ProtoScalar> values, Object shreddedData, DType shreddedDtype) {
        if (shreddedData == null || shreddedDtype == null) {
            throw new IllegalArgumentException("shreddedData and shreddedDtype are required");
        }
        return new VariantData(values, shreddedData, shreddedDtype);
    }

    /// Returns the number of rows in the column.
    ///
    /// @return row count
    public long length() {
        return values.size();
    }
}
