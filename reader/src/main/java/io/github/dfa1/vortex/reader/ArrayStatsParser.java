package io.github.dfa1.vortex.reader;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.fbs.FbsArrayStats;
import io.github.dfa1.vortex.core.fbs.FbsPrecision;
import io.github.dfa1.vortex.core.model.ArrayStats;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.proto.ProtoScalarValue;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;

/// Parses the wire `ArrayStats` FlatBuffer table into an [ArrayStats]: Rust's `from_flatbuffer`.
public final class ArrayStatsParser {

    private ArrayStatsParser() {
    }

    /// Parses stats from a FlatBuffers [FbsArrayStats] table.
    /// Returns [ArrayStats#empty()] when `fbs` is `null` or carries no statistic.
    ///
    /// @param fbs the FlatBuffers stats table, or `null`
    /// @return parsed stats, or an empty instance if no usable data is present
    /// @throws VortexException if a precision field or a scalar value is malformed
    public static ArrayStats parse(FbsArrayStats fbs) {
        return parse(fbs, null);
    }

    /// Parses stats like [#parse(FbsArrayStats)], decoding the min and max against the column's
    /// dtype: Rust writes a decimal bound as the little-endian two's-complement bytes of its unscaled
    /// value, which only the dtype's scale turns into a number.
    ///
    /// @param fbs   the FlatBuffers stats table, or `null`
    /// @param dtype the dtype of the array the stats describe, or `null` when unknown
    /// @return parsed stats, or an empty instance if no usable data is present
    /// @throws VortexException if a precision field or a scalar value is malformed
    public static ArrayStats parse(FbsArrayStats fbs, DType dtype) {
        if (fbs == null) {
            return ArrayStats.empty();
        }
        Object min = decodeBound(fbs.minAsSegment(), dtype);
        Object max = decodeBound(fbs.maxAsSegment(), dtype);
        ArrayStats stats = new ArrayStats(
                min,
                min != null && isExact(fbs.minPrecision(), "min_precision"),
                max,
                max != null && isExact(fbs.maxPrecision(), "max_precision"),
                decodeScalar(fbs.sumAsSegment()),
                fbs.hasIsSorted() ? fbs.isSorted() : null,
                fbs.hasIsStrictSorted() ? fbs.isStrictSorted() : null,
                fbs.hasIsConstant() ? fbs.isConstant() : null,
                fbs.hasNullCount() ? fbs.nullCount() : null,
                fbs.hasUncompressedSizeInBytes() ? fbs.uncompressedSizeInBytes() : null,
                fbs.hasNanCount() ? fbs.nanCount() : null);
        return stats.equals(ArrayStats.empty()) ? ArrayStats.empty() : stats;
    }

    /// Rust's `from_flatbuffer`: an absent precision is the FlatBuffer default `Inexact`, and any
    /// value other than `Inexact`/`Exact` is a corrupted field. Read only when its bound is present.
    private static boolean isExact(int precision, String field) {
        return switch (precision) {
            case FbsPrecision.Inexact -> false;
            case FbsPrecision.Exact -> true;
            default -> throw new VortexException("corrupted " + field + " in array stats: " + precision);
        };
    }

    private static Object decodeBound(MemorySegment seg, DType dtype) {
        Object value = decodeScalar(seg);
        // Rust's from_proto reads an extension's scalar as its storage dtype's.
        while (dtype instanceof DType.Extension extension) {
            dtype = extension.storageDType();
        }
        if (!(dtype instanceof DType.Decimal decimal) || value == null) {
            return value;
        }
        // Rust reads a decimal's BytesValue only (bytes_from_proto): any other kind is a corrupted
        // bound, and must not reach a fold that orders BigDecimal against it.
        byte[] bytes = decodeBytes(seg);
        if (bytes == null) {
            throw new VortexException("decimal bound in array stats is not a byte string: " + value);
        }
        // bytes_from_proto's match: the storage width, 1, 2, 4, 8, 16 or 32 bytes.
        int width = bytes.length;
        switch (width) {
            case 1, 2, 4, 8, 16, 32 -> { }
            default -> throw new VortexException("invalid decimal byte length in array stats: " + width);
        }
        byte[] bigEndian = new byte[width];
        for (int i = 0; i < width; i++) {
            bigEndian[i] = bytes[width - 1 - i];
        }
        return new BigDecimal(new BigInteger(bigEndian), decimal.scale());
    }

    private static byte[] decodeBytes(MemorySegment seg) {
        try {
            return ProtoScalarValue.decode(seg, 0, seg.byteSize()).bytes_value();
        } catch (IOException e) {
            throw new VortexException("invalid scalar value in array stats", e);
        }
    }

    private static Object decodeScalar(MemorySegment seg) {
        if (seg == null || seg.byteSize() == 0) {
            return null;
        }
        try {
            ProtoScalarValue sv = ProtoScalarValue.decode(seg, 0, seg.byteSize());
            if (sv.int64_value() != null) {
                return sv.int64_value();
            }
            if (sv.uint64_value() != null) {
                return sv.uint64_value();
            }
            if (sv.f16_value() != null) {
                return Float.float16ToFloat((short) sv.f16_value().longValue());
            }
            if (sv.f32_value() != null) {
                return sv.f32_value();
            }
            if (sv.f64_value() != null) {
                return sv.f64_value();
            }
            if (sv.bool_value() != null) {
                return sv.bool_value();
            }
            if (sv.string_value() != null) {
                return sv.string_value();
            }
            if (sv.bytes_value() != null) {
                return new String(sv.bytes_value(), StandardCharsets.UTF_8);
            }
            return null;
        } catch (IOException e) {
            throw new VortexException("invalid scalar value in array stats", e);
        }
    }
}
