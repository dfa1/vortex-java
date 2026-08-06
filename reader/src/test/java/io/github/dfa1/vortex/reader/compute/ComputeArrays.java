package io.github.dfa1.vortex.reader.compute;

import io.github.dfa1.vortex.core.io.VortexFormat;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.reader.array.ByteArray;
import io.github.dfa1.vortex.reader.array.ChunkedLongArray;
import io.github.dfa1.vortex.reader.array.DictLongArray;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.MaterializedBoolArray;
import io.github.dfa1.vortex.reader.array.MaterializedByteArray;
import io.github.dfa1.vortex.reader.array.MaterializedDoubleArray;
import io.github.dfa1.vortex.reader.array.MaterializedLongArray;
import io.github.dfa1.vortex.reader.array.VarBinArray;
import io.github.dfa1.vortex.reader.array.VarBinOffsetArray;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.List;

/// Builds the small in-memory arrays the compute tests fold over. Off-heap from a shared [Arena] so
/// the arrays look exactly like the zero-copy slices the reader hands the kernels at scan time.
final class ComputeArrays {

    private ComputeArrays() {
    }

    /// Builds a buffer-backed `i64` array from the given values.
    ///
    /// @param arena  the allocator for the off-heap buffer
    /// @param values the element values
    /// @return a [LongArray] of [DType#I64]
    static MaterializedLongArray longArray(Arena arena, long... values) {
        return new MaterializedLongArray(DType.I64, values.length, longSegment(arena, values));
    }

    /// Builds a buffer-backed `u64` array from the given raw 64-bit values.
    ///
    /// @param arena  the allocator for the off-heap buffer
    /// @param values the raw element bits
    /// @return a [LongArray] of [DType#U64]
    static MaterializedLongArray unsignedLongArray(Arena arena, long... values) {
        return new MaterializedLongArray(DType.U64, values.length, longSegment(arena, values));
    }

    /// Builds a buffer-backed `f64` array from the given values.
    ///
    /// @param arena  the allocator for the off-heap buffer
    /// @param values the element values
    /// @return a [MaterializedDoubleArray] of [DType#F64]
    static MaterializedDoubleArray doubleArray(Arena arena, double... values) {
        MemorySegment seg = arena.allocate(Math.max(1, values.length * 8L), 8);
        for (int i = 0; i < values.length; i++) {
            seg.setAtIndex(VortexFormat.LE_DOUBLE, i, values[i]);
        }
        return new MaterializedDoubleArray(DType.F64, values.length, seg);
    }

    /// Wraps an `i64` child with a validity bitmap, the only array family that carries nulls.
    ///
    /// @param arena  the allocator for the off-heap buffers
    /// @param values the element values (the value at a null position is ignored by the kernels)
    /// @param valid  per-position validity, parallel to `values`
    /// @return a [MaskedArray] over an [DType#I64] child
    static MaskedArray maskedLongArray(Arena arena, long[] values, boolean[] valid) {
        return new MaskedArray(longArray(arena, values), boolArray(arena, valid));
    }

    /// Builds a dictionary-encoded `i64` array: a pool of distinct values indexed by per-row `u8`
    /// codes. Decodes to `pool[codes[i]]` at each row.
    ///
    /// @param arena the allocator for the off-heap buffers
    /// @param pool  the dictionary values
    /// @param codes the per-row indices into `pool`
    /// @return a [DictLongArray] of [DType#I64]
    static DictLongArray dictLongArray(Arena arena, long[] pool, int[] codes) {
        LongArray values = longArray(arena, pool);
        ByteArray codeArray = byteArray(arena, codes);
        return DictLongArray.of(DType.I64, codes.length, values, codeArray);
    }

    /// Builds a chunked `i64` array by concatenating the given chunks.
    ///
    /// @param arena  the allocator for the off-heap buffers
    /// @param chunks the chunk value arrays, in order
    /// @return a [ChunkedLongArray] of [DType#I64]
    static ChunkedLongArray chunkedLongArray(Arena arena, long[]... chunks) {
        long total = 0;
        var children = new java.util.ArrayList<LongArray>(chunks.length);
        for (long[] chunk : chunks) {
            children.add(longArray(arena, chunk));
            total += chunk.length;
        }
        return ChunkedLongArray.of(DType.I64, total, List.copyOf(children));
    }

    /// Builds a bit-packed boolean array (LSB-first) used as a [MaskedArray] validity bitmap.
    ///
    /// @param arena  the allocator for the off-heap buffer
    /// @param values the boolean values
    /// @return a [MaterializedBoolArray] of [DType#BOOL]
    static MaterializedBoolArray boolArray(Arena arena, boolean... values) {
        long bytes = Math.max(1, (values.length + 7) >>> 3);
        MemorySegment seg = arena.allocate(bytes);
        for (int i = 0; i < values.length; i++) {
            if (values[i]) {
                long byteIndex = i >>> 3;
                int existing = seg.get(ValueLayout.JAVA_BYTE, byteIndex) & 0xff;
                seg.set(ValueLayout.JAVA_BYTE, byteIndex, (byte) (existing | (1 << (i & 7))));
            }
        }
        return new MaterializedBoolArray(DType.BOOL, values.length, seg);
    }

    /// Builds an offset-based Utf8 [VarBinArray] from the given strings, off-heap so it looks like
    /// the zero-copy slices the reader hands the kernels. Exercises the [VarBinOffsetArray]
    /// accessor path the string compute kernels fold over.
    ///
    /// @param arena  the allocator for the off-heap bytes and offsets buffers
    /// @param values the element strings, in row order
    /// @return a [VarBinArray] of [DType#UTF8]
    static VarBinArray utf8Array(Arena arena, String... values) {
        byte[] allBytes = String.join("", values).getBytes(StandardCharsets.UTF_8);
        MemorySegment bytes = arena.allocate(Math.max(1, allBytes.length));
        MemorySegment.copy(MemorySegment.ofArray(allBytes), 0, bytes, 0, allBytes.length);
        MemorySegment offsets = arena.allocate((values.length + 1) * Integer.BYTES, Integer.BYTES);
        int running = 0;
        offsets.setAtIndex(VortexFormat.LE_INT, 0, 0);
        for (int i = 0; i < values.length; i++) {
            running += values[i].getBytes(StandardCharsets.UTF_8).length;
            offsets.setAtIndex(VortexFormat.LE_INT, i + 1, running);
        }
        return new VarBinOffsetArray(DType.UTF8, values.length, bytes.asReadOnly(),
                offsets, PType.I32);
    }

    private static MemorySegment longSegment(Arena arena, long... values) {
        MemorySegment seg = arena.allocate(Math.max(1, values.length * 8L), 8);
        for (int i = 0; i < values.length; i++) {
            seg.setAtIndex(VortexFormat.LE_LONG, i, values[i]);
        }
        return seg;
    }

    private static MaterializedByteArray byteArray(Arena arena, int... values) {
        MemorySegment seg = arena.allocate(Math.max(1, values.length));
        for (int i = 0; i < values.length; i++) {
            seg.set(ValueLayout.JAVA_BYTE, i, (byte) values[i]);
        }
        return new MaterializedByteArray(DType.U8, values.length, seg);
    }
}
