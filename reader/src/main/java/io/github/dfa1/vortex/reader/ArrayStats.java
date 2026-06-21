package io.github.dfa1.vortex.reader;

import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.proto.ScalarValue;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/// Per-array statistics embedded in the encoding tree.
///
/// @param min            minimum value in the array, or `null` if unknown
/// @param max            maximum value in the array, or `null` if unknown
/// @param trueCount      number of `true` values for bool columns, or `null` if unknown
/// @param nullCount      number of null values, or `null` if unknown
/// @param isSorted       `true` if the array is sorted in ascending order, or `null` if unknown
/// @param isStrictSorted `true` if the array is strictly sorted (no duplicates), or `null` if unknown
public record ArrayStats(
        Object min,
        Object max,
        Long trueCount,
        Long nullCount,
        Boolean isSorted,
        Boolean isStrictSorted
) {
    private static final ArrayStats EMPTY = new ArrayStats(null, null, null, null, null, null);

    /// Returns an empty stats instance with all fields set to `null`.
    ///
    /// @return an empty [ArrayStats] with no known statistics
    public static ArrayStats empty() {
        return EMPTY;
    }

    /// Parses stats from a FlatBuffers [io.github.dfa1.vortex.fbs.ArrayStats] table.
    /// Returns an empty instance when `fbs` is `null` or carries no min/max and no null count.
    ///
    /// @param fbs the FlatBuffers stats table, or `null`
    /// @return parsed stats, or an empty instance if no usable data is present
    public static ArrayStats fromFbs(io.github.dfa1.vortex.fbs.ArrayStats fbs) {
        if (fbs == null) {
            return EMPTY;
        }
        Object min = decodeScalar(fbs.minAsByteBuffer());
        Object max = decodeScalar(fbs.maxAsByteBuffer());
        Long nullCount = fbs.hasNullCount() ? fbs.nullCount() : null;
        if (min == null && max == null && nullCount == null) {
            return EMPTY;
        }
        return new ArrayStats(min, max, null, nullCount, null, null);
    }

    private static Object decodeScalar(ByteBuffer bytes) {
        if (bytes == null || !bytes.hasRemaining()) {
            return null;
        }
        try {
            MemorySegment seg = MemorySegment.ofBuffer(bytes.duplicate());
            ScalarValue sv = ScalarValue.decode(seg, 0, seg.byteSize());
            if (sv.int64_value() != null) {
                return sv.int64_value();
            }
            if (sv.uint64_value() != null) {
                return sv.uint64_value();
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
