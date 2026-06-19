package io.github.dfa1.vortex.cli.tui;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.reader.array.BoolArray;
import io.github.dfa1.vortex.reader.array.ByteArray;
import io.github.dfa1.vortex.reader.array.DoubleArray;
import io.github.dfa1.vortex.reader.array.FloatArray;
import io.github.dfa1.vortex.reader.array.IntArray;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.array.MaterializedBoolArray;
import io.github.dfa1.vortex.reader.array.MaterializedByteArray;
import io.github.dfa1.vortex.reader.array.MaterializedDoubleArray;
import io.github.dfa1.vortex.reader.array.MaterializedFloatArray;
import io.github.dfa1.vortex.reader.array.MaterializedIntArray;
import io.github.dfa1.vortex.reader.array.MaterializedLongArray;
import io.github.dfa1.vortex.reader.array.MaterializedShortArray;
import io.github.dfa1.vortex.reader.array.ShortArray;
import io.github.dfa1.vortex.reader.array.VarBinArray;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

/// Builds small in-memory `Materialized*` arrays from a confined [Arena] for the
/// render-helper unit tests. Each builder mirrors the on-wire little-endian layout
/// the reader produces so the formatters see realistic inputs.
final class ArrayFixtures {

    private ArrayFixtures() {
    }

    static LongArray longs(Arena arena, long... vs) {
        MemorySegment seg = arena.allocate(vs.length * 8L, 8);
        for (int i = 0; i < vs.length; i++) {
            seg.setAtIndex(ValueLayout.JAVA_LONG, i, vs[i]);
        }
        return new MaterializedLongArray(new DType.Primitive(PType.I64, false), vs.length, seg.asReadOnly());
    }

    static IntArray ints(Arena arena, int... vs) {
        MemorySegment seg = arena.allocate(vs.length * 4L, 4);
        for (int i = 0; i < vs.length; i++) {
            seg.setAtIndex(ValueLayout.JAVA_INT, i, vs[i]);
        }
        return new MaterializedIntArray(new DType.Primitive(PType.I32, false), vs.length, seg.asReadOnly());
    }

    static ShortArray shorts(Arena arena, short... vs) {
        MemorySegment seg = arena.allocate(vs.length * 2L, 2);
        for (int i = 0; i < vs.length; i++) {
            seg.setAtIndex(ValueLayout.JAVA_SHORT, i, vs[i]);
        }
        return new MaterializedShortArray(new DType.Primitive(PType.I16, false), vs.length, seg.asReadOnly());
    }

    static ByteArray bytes(Arena arena, byte... vs) {
        MemorySegment seg = arena.allocate(vs.length, 1);
        for (int i = 0; i < vs.length; i++) {
            seg.set(ValueLayout.JAVA_BYTE, i, vs[i]);
        }
        return new MaterializedByteArray(new DType.Primitive(PType.I8, false), vs.length, seg.asReadOnly());
    }

    static DoubleArray doubles(Arena arena, double... vs) {
        MemorySegment seg = arena.allocate(vs.length * 8L, 8);
        for (int i = 0; i < vs.length; i++) {
            seg.setAtIndex(ValueLayout.JAVA_DOUBLE, i, vs[i]);
        }
        return new MaterializedDoubleArray(new DType.Primitive(PType.F64, false), vs.length, seg.asReadOnly());
    }

    static FloatArray floats(Arena arena, float... vs) {
        MemorySegment seg = arena.allocate(vs.length * 4L, 4);
        for (int i = 0; i < vs.length; i++) {
            seg.setAtIndex(ValueLayout.JAVA_FLOAT, i, vs[i]);
        }
        return new MaterializedFloatArray(new DType.Primitive(PType.F32, false), vs.length, seg.asReadOnly());
    }

    static BoolArray bools(Arena arena, boolean... vs) {
        int bytes = (vs.length + 7) / 8;
        MemorySegment seg = arena.allocate(Math.max(1, bytes), 1);
        for (int i = 0; i < vs.length; i++) {
            if (vs[i]) {
                long byteIdx = i >>> 3;
                byte cur = seg.get(ValueLayout.JAVA_BYTE, byteIdx);
                seg.set(ValueLayout.JAVA_BYTE, byteIdx, (byte) (cur | (1 << (i & 7))));
            }
        }
        return new MaterializedBoolArray(new DType.Bool(false), vs.length, seg.asReadOnly());
    }

    /// Builds a UTF-8 [VarBinArray] (`OffsetMode`, I64 offsets) from the given strings.
    static VarBinArray utf8(Arena arena, String... vs) {
        byte[][] rows = new byte[vs.length][];
        for (int i = 0; i < vs.length; i++) {
            rows[i] = vs[i].getBytes(StandardCharsets.UTF_8);
        }
        return varbin(arena, new DType.Utf8(false), rows);
    }

    /// Builds a binary [VarBinArray] (`OffsetMode`, I64 offsets) from the given byte rows.
    static VarBinArray binary(Arena arena, byte[]... rows) {
        return varbin(arena, new DType.Binary(false), rows);
    }

    private static VarBinArray varbin(Arena arena, DType dtype, byte[]... rows) {
        int total = 0;
        for (byte[] r : rows) {
            total += r.length;
        }
        MemorySegment bytes = arena.allocate(Math.max(1, total), 1);
        MemorySegment offsets = arena.allocate((rows.length + 1) * 8L, 8);
        long pos = 0;
        offsets.setAtIndex(ValueLayout.JAVA_LONG, 0, 0L);
        for (int i = 0; i < rows.length; i++) {
            MemorySegment.copy(MemorySegment.ofArray(rows[i]), 0L, bytes, pos, rows[i].length);
            pos += rows[i].length;
            offsets.setAtIndex(ValueLayout.JAVA_LONG, i + 1, pos);
        }
        return new VarBinArray.OffsetMode(dtype, rows.length, bytes.asReadOnly(), offsets, PType.I64);
    }
}
