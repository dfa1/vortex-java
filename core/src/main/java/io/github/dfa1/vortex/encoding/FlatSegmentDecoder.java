package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.ArrayStats;
import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.fbs.Buffer;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/// Parses a flat segment from the memory-mapped file region and dispatches to the
/// appropriate {@link Encoding} via the registry.
///
/// <p>Flat segment wire format:
/// {@code buffer_data... | FlatBuffer(Array) | u32 LE = FlatBuffer byte length}
///
/// <p>Registry is pure dispatch ({@code register}/{@code decode}); this class owns
/// all file-format knowledge: FlatBuffer parsing, buffer-offset arithmetic, and
/// encoding-spec lookup.
public final class FlatSegmentDecoder {

    private final Registry registry;

    /// Creates a decoder backed by the given registry.
    ///
    /// @param registry the registry used to dispatch to concrete {@link Encoding} impls
    public FlatSegmentDecoder(Registry registry) {
        this.registry = registry;
    }

    /// Decodes a flat segment from a memory-mapped region.
    ///
    /// @param seg           memory-mapped region for the flat segment
    /// @param encodingSpecs ordered list of encoding id strings from the file's encoding table
    /// @param dtype         expected logical type for the decoded array
    /// @param rowCount      number of logical rows in this segment
    /// @param arena         allocator for decode output; lifetime matches the current chunk epoch
    /// @return the decoded {@link Array} for this segment
    public Array decode(MemorySegment seg, List<String> encodingSpecs,
            DType dtype, long rowCount, SegmentAllocator arena) {
        int segLen = (int) seg.byteSize();
        ByteBuffer bb = seg.asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);

        int fbLen = bb.getInt(segLen - 4);
        int fbStart = segLen - 4 - fbLen;
        ByteBuffer fbBuf = bb.slice(fbStart, fbLen).order(ByteOrder.LITTLE_ENDIAN);
        var fbArray = io.github.dfa1.vortex.fbs.Array.getRootAsArray(fbBuf);

        int numBuffers = fbArray.buffersLength();
        MemorySegment[] bufs = new MemorySegment[numBuffers];
        long dataOffset = 0;
        for (int i = 0; i < numBuffers; i++) {
            Buffer bufDesc = fbArray.buffers(i);
            dataOffset += bufDesc.padding();
            bufs[i] = seg.asSlice(dataOffset, bufDesc.length());
            dataOffset += bufDesc.length();
        }

        ArrayNode rootNode = convertArrayNode(fbArray.root(), encodingSpecs);
        var ctx = new DecodeContext(rootNode, dtype, rowCount, bufs, registry, arena);
        return registry.decode(ctx);
    }

    private static ArrayNode convertArrayNode(
            io.github.dfa1.vortex.fbs.ArrayNode fbs,
            List<String> encodingSpecs
    ) {
        String rawEncodingId = encodingSpecs.get(fbs.encoding());

        ArrayNode[] children = new ArrayNode[fbs.childrenLength()];
        for (int i = 0; i < children.length; i++) {
            children[i] = convertArrayNode(fbs.children(i), encodingSpecs);
        }

        int[] bufferIndices = new int[fbs.buffersLength()];
        for (int i = 0; i < bufferIndices.length; i++) {
            bufferIndices[i] = fbs.buffers(i);
        }

        // metadataAsByteBuffer() returns duplicate with position=vectorStart; slice to normalize to 0
        ByteBuffer rawMeta = fbs.metadataAsByteBuffer();
        ByteBuffer meta = (rawMeta != null) ? rawMeta.slice() : null;
        ArrayStats stats = ArrayStats.fromFbs(fbs.stats());
        EncodingId known = EncodingId.tryFrom(rawEncodingId);
        if (known != null) {
            return new KnownArrayNode(known, meta, children, bufferIndices, stats);
        }
        return new UnknownArrayNode(rawEncodingId, meta, children, bufferIndices, stats);
    }
}
