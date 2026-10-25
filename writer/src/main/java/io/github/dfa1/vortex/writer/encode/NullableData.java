package io.github.dfa1.vortex.writer.encode;

/// Writer-side carrier for nullable column data: a packed values array paired with a
/// per-row validity bitmap.
///
/// `values` carries the raw storage in the shape an [EncodingEncoder] expects
/// (`int[]`, `long[]`, `byte[]`, `double[]`, ...). Null
/// positions hold zero-valued placeholders so primitive encoders can compress them
/// alongside real data. `validity` has the same logical length: `true`
/// for valid rows, `false` for nulls.
///
/// A nullable primitive column keeps its validity on the encoding that stores the values, as
/// Rust's arrays do (a `vortex.primitive` or `fastlanes.bitpacked` with a `vortex.bool` child);
/// other nullable columns are written as `vortex.masked`: a non-nullable child (the storage)
/// plus a Bool validity child. Readers reconstruct a `MaskedArray` — named as text, not linked,
/// because the writer module does not depend on the reader.
///
/// Invariant: `values` length (or storage row count) matches
/// `validity.length`; placeholders at null positions must be zero so
/// the validity child contains no semantic nulls.
///
/// @param values   packed values array; concrete type depends on the column dtype
/// @param validity per-row validity bitmap, parallel to `values`
@SuppressWarnings("java:S6218") // internal data carrier; record components are arrays of immutable primitives or refs that flow through pipelines without ever being compared.
public record NullableData(Object values, boolean[] validity) {

    /// Returns the number of rows.
    ///
    /// @return the validity length
    public int length() {
        return validity.length;
    }

    /// Returns the number of null rows.
    ///
    /// @return how many rows are invalid
    public int nullCount() {
        int valid = 0;
        for (boolean v : validity) {
            valid += v ? 1 : 0;
        }
        return validity.length - valid;
    }

    /// Returns the values of the valid rows, in row order: the array Rust's stats see, since
    /// they skip nulls (`vortex-compressor` `stats/integer.rs`).
    ///
    /// @return a new primitive array of the same type as `values`, one element per valid row
    public Object validValues() {
        int count = validity.length - nullCount();
        return switch (values) {
            case byte[] a -> {
                byte[] out = new byte[count];
                for (int i = 0, j = 0; j < count; i++) {
                    if (validity[i]) {
                        out[j++] = a[i];
                    }
                }
                yield out;
            }
            case short[] a -> {
                short[] out = new short[count];
                for (int i = 0, j = 0; j < count; i++) {
                    if (validity[i]) {
                        out[j++] = a[i];
                    }
                }
                yield out;
            }
            case int[] a -> {
                int[] out = new int[count];
                for (int i = 0, j = 0; j < count; i++) {
                    if (validity[i]) {
                        out[j++] = a[i];
                    }
                }
                yield out;
            }
            case long[] a -> {
                long[] out = new long[count];
                for (int i = 0, j = 0; j < count; i++) {
                    if (validity[i]) {
                        out[j++] = a[i];
                    }
                }
                yield out;
            }
            case float[] a -> {
                float[] out = new float[count];
                for (int i = 0, j = 0; j < count; i++) {
                    if (validity[i]) {
                        out[j++] = a[i];
                    }
                }
                yield out;
            }
            case double[] a -> {
                double[] out = new double[count];
                for (int i = 0, j = 0; j < count; i++) {
                    if (validity[i]) {
                        out[j++] = a[i];
                    }
                }
                yield out;
            }
            default -> throw new IllegalArgumentException("not a primitive array: " + values.getClass());
        };
    }
}
