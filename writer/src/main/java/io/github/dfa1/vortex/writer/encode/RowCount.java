package io.github.dfa1.vortex.writer.encode;

import java.math.BigDecimal;

/// The row count of a column carrier the writer accepts: primitive and object arrays, plus the
/// structured carriers of this package ([NullableData], [StructData], [ListData], …).
public final class RowCount {

    private RowCount() {
    }

    /// The row count of a column carrier. Every carrier is backed by Java arrays, so it is an `int`;
    /// a carrier declaring a longer length is rejected rather than truncated.
    ///
    /// @param data a column carrier
    /// @return its number of rows
    /// @throws UnsupportedOperationException if `data` is not a carrier the writer accepts
    /// @throws ArithmeticException if `data` declares more than `Integer.MAX_VALUE` rows
    public static int of(Object data) {
        return switch (data) {
            case byte[] a -> a.length;
            case short[] a -> a.length;
            case int[] a -> a.length;
            case long[] a -> a.length;
            case float[] a -> a.length;
            case double[] a -> a.length;
            case boolean[] a -> a.length;
            case String[] a -> a.length;
            case BigDecimal[] a -> a.length;
            case byte[][] a -> a.length;
            // A struct column's row count is its fields' row count (all fields share length,
            // enforced by StructEncodingEncoder); an empty struct carries no rows.
            case StructData d -> d.fieldArrays().isEmpty() ? 0 : of(d.fieldArrays().getFirst());
            case ListData d -> Math.toIntExact(d.outerLen());
            case ListViewData d -> Math.toIntExact(d.outerLen());
            case DateTimePartsData d -> d.timestamps().length;
            case FixedSizeListData d -> Math.toIntExact(d.outerLen());
            case NullableData d -> d.validity().length;
            case VariantData d -> Math.toIntExact(d.length());
            default -> throw new UnsupportedOperationException("unsupported data type: " + data.getClass());
        };
    }
}
