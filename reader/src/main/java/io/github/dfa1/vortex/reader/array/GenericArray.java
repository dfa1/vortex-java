package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.VortexException;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteOrder;

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

    /// Creates a new `GenericArray` with the given buffers and children.
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

    /// Creates a new `GenericArray` with a single buffer and no children.
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

    /// Returns a view of this array clamped to `newLength` logical rows.
    /// Buffers and children are reused as-is; callers are expected to respect
    /// {@link #length()} when reading. Used by the scan iterator to honour
    /// `ScanOptions.limit` for dtypes that don't have a typed array.
    ///
    /// @param newLength desired logical length; must be `<= length()`
    /// @return a new `GenericArray` sharing this array's buffers and children
    /// @throws IllegalArgumentException if `newLength` exceeds the current length
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

    @Override
    public Array truncate(long rows) {
        return withLength(rows);
    }

    MemorySegment buffer(int i) {
        return buffers[i];
    }

    /// Decodes the decimal value at row `i` from a single-buffer layout.
    ///
    /// The buffer holds one little-endian two's-complement integer per row. Element
    /// width is derived from the buffer's byte size divided by {@link #length()},
    /// not from the dtype's precision — `vortex.decimal` writes whatever width
    /// the encoder chose in its `valuesType` metadata, which can be narrower
    /// than the precision alone would allow.
    ///
    /// The child-array shape produced by `vortex.decimal_byte_parts` is now
    /// handled by {@link LazyDecimalBytePartsArray} directly.
    ///
    /// @param i row index, `0 <= i < length()`
    /// @return decoded value as a {@link BigDecimal} with the dtype's scale
    /// @throws VortexException             if the dtype isn't decimal or the array
    ///                                     shape isn't the single-buffer layout
    /// @throws IndexOutOfBoundsException if `i` is outside `[0, length())`
    public BigDecimal getDecimal(long i) {
        if (i < 0 || i >= length) {
            throw new IndexOutOfBoundsException("index " + i + " out of bounds for length " + length);
        }
        if (!(dtype instanceof DType.Decimal d)) {
            throw new VortexException("getDecimal called on non-decimal dtype: " + dtype);
        }
        if (buffers.length != 1 || children.length != 0) {
            throw new VortexException("getDecimal: unsupported decimal shape buffers="
                    + buffers.length + " children=" + children.length);
        }
        BigInteger mantissa = readSingleBufferMantissa(buffers[0], length, i);
        return new BigDecimal(mantissa, d.scale());
    }

    private static BigInteger readSingleBufferMantissa(MemorySegment buf, long length, long i) {
        long bufBytes = buf.byteSize();
        if (length == 0 || bufBytes % length != 0) {
            throw new VortexException("getDecimal: buffer size " + bufBytes
                    + " is not a multiple of length " + length);
        }
        int width = (int) (bufBytes / length);
        if (width != 1 && width != 2 && width != 4 && width != 8 && width != 16) {
            throw new VortexException("getDecimal: unsupported element width " + width + " bytes");
        }
        return readSignedLe(buf, i * width, width);
    }

    private static final ValueLayout.OfShort SHORT_LE =
            ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfInt INT_LE =
            ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfLong LONG_LE =
            ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    private static BigInteger readSignedLe(MemorySegment buf, long offset, int width) {
        return switch (width) {
            case 1 -> BigInteger.valueOf(buf.get(ValueLayout.JAVA_BYTE, offset));
            case 2 -> BigInteger.valueOf(buf.get(SHORT_LE, offset));
            case 4 -> BigInteger.valueOf(buf.get(INT_LE, offset));
            case 8 -> BigInteger.valueOf(buf.get(LONG_LE, offset));
            case 16 -> readSigned128Le(buf, offset);
            default -> throw new VortexException("readSignedLe: unsupported width " + width);
        };
    }

    private static BigInteger readSigned128Le(MemorySegment buf, long offset) {
        // Two's-complement i128 on disk in little-endian; BigInteger ingests big-endian.
        // No SIMD intrinsic for 16-byte signed integer, so we materialise into a heap
        // buffer here. Only fires for decimal(>18, _) — narrow-precision fast paths above
        // stay allocation-free.
        byte[] be = new byte[16];
        for (int k = 0; k < 16; k++) {
            be[15 - k] = buf.get(ValueLayout.JAVA_BYTE, offset + k);
        }
        return new BigInteger(be);
    }

    /// Returns the child array at position `i`.
    ///
    /// @param i child index
    /// @return the child {@link Array} at index `i`
    public Array child(int i) {
        return children[i];
    }
}
