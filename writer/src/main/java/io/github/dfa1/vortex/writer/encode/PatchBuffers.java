package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.compute.PrimitiveArrays;
import io.github.dfa1.vortex.core.io.PTypeIO;
import io.github.dfa1.vortex.core.io.VortexFormat;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.proto.ProtoPType;
import io.github.dfa1.vortex.core.proto.ProtoPatchesMetadata;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.List;
import java.util.Optional;

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

    /// Lays out the patches of an array of `length` values, `positionArray` being ascending.
    ///
    /// @param positionArray the positions of the patched values, ascending, at least one
    /// @param length        the number of values in the array being patched
    /// @param ctx           the context supplying the arena
    /// @return the buffers, with chunk offsets
    /// @throws IllegalArgumentException if there are no positions: an array without patches has no
    ///                                  patch children at all
    static PatchBuffers of(long[] positionArray, int length, EncodeContext ctx) {
        if (positionArray.length == 0) {
            throw new IllegalArgumentException("no patches to lay out");
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

    /// Appends the patches to `buffers` as the three children Rust writes them as: indices,
    /// values (see [#valuesNode(Object, PType, List, EncodeContext)]) and chunk offsets.
    ///
    /// @param values  the patch values, as [#valuesNode(Object, PType, List, EncodeContext)] takes them
    /// @param ptype   the values' type
    /// @param buffers the node's buffers so far, appended to
    /// @param ctx     the context supplying the arena
    /// @return the indices, values and chunk-offsets children, pointing at the buffers appended
    EncodeNode[] append(Object values, PType ptype, List<EncodedBuffer> buffers, EncodeContext ctx) {
        EncodeNode indicesNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, buffers.size());
        buffers.add(EncodedBuffer.of(indices, indexType));
        EncodeNode valuesNode = valuesNode(values, ptype, buffers, ctx);
        EncodeNode offsetsNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, buffers.size());
        buffers.add(EncodedBuffer.of(offsets, offsetType));
        return new EncodeNode[]{indicesNode, valuesNode, offsetsNode};
    }

    /// Appends the patch values to `buffers` as Rust's `compress_patches` leaves them: a
    /// `vortex.constant` when every value is equal, a `vortex.primitive` otherwise.
    ///
    /// @param values  the patch values: a primitive array of `ptype`, or for an integer `ptype` its
    ///                values widened to a `long[]` as `PrimitiveArrays.toLongs` widens them
    /// @param ptype   the values' type
    /// @param buffers the node's buffers so far, appended to
    /// @param ctx     the context supplying the arena
    /// @return the values child, pointing at the buffers it appended
    static EncodeNode valuesNode(Object values, PType ptype, List<EncodedBuffer> buffers, EncodeContext ctx) {
        int first = buffers.size();
        boolean widened = values instanceof long[] && ptype.isInteger();
        Optional<EncodeResult> constant = widened
                ? ConstantEncodingEncoder.encodeIfConstantWidened(ptype, (long[]) values)
                : ConstantEncodingEncoder.encodeIfConstant(ptype, values);
        if (constant.isPresent()) {
            buffers.addAll(constant.get().encodedBuffers());
            return EncodeNode.remapBufferIndices(constant.get().rootNode(), first);
        }
        MemorySegment segment;
        if (widened) {
            segment = narrowed((long[]) values, ptype, ctx);
        } else {
            segment = PrimitiveArrays.toSegment(values, ptype, ctx.arena());
        }
        buffers.add(EncodedBuffer.of(segment, ptype));
        return EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, first);
    }

    // Writes widened integers back at their own width, in one copy.
    private static MemorySegment narrowed(long[] values, PType ptype, EncodeContext ctx) {
        int width = ptype.byteSize();
        MemorySegment segment = ctx.arena().allocate((long) values.length * width, width);
        for (int i = 0; i < values.length; i++) {
            PTypeIO.set(segment, (long) i * width, ptype, values[i]);
        }
        return segment;
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
