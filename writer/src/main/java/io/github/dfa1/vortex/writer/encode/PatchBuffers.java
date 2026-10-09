package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.io.VortexFormat;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.proto.ProtoPType;
import io.github.dfa1.vortex.core.proto.ProtoPatchesMetadata;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.List;

/// The index and chunk-offset buffers of an encoding's patches, laid out as Rust's
/// `compress_patches` (`vortex-btrblocks`) leaves them: positions and offsets narrowed to the
/// smallest unsigned type that holds the largest, and nothing bit-packed.
///
/// @param indexType   the type the patch positions are narrowed to
/// @param indices     the patch positions, in [#indexType]
/// @param offsetType  the type the chunk offsets are narrowed to
/// @param offsets     for each [#CHUNK_SIZE]-value chunk, the number of patches before it
/// @param chunkCount  the number of entries in [#offsets]
record PatchBuffers(PType indexType, MemorySegment indices, PType offsetType, MemorySegment offsets,
                    int chunkCount) {

    /// Values per chunk of patch offsets (Rust's `ENCODE_CHUNK_SIZE`, one FastLanes block).
    static final int CHUNK_SIZE = 1024;

    /// Lays out the patches of an array of `length` values, `positions` being ascending.
    ///
    /// @param positions the positions of the patched values, ascending
    /// @param length    the number of values in the array being patched
    /// @param ctx       the context supplying the arena
    /// @return the buffers, with chunk offsets
    static PatchBuffers of(List<Integer> positions, int length, EncodeContext ctx) {
        long[] positionArray = new long[positions.size()];
        for (int i = 0; i < positionArray.length; i++) {
            positionArray[i] = positions.get(i);
        }
        int chunkCount = (length + CHUNK_SIZE - 1) / CHUNK_SIZE;
        long[] offsetArray = new long[chunkCount];
        int next = 0;
        for (int chunk = 0; chunk < chunkCount; chunk++) {
            while (next < positionArray.length && positionArray[next] < (long) chunk * CHUNK_SIZE) {
                next++;
            }
            offsetArray[chunk] = next;
        }
        PType indexType = narrowestUnsigned(positionArray[positionArray.length - 1]);
        PType offsetType = narrowestUnsigned(offsetArray[chunkCount - 1]);
        return new PatchBuffers(indexType, unsigned(positionArray, indexType, ctx),
                offsetType, unsigned(offsetArray, offsetType, ctx), chunkCount);
    }

    /// The patches metadata for `count` patches.
    ///
    /// @param count the number of patches
    /// @return the metadata, naming the narrowed types
    ProtoPatchesMetadata meta(int count) {
        return new ProtoPatchesMetadata(count, 0L, ProtoPType.fromValue(indexType.ordinal()),
                (long) chunkCount, ProtoPType.fromValue(offsetType.ordinal()), 0L);
    }

    /// The smallest unsigned type that holds `max`.
    ///
    /// @param max the largest value to hold
    /// @return U8, U16, U32 or U64
    static PType narrowestUnsigned(long max) {
        if (max >= 0 && max <= 0xFFL) {
            return PType.U8;
        }
        if (max >= 0 && max <= 0xFFFFL) {
            return PType.U16;
        }
        return max >= 0 && max <= 0xFFFF_FFFFL ? PType.U32 : PType.U64;
    }

    /// Copies `values` into a little-endian buffer of `type`.
    ///
    /// @param values the values to store
    /// @param type   the unsigned type to store them as
    /// @param ctx    the context supplying the arena
    /// @return the buffer, aligned to `type`
    static MemorySegment unsigned(long[] values, PType type, EncodeContext ctx) {
        int width = type.byteSize();
        MemorySegment buffer = ctx.arena().allocate((long) values.length * width, width);
        for (int i = 0; i < values.length; i++) {
            switch (type) {
                case U8 -> buffer.set(ValueLayout.JAVA_BYTE, i, (byte) values[i]);
                case U16 -> buffer.setAtIndex(VortexFormat.LE_SHORT, i, (short) values[i]);
                case U32 -> buffer.setAtIndex(VortexFormat.LE_INT, i, (int) values[i]);
                default -> buffer.setAtIndex(VortexFormat.LE_LONG, i, values[i]);
            }
        }
        return buffer;
    }
}
