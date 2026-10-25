package io.github.dfa1.vortex.reader;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.proto.ProtoScalarValue;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;

/// Per-array statistics embedded in the encoding tree: one component per field of the wire
/// `ArrayStats` table (Rust's `Stat` enum). A bool column's true count is its `sum`, as in Rust.
///
/// `min`/`max` may be inexact (Rust's `Precision::Inexact`): still a valid bound, so pruning on a
/// range may use it, but not the true extreme (Rust's flat writer truncates a string bound longer
/// than 64 bytes). Anything that reports the value, or needs `min == max`, requires
/// `minExact`/`maxExact`.
///
/// The counts are `u64` on the wire, read as a signed `Long`.
///
/// @param min                     minimum value in the array, or `null` if unknown
/// @param minExact                `true` if `min` is the exact minimum, not only a lower bound
/// @param max                     maximum value in the array, or `null` if unknown
/// @param maxExact                `true` if `max` is the exact maximum, not only an upper bound
/// @param sum                     sum of values (`Long` for integer and bool columns, `Double` for floats), or `null` if unknown
/// @param isSorted                `true` if the values are sorted ascending with nulls first (Rust orders null lowest), or `null` if unknown
/// @param isStrictSorted          `true` if also no two neighbors are equal, two nulls included, or `null` if unknown
/// @param isConstant              `true` if every value is null, or none is and all are equal, or `null` if unknown
/// @param nullCount               number of null values, or `null` if unknown
/// @param uncompressedSizeInBytes the array's uncompressed size in bytes, or `null` if unknown
/// @param nanCount                number of `NaN` values in a float column (`min` and `max` skip them), or `null` if unknown
public record ArrayStats(
        Object min,
        boolean minExact,
        Object max,
        boolean maxExact,
        Object sum,
        Boolean isSorted,
        Boolean isStrictSorted,
        Boolean isConstant,
        Long nullCount,
        Long uncompressedSizeInBytes,
        Long nanCount
) {
    private static final ArrayStats EMPTY =
            new ArrayStats(null, false, null, false, null, null, null, null, null, null, null);

    /// Returns an empty stats instance with all fields set to `null`.
    ///
    /// @return an empty [ArrayStats] with no known statistics
    public static ArrayStats empty() {
        return EMPTY;
    }

    /// Parses stats from a FlatBuffers [io.github.dfa1.vortex.core.fbs.FbsArrayStats] table.
    /// Returns an empty instance when `fbs` is `null` or carries no statistic.
    ///
    /// @param fbs the FlatBuffers stats table, or `null`
    /// @return parsed stats, or an empty instance if no usable data is present
    // Low-level entry point for the parse path and the inspector; it intentionally exposes the generated
    // FlatBuffers type, which core only qualified-exports to the modules that need it.
    @SuppressWarnings("exports")
    public static ArrayStats fromFbs(io.github.dfa1.vortex.core.fbs.FbsArrayStats fbs) {
        if (fbs == null) {
            return EMPTY;
        }
        Object min = decodeScalar(fbs.minAsSegment());
        Object max = decodeScalar(fbs.maxAsSegment());
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
        return stats.equals(EMPTY) ? EMPTY : stats;
    }

    /// Rust's `from_flatbuffer`: an absent precision is the FlatBuffer default `Inexact`, and any
    /// value other than `Inexact`/`Exact` is a corrupted field. Read only when its bound is present.
    private static boolean isExact(int precision, String field) {
        return switch (precision) {
            case io.github.dfa1.vortex.core.fbs.FbsPrecision.Inexact -> false;
            case io.github.dfa1.vortex.core.fbs.FbsPrecision.Exact -> true;
            default -> throw new VortexException("corrupted " + field + " in array stats: " + precision);
        };
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
