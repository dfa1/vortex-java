package io.github.dfa1.vortex.core.array;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.VortexException;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.math.BigDecimal;
import java.math.BigInteger;

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

    /// Decodes the decimal value at row {@code i}.
    ///
    /// Handles the two shapes produced by Vortex decimal decoders:
    ///
    /// - **single-buffer**: one raw buffer of little-endian two's-complement
    ///   integers (one element per row); element width derived from the
    ///   dtype's precision (1 / 2 / 4 / 8 / 16 bytes for precision ≤ 2 / 4 /
    ///   9 / 18 / 38). Produced by {@code vortex.decimal}.
    /// - **child-array**: zero buffers, one child holding the most-significant
    ///   integer part as a {@link LongArray}, {@link IntArray}, {@link ShortArray},
    ///   or {@link ByteArray}. Produced by {@code vortex.decimal_byte_parts}
    ///   when {@code lower_part_count == 0}.
    ///
    /// @param i row index, {@code 0 <= i < length()}
    /// @return decoded value as a {@link BigDecimal} with the dtype's scale
    /// @throws VortexException if the dtype isn't decimal or the array shape
    ///         doesn't match either supported layout
    public BigDecimal getDecimal(long i) {
        if (!(dtype instanceof DType.Decimal d)) {
            throw new VortexException("getDecimal called on non-decimal dtype: " + dtype);
        }
        BigInteger mantissa;
        if (buffers.length == 1 && children.length == 0) {
            int width = decimalByteWidth(d.precision());
            mantissa = readSignedLe(buffers[0], i * width, width);
        } else if (buffers.length == 0 && children.length == 1) {
            mantissa = mantissaFromChild(children[0], i);
        } else {
            throw new VortexException("getDecimal: unsupported decimal shape buffers="
                    + buffers.length + " children=" + children.length);
        }
        return new BigDecimal(mantissa, d.scale());
    }

    private static BigInteger mantissaFromChild(Array child, long i) {
        return switch (child) {
            case LongArray a -> BigInteger.valueOf(a.getLong(i));
            case IntArray a -> BigInteger.valueOf(a.getInt(i));
            case ShortArray a -> BigInteger.valueOf(a.getShort(i));
            case ByteArray a -> BigInteger.valueOf(a.getByte(i));
            case MaskedArray a -> mantissaFromChild(a.inner(), i);
            default ->
                    throw new VortexException("getDecimal: unsupported mantissa child type "
                            + child.getClass().getSimpleName());
        };
    }

    private static int decimalByteWidth(int precision) {
        if (precision <= 2) {
            return 1;
        }
        if (precision <= 4) {
            return 2;
        }
        if (precision <= 9) {
            return 4;
        }
        if (precision <= 18) {
            return 8;
        }
        return 16;
    }

    private static BigInteger readSignedLe(MemorySegment buf, long offset, int width) {
        // Little-endian two's-complement on disk; BigInteger expects big-endian.
        byte[] be = new byte[width];
        for (int k = 0; k < width; k++) {
            be[width - 1 - k] = buf.get(ValueLayout.JAVA_BYTE, offset + k);
        }
        return new BigInteger(be);
    }

    /// Returns the child array at position {@code i}.
    ///
    /// @param i child index
    /// @return the child {@link Array} at index {@code i}
    public Array child(int i) {
        return children[i];
    }
}
