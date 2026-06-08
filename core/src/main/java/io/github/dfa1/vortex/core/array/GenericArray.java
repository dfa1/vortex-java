package io.github.dfa1.vortex.core.array;

import io.github.dfa1.vortex.core.DType;

import java.lang.foreign.MemorySegment;

/// Fallback [Array] for dtypes that lack a dedicated concrete subtype.
///
/// Holds raw buffer segments and child arrays. Used by encodings during migration
/// and for less-common dtypes (e.g. Decimal, Ext) where typed accessors are
/// not yet implemented.
public final class GenericArray implements Array {

    private final DType dtype;
    private final long length;
    private final MemorySegment[] buffers;
    private final Array[] children;

    /// Creates a new {@code GenericArray} with the given buffers and children.
    ///
    /// @param dtype    logical type of this array
    /// @param length   number of logical elements
    /// @param buffers  raw memory segments backing this array's data
    /// @param children child arrays (e.g. offsets, validity)
    public GenericArray(DType dtype, long length, MemorySegment[] buffers, Array[] children) {
        this.dtype = dtype;
        this.length = length;
        this.buffers = buffers;
        this.children = children;
    }

    /// Creates a new {@code GenericArray} with a single buffer and no children.
    ///
    /// @param dtype  logical type of this array
    /// @param length number of logical elements
    /// @param buffer single raw memory segment backing this array's data
    public GenericArray(DType dtype, long length, MemorySegment buffer) {
        this(dtype, length, new MemorySegment[]{buffer}, new Array[0]);
    }

    @Override
    public DType dtype() {
        return dtype;
    }

    @Override
    public long length() {
        return length;
    }

    /// Returns a view of this array clamped to {@code newLength} logical rows.
    /// Buffers and children are reused as-is; callers are expected to respect
    /// {@link #length()} when reading. Used by the scan iterator to honour
    /// {@code ScanOptions.limit} for dtypes that don't have a typed array.
    ///
    /// @param newLength desired logical length; must be {@code <= length()}
    /// @return a new {@code GenericArray} sharing this array's buffers and children
    /// @throws IllegalArgumentException if {@code newLength} exceeds the current length
    public GenericArray withLength(long newLength) {
        if (newLength < 0 || newLength > length) {
            throw new IllegalArgumentException(
                    "newLength " + newLength + " out of range [0," + length + "]");
        }
        if (newLength == length) {
            return this;
        }
        return new GenericArray(dtype, newLength, buffers, children);
    }

    MemorySegment buffer(int i) {
        return buffers[i];
    }

    /// Returns the number of raw memory buffers backing this array.
    ///
    /// @return buffer count
    public int bufferCount() {
        return buffers.length;
    }

    /// Returns the raw buffer at position {@code i}. Used by callers that need
    /// to inspect encoded bytes when no typed accessor exists for the dtype
    /// (e.g. the TUI inspector decoding {@code Decimal} cells).
    ///
    /// @param i buffer index
    /// @return the underlying {@link MemorySegment}
    public MemorySegment bufferAt(int i) {
        return buffers[i];
    }

    /// Returns the number of child arrays.
    ///
    /// @return child count
    public int childCount() {
        return children.length;
    }

    /// Returns the child array at position {@code i}.
    ///
    /// @param i child index
    /// @return the child {@link Array} at index {@code i}
    public Array child(int i) {
        return children[i];
    }
}
