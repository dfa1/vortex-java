package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.compute.PrimitiveArrays;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.proto.ProtoScalarValue;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Objects;

/// Computes the `{min, max}` zone-map bounds for whatever shape an encoder was handed.
///
/// Every encoding that wins a column is responsible for reporting the column's bounds, or that
/// column silently loses zone-map pruning and `MIN`/`MAX` push-down. Five encodings shipped
/// without doing so before this existed, each found by eye on one dataset, because the bounds had
/// to be assembled differently for each input shape and passing `null` compiled just as well.
///
/// The shapes an encoder can receive:
/// - [NullableData] - null slots hold whatever the backing array happened to contain, so the
///   values are compacted against the validity mask first. Taking the raw array's minimum here
///   would report `0` for a column of positive values, which is worse than reporting nothing.
/// - a primitive array (`long[]`, `double[]`, …) matching the dtype's [io.github.dfa1.vortex.core.model.PType]
/// - `String[]`, compared lexicographically, skipping nulls
///
/// Anything else - binary blobs, nested types - has no useful ordering and yields `null`.
final class ZoneMapStats {

    private ZoneMapStats() {
    }

    /// The zone-map bounds for `data`, or `null` when the shape carries no useful ordering.
    ///
    /// @param dtype the column's dtype; a nullable one is read through to its non-nullable form
    /// @param data  the values being encoded
    /// @return a two-element `{min, max}` array of encoded scalars, or `null`
    static byte[][] of(DType dtype, Object data) {
        if (data instanceof NullableData(var values, var validity)) {
            DType nonNullable = dtype == null ? null : dtype.withNullable(false);
            if (nonNullable instanceof DType.Primitive p) {
                return PrimitiveEncodingEncoder.minMaxStats(p.ptype(),
                        PrimitiveArrays.compact(p.ptype(), values, validity));
            }
            if (nonNullable instanceof DType.Decimal d && values instanceof BigDecimal[] decimals) {
                return ofDecimal(d, decimals);
            }
            // A String[] carries a real null at its invalid positions, so the lexicographic
            // null-skip is already correct and needs no compaction.
            return values instanceof String[] strings ? VarBinEncodingEncoder.minMaxStats(strings) : null;
        }
        if (data instanceof String[] strings) {
            return VarBinEncodingEncoder.minMaxStats(strings);
        }
        DType effective = dtype instanceof DType.Extension ext ? ext.storageDType() : dtype;
        if (effective instanceof DType.Primitive p && data != null && data.getClass().isArray()) {
            return PrimitiveEncodingEncoder.minMaxStats(p.ptype(), data);
        }
        return null;
    }

    /// Bounds over the non-null `values` of a decimal column (a `null` is a masked-out row),
    /// packed at the column's storage width like the dense path.
    ///
    /// @param dtype  the decimal dtype (precision picks the width)
    /// @param values the decimals, `null` at masked-out rows
    /// @return a two-element `{min, max}` array of encoded scalars, or `null` when none is valid
    static byte[][] ofDecimal(DType.Decimal dtype, BigDecimal[] values) {
        BigDecimal[] valid = Arrays.stream(values).filter(Objects::nonNull).toArray(BigDecimal[]::new);
        int width = DecimalEncodingEncoder.storageWidth(dtype.precision());
        return ofDecimal(DecimalEncodingEncoder.pack(valid, width, Arena.ofAuto()), width);
    }

    /// Bounds for a decimal column whose values are packed little-endian at a fixed width.
    ///
    /// `ScalarValue` has no decimal variant, in our schema or upstream's. The Rust reference
    /// encodes a decimal scalar as `bytes_value` holding the unscaled integer little-endian at the
    /// column's storage width - `DecimalValue::I8..I256 => v.to_le_bytes()` in
    /// `vortex-array/src/scalar/proto.rs` - so that is what is written here.
    ///
    /// Comparison is two's-complement over the whole width, so it is correct for I128 and I256 as
    /// well without widening anything into a `BigInteger` per value.
    ///
    /// @param values    the packed values
    /// @param byteWidth bytes per value, 1/2/4/8/16/32
    /// @return a two-element `{min, max}` array of encoded scalars, or `null` when empty
    static byte[][] ofDecimal(MemorySegment values, int byteWidth) {
        long n = values.byteSize() / byteWidth;
        if (n == 0) {
            return null;
        }
        long minOff = 0;
        long maxOff = 0;
        for (long i = 1; i < n; i++) {
            long off = i * byteWidth;
            if (compareTwosComplement(values, off, minOff, byteWidth) < 0) {
                minOff = off;
            }
            if (compareTwosComplement(values, off, maxOff, byteWidth) > 0) {
                maxOff = off;
            }
        }
        return new byte[][]{decimalScalar(values, minOff, byteWidth), decimalScalar(values, maxOff, byteWidth)};
    }

    /// Bounds for a decimal column already widened into `long` values, encoded at the same
    /// eight-byte width Rust uses for `DecimalValue::I64`.
    ///
    /// @param values the unscaled values
    /// @return a two-element `{min, max}` array of encoded scalars, or `null` when empty
    static byte[][] ofDecimal(long[] values) {
        if (values.length == 0) {
            return null;
        }
        long min = values[0];
        long max = values[0];
        for (long v : values) {
            if (v < min) {
                min = v;
            }
            if (v > max) {
                max = v;
            }
        }
        return new byte[][]{decimalScalar(min), decimalScalar(max)};
    }

    /// Compares two equal-width little-endian two's-complement values in place: the most
    /// significant byte decides as signed, the rest as unsigned.
    private static int compareTwosComplement(MemorySegment values, long a, long b, int byteWidth) {
        byte topA = values.get(ValueLayout.JAVA_BYTE, a + byteWidth - 1);
        byte topB = values.get(ValueLayout.JAVA_BYTE, b + byteWidth - 1);
        if (topA != topB) {
            return Byte.compare(topA, topB);
        }
        for (int i = byteWidth - 2; i >= 0; i--) {
            int x = values.get(ValueLayout.JAVA_BYTE, a + i) & 0xff;
            int y = values.get(ValueLayout.JAVA_BYTE, b + i) & 0xff;
            if (x != y) {
                return Integer.compare(x, y);
            }
        }
        return 0;
    }

    private static byte[] decimalScalar(MemorySegment values, long offset, int byteWidth) {
        return ProtoScalarValue.ofBytesValue(
                values.asSlice(offset, byteWidth).toArray(ValueLayout.JAVA_BYTE)).encode();
    }

    private static byte[] decimalScalar(long value) {
        byte[] le = new byte[Long.BYTES];
        for (int i = 0; i < Long.BYTES; i++) {
            le[i] = (byte) (value >>> (8 * i));
        }
        return ProtoScalarValue.ofBytesValue(le).encode();
    }

    /// The min half of an [#of(DType, Object)] result, or `null`.
    ///
    /// @param stats an [#of(DType, Object)] result, possibly `null`
    /// @return the serialized min scalar, or `null`
    static byte[] minOf(byte[][] stats) {
        return stats == null ? null : stats[0];
    }

    /// The max half of an [#of(DType, Object)] result, or `null`.
    ///
    /// @param stats an [#of(DType, Object)] result, possibly `null`
    /// @return the serialized max scalar, or `null`
    static byte[] maxOf(byte[][] stats) {
        return stats == null ? null : stats[1];
    }
}
