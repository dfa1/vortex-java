package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.encoding.DTypes;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/// Shared builders for in-memory `Materialized*Array` instances in reader tests.
///
/// Replaces the per-file copy-pasted segment-allocation helpers. Each builder
/// allocates a little-endian, read-only segment from the supplied [Arena] with the
/// default signed dtype (from [DTypes]) for its width. Tests that need an unsigned
/// dtype (U8/U16) for widening behaviour build the array inline instead.
public final class TestArrays {

    private TestArrays() {
    }

    /// Builds an I64-typed [LongArray] from the given values.
    ///
    /// @param arena allocator for the backing segment
    /// @param vs    element values
    /// @return read-only materialized long array
    public static LongArray longs(Arena arena, long... vs) {
        MemorySegment seg = arena.allocate(vs.length * 8L, 8);
        for (int i = 0; i < vs.length; i++) {
            seg.setAtIndex(ValueLayout.JAVA_LONG, i, vs[i]);
        }
        return new MaterializedLongArray(DTypes.I64, vs.length, seg.asReadOnly());
    }

    /// Builds an I32-typed [IntArray] from the given values.
    ///
    /// @param arena allocator for the backing segment
    /// @param vs    element values
    /// @return read-only materialized int array
    public static IntArray ints(Arena arena, int... vs) {
        MemorySegment seg = arena.allocate(vs.length * 4L, 4);
        for (int i = 0; i < vs.length; i++) {
            seg.setAtIndex(ValueLayout.JAVA_INT, i, vs[i]);
        }
        return new MaterializedIntArray(DTypes.I32, vs.length, seg.asReadOnly());
    }

    /// Builds an F64-typed [DoubleArray] from the given values.
    ///
    /// @param arena allocator for the backing segment
    /// @param vs    element values
    /// @return read-only materialized double array
    public static DoubleArray doubles(Arena arena, double... vs) {
        MemorySegment seg = arena.allocate(vs.length * 8L, 8);
        for (int i = 0; i < vs.length; i++) {
            seg.setAtIndex(ValueLayout.JAVA_DOUBLE, i, vs[i]);
        }
        return new MaterializedDoubleArray(DTypes.F64, vs.length, seg.asReadOnly());
    }

    /// Builds an F32-typed [FloatArray] from the given values.
    ///
    /// @param arena allocator for the backing segment
    /// @param vs    element values
    /// @return read-only materialized float array
    public static FloatArray floats(Arena arena, float... vs) {
        MemorySegment seg = arena.allocate(vs.length * 4L, 4);
        for (int i = 0; i < vs.length; i++) {
            seg.setAtIndex(ValueLayout.JAVA_FLOAT, i, vs[i]);
        }
        return new MaterializedFloatArray(DTypes.F32, vs.length, seg.asReadOnly());
    }

    /// Builds an I16-typed [ShortArray] from the given values.
    ///
    /// @param arena allocator for the backing segment
    /// @param vs    element values
    /// @return read-only materialized short array
    public static ShortArray shorts(Arena arena, short... vs) {
        MemorySegment seg = arena.allocate(vs.length * 2L, 2);
        for (int i = 0; i < vs.length; i++) {
            seg.setAtIndex(ValueLayout.JAVA_SHORT, i, vs[i]);
        }
        return new MaterializedShortArray(DTypes.I16, vs.length, seg.asReadOnly());
    }

    /// Builds an I8-typed [ByteArray] from the given values.
    ///
    /// @param arena allocator for the backing segment
    /// @param vs    element values
    /// @return read-only materialized byte array
    public static ByteArray bytes(Arena arena, byte... vs) {
        MemorySegment seg = arena.allocate(Math.max(1, vs.length), 1);
        for (int i = 0; i < vs.length; i++) {
            seg.set(ValueLayout.JAVA_BYTE, i, vs[i]);
        }
        return new MaterializedByteArray(DTypes.I8, vs.length, seg.asReadOnly());
    }

    /// Builds a Bool [BoolArray] from the given values (LSB-first bit packing).
    ///
    /// @param arena allocator for the backing segment
    /// @param vs    element values
    /// @return read-only materialized bool array
    public static BoolArray bools(Arena arena, boolean... vs) {
        MemorySegment seg = arena.allocate(Math.max(1, (vs.length + 7) / 8), 1);
        for (int i = 0; i < vs.length; i++) {
            if (vs[i]) {
                long byteIdx = i >>> 3;
                byte cur = seg.get(ValueLayout.JAVA_BYTE, byteIdx);
                seg.set(ValueLayout.JAVA_BYTE, byteIdx, (byte) (cur | (1 << (i & 7))));
            }
        }
        return new MaterializedBoolArray(DTypes.BOOL, vs.length, seg.asReadOnly());
    }

    /// Builds an F16-typed [Float16Array] from the given float values.
    ///
    /// @param arena allocator for the backing segment
    /// @param vs    element values (converted to half precision)
    /// @return read-only materialized float16 array
    public static Float16Array float16(Arena arena, float... vs) {
        MemorySegment seg = arena.allocate(vs.length * 2L, 2);
        for (int i = 0; i < vs.length; i++) {
            seg.setAtIndex(ValueLayout.JAVA_SHORT, i, Float.floatToFloat16(vs[i]));
        }
        return new MaterializedFloat16Array(DTypes.F16, vs.length, seg.asReadOnly());
    }
}
