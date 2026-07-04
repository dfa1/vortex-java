package io.github.dfa1.vortex.reader.extension;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.reader.array.IntArray;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.array.MaterializedIntArray;
import io.github.dfa1.vortex.reader.array.MaterializedLongArray;

import static io.github.dfa1.vortex.core.io.VortexFormat.LE_SHORT;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

/// Shared fixtures for the per-extension decoder test classes.
final class ExtensionTestSupport {

    static final DType.Primitive I32 = DType.I32;
    static final DType.Primitive I64 = DType.I64;
    static final DType.Primitive U8 = DType.U8;

    private ExtensionTestSupport() {
    }

    static DType.Extension ext(String id, DType storage, MemorySegment meta) {
        return new DType.Extension(id, storage, meta, false);
    }

    static MemorySegment unitByte(byte tag) {
        return MemorySegment.ofArray(new byte[]{tag});
    }

    static MemorySegment tzMeta(byte unitTag, String tz) {
        byte[] tzBytes = tz == null ? new byte[0] : tz.getBytes(StandardCharsets.UTF_8);
        MemorySegment meta = MemorySegment.ofArray(new byte[3 + tzBytes.length]);
        meta.set(ValueLayout.JAVA_BYTE, 0, unitTag);
        meta.set(LE_SHORT, 1, (short) tzBytes.length);
        MemorySegment.copy(tzBytes, 0, meta, ValueLayout.JAVA_BYTE, 3, tzBytes.length);
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
