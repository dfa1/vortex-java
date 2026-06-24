package io.github.dfa1.vortex.reader;

import static io.github.dfa1.vortex.encoding.PTypeIO.LE_INT;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.IoBounds;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.encoding.EncodingId;
import io.github.dfa1.vortex.fbs.FbsBuffer;
import io.github.dfa1.vortex.reader.decode.ArrayNode;
import io.github.dfa1.vortex.reader.decode.DecodeContext;
import io.github.dfa1.vortex.reader.decode.KnownArrayNode;
import io.github.dfa1.vortex.reader.decode.UnknownArrayNode;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.util.List;

/// Parses a flat segment from the memory-mapped file region and dispatches to the
/// appropriate decoder via the [ReadRegistry].
///
/// Flat segment wire format:
/// `buffer_data... | FlatBuffer(Array) | u32 LE = FlatBuffer byte length`
///
/// [ReadRegistry] is pure dispatch; this class owns all file-format knowledge:
/// FlatBuffer parsing, buffer-offset arithmetic, and encoding-spec lookup.
public final class FlatSegmentDecoder {

    private final ReadRegistry registry;

    /// Creates a decoder backed by the given registry.
    ///
    /// @param registry the registry used to dispatch to concrete decoder impls
    public FlatSegmentDecoder(ReadRegistry registry) {
        this.registry = registry;
    }

    /// Decodes a flat segment from a memory-mapped region.
    ///
    /// @param seg           memory-mapped region for the flat segment
    /// @param encodingSpecs ordered list of encoding id strings from the file's encoding table
    /// @param dtype         expected logical type for the decoded array
    /// @param rowCount      number of logical rows in this segment
    /// @param arena         allocator for decode output; lifetime matches the current chunk epoch
    /// @return the decoded [Array] for this segment
    public Array decode(MemorySegment seg, List<String> encodingSpecs,
            DType dtype, long rowCount, SegmentAllocator arena) {
        int segLen = IoBounds.toIntSize(seg.byteSize());

        // The trailing u32 length field must itself be in range before we read it.
        IoBounds.checkRange(segLen - 4L, 4, segLen);
        int fbLen = seg.get(LE_INT, segLen - 4);
        long fbStart = segLen - 4L - fbLen;
        IoBounds.checkRange(fbStart, fbLen, segLen);
        var fbArray = io.github.dfa1.vortex.fbs.FbsArray.getRootAsFbsArray(seg.asSlice(fbStart, fbLen));

        int numBuffers = IoBounds.checkCount(fbArray.buffersLength());
        MemorySegment[] bufs = new MemorySegment[numBuffers];
        long dataOffset = 0;
        for (int i = 0; i < numBuffers; i++) {
            FbsBuffer bufDesc = fbArray.buffers(i);
            dataOffset += bufDesc.padding();
            bufs[i] = IoBounds.slice(seg, dataOffset, bufDesc.length());
            dataOffset += bufDesc.length();
        }

        ArrayNode rootNode = convertArrayNode(fbArray.root(), encodingSpecs);
        var ctx = new DecodeContext(rootNode, dtype, rowCount, bufs, registry, arena);
        return registry.decode(ctx);
    }

    private static ArrayNode convertArrayNode(
            io.github.dfa1.vortex.fbs.FbsArrayNode fbs,
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

        MemorySegment meta = fbs.metadataAsSegment();
        return EncodingId.parse(rawEncodingId)
                .<ArrayNode>map(known -> new KnownArrayNode(known, meta, children, bufferIndices))
                .orElseGet(() -> new UnknownArrayNode(rawEncodingId, meta, children, bufferIndices));
    }
}
