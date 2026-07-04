package io.github.dfa1.vortex.reader;

import static io.github.dfa1.vortex.core.io.PTypeIO.LE_INT;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.io.IoBounds;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.core.fbs.FbsBuffer;
import io.github.dfa1.vortex.reader.decode.ArrayNode;
import io.github.dfa1.vortex.reader.decode.DecodeContext;

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

    /// Hard cap on array-node recursion depth. The encoded array tree nests through child nodes
    /// (validity, patches, run-ends, dictionary codes/values, …); a crafted or self-referential
    /// FlatBuffer can drive [#convertArrayNode] into unbounded recursion and a [StackOverflowError]
    /// — an `Error`, so it would bypass the [VortexException]
    /// contract. 64 is well past any real encoding's nesting.
    static final int MAX_ARRAY_TREE_DEPTH = 64;

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
        int fbLen = seg.get(LE_INT, segLen - 4L);
        long fbStart = segLen - 4L - fbLen;
        IoBounds.checkRange(fbStart, fbLen, segLen);
        var fbArray = io.github.dfa1.vortex.core.fbs.FbsArray.getRootAsFbsArray(seg.asSlice(fbStart, fbLen));

        int numBuffers = IoBounds.checkCount(fbArray.buffersLength());
        MemorySegment[] bufs = new MemorySegment[numBuffers];
        long dataOffset = 0;
        for (int i = 0; i < numBuffers; i++) {
            FbsBuffer bufDesc = fbArray.buffers(i);
            dataOffset += bufDesc.padding();
            bufs[i] = IoBounds.slice(seg, dataOffset, bufDesc.length());
            dataOffset += bufDesc.length();
        }

        ArrayNode rootNode = convertArrayNode(fbArray.root(), encodingSpecs, 0);
        var ctx = new DecodeContext(rootNode, dtype, rowCount, bufs, registry, arena);
        return registry.decode(ctx);
    }

    private static ArrayNode convertArrayNode(
            io.github.dfa1.vortex.core.fbs.FbsArrayNode fbs,
            List<String> encodingSpecs,
            int depth
    ) {
        if (depth > MAX_ARRAY_TREE_DEPTH) {
            throw new VortexException(
                    "array tree depth exceeds limit (" + MAX_ARRAY_TREE_DEPTH + ")");
        }
        String rawEncodingId = encodingSpecs.get(fbs.encoding());

        ArrayNode[] children = new ArrayNode[fbs.childrenLength()];
        for (int i = 0; i < children.length; i++) {
            children[i] = convertArrayNode(fbs.children(i), encodingSpecs, depth + 1);
        }

        int[] bufferIndices = new int[fbs.buffersLength()];
        for (int i = 0; i < bufferIndices.length; i++) {
            bufferIndices[i] = fbs.buffers(i);
        }

        MemorySegment meta = fbs.metadataAsSegment();
        return new ArrayNode(rawEncodingId, meta, children, bufferIndices);
    }
}
