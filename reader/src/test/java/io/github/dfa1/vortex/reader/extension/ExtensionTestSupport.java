package io.github.dfa1.vortex.reader.extension;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.reader.array.IntArray;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.array.MaterializedIntArray;
import io.github.dfa1.vortex.reader.array.MaterializedLongArray;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/// Shared fixtures for the per-extension decoder test classes.
final class ExtensionTestSupport {

    static final DType.Primitive I32 = new DType.Primitive(PType.I32, false);
    static final DType.Primitive I64 = new DType.Primitive(PType.I64, false);
    static final DType.Primitive U8 = new DType.Primitive(PType.U8, false);

    private ExtensionTestSupport() {
    }

    static DType.Extension ext(String id, DType storage, ByteBuffer meta) {
        return new DType.Extension(id, storage, meta, false);
    }

    static ByteBuffer unitByte(byte tag) {
        ByteBuffer meta = ByteBuffer.allocate(1);
        meta.put(0, tag);
        return meta;
    }

    static ByteBuffer tzMeta(byte unitTag, String tz) {
        byte[] tzBytes = tz == null ? new byte[0] : tz.getBytes(StandardCharsets.UTF_8);
        ByteBuffer meta = ByteBuffer.allocate(3 + tzBytes.length).order(ByteOrder.LITTLE_ENDIAN);
        meta.put(0, unitTag);
        meta.putShort(1, (short) tzBytes.length);
        for (int k = 0; k < tzBytes.length; k++) {
            meta.put(3 + k, tzBytes[k]);
        }
        return meta;
    }

    static IntArray i32(Arena arena, int value) {
        MemorySegment buf = arena.allocate(4);
        buf.set(ValueLayout.JAVA_INT_UNALIGNED, 0, value);
        return new MaterializedIntArray(I32, 1, buf);
    }

    static LongArray i64(Arena arena, long value) {
        MemorySegment buf = arena.allocate(8);
        buf.set(ValueLayout.JAVA_LONG_UNALIGNED, 0, value);
        return new MaterializedLongArray(I64, 1, buf);
    }
}
