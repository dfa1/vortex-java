package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.io.VortexFormat;
import io.github.dfa1.vortex.core.model.DType;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.util.Objects;

/// Metadata-only [Float16Array] for `vortex.sequence` columns: `A[i] = base + i * multiplier`.
///
/// The encoding carries no buffers at all — base and multiplier live in proto3 metadata — so
/// every row is computable in O(1) and no allocation is needed regardless of row count.
///
/// The step is computed in `float` and round-tripped through half precision on every read, so
/// values match the eager decode this replaces bit for bit: it wrote
/// [Float#floatToFloat16(float)] into the buffer and widened again on access.
///
/// @param dtype      logical primitive type (F16)
/// @param length     total logical row count
/// @param base       value at row 0, already widened from half precision
/// @param multiplier step added per row, already widened from half precision
public record LazySequenceFloat16Array(DType dtype, long length, float base, float multiplier)
        implements Float16Array {

    @Override
    public float getFloat(long i) {
        Objects.checkIndex(i, length);
        return Float.float16ToFloat(Float.floatToFloat16(base + i * multiplier));
    }

    /// Zero-copy truncation: the formula is unchanged for the leading rows, so only the
    /// row count shrinks.
    ///
    /// @param rows number of leading rows to keep
    /// @return a length-`rows` sequence over the same base and multiplier
    @Override
    public Array limited(long rows) {
        return rows >= length ? this : new LazySequenceFloat16Array(dtype, rows, base, multiplier);
    }

    /// Materializes the sequence into a fresh little-endian half-precision segment.
    /// [Float16Array] declares no default, and this is the only allocation the encoding
    /// performs — on demand, for consumers that need a contiguous buffer.
    ///
    /// @param arena allocator for the output segment
    /// @return a little-endian `f16` segment of `length()` elements
    @Override
    public MemorySegment materialize(SegmentAllocator arena) {
        long n = length;
        float b = base;
        float m = multiplier;
        MemorySegment dst = arena.allocate(n * 2L, 2);
        for (long i = 0; i < n; i++) {
            dst.setAtIndex(VortexFormat.LE_SHORT, i, Float.floatToFloat16(b + i * m));
        }
        return dst;
    }
}
