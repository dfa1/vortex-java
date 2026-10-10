package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.io.VortexFormat;
import io.github.dfa1.vortex.core.model.DType;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.util.Objects;

/// Metadata-only [Float16Array] for `vortex.constant` columns.
///
/// Holds a single F16 value broadcast across `length` logical rows. No buffer is allocated —
/// `getFloat(i)` returns the stored value, widened from half precision, for any valid index.
/// The value is kept as its 16 raw bits so a NaN payload or `-0.0` survives a re-encode.
///
/// @param dtype  logical primitive type (F16)
/// @param length total logical row count
/// @param bits   broadcast value as raw half-precision bits
public record LazyConstantFloat16Array(DType dtype, long length, short bits) implements Float16Array {

    @Override
    public float getFloat(long i) {
        Objects.checkIndex(i, length);
        return Float.float16ToFloat(bits);
    }

    /// Zero-copy truncation: every row holds the same value, so only the row count shrinks.
    ///
    /// @param rows number of leading rows to keep
    /// @return a length-`rows` constant over the same value
    @Override
    public Array limited(long rows) {
        return rows >= length ? this : new LazyConstantFloat16Array(dtype, rows, bits);
    }

    /// Materializes the constant into a fresh little-endian half-precision segment, on demand
    /// for consumers that need a contiguous buffer.
    ///
    /// @param arena allocator for the output segment
    /// @return a little-endian `f16` segment of `length()` elements
    @Override
    public MemorySegment materialize(SegmentAllocator arena) {
        long n = length;
        MemorySegment dst = arena.allocate(n * 2L, 2);
        for (long i = 0; i < n; i++) {
            dst.setAtIndex(VortexFormat.LE_SHORT, i, bits);
        }
        return dst;
    }
}
