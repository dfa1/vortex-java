package io.github.dfa1.vortex.reader;

import static io.github.dfa1.vortex.core.io.VortexFormat.LE_INT;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.io.IoBounds;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.fbs.FbsBuffer;
import io.github.dfa1.vortex.reader.decode.ArrayNode;
import io.github.dfa1.vortex.reader.decode.DecodeContext;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.util.List;

/// Decodes one serialized array message — the wire unit a flat layout's segment stores — by
/// parsing its FlatBuffer scaffolding and dispatching to the appropriate decoder via the
/// [ReadRegistry].
///
/// Named after the Rust reference's `SerializedArray` (`vortex-array/src/serde.rs`): "a parsed
/// but not-yet-decoded deserialized array" whose `decode` resolves the encoding id against the
/// spec table and consults the registry — exactly this class's contract.
///
/// Serialized array wire format:
/// `buffer_data... | FlatBuffer(Array) | u32 LE = FlatBuffer byte length`
///
/// [ReadRegistry] is pure dispatch; this class owns all file-format knowledge:
/// FlatBuffer parsing, buffer-offset arithmetic, and encoding-spec lookup.
final class SerializedArrayDecoder {

    private final ReadRegistry registry;

    /// Creates a decoder backed by the given registry.
    ///
    /// @param registry the registry used to dispatch to concrete decoder impls
    public SerializedArrayDecoder(ReadRegistry registry) {
        this.registry = registry;
    }

    /// Decodes a flat segment from a memory-mapped region.
    ///
    /// @param seg           memory-mapped region for the flat segment
    /// @param encodingSpecs ordered list of [EncodingId] from the file's encoding table
    /// @param dtype         expected logical type for the decoded array
    /// @param rowCount      number of logical rows in this segment
    /// @param arena         allocator for decode output; lifetime matches the current chunk epoch
    /// @return the decoded [Array] for this segment
    public Array decode(MemorySegment seg, List<EncodingId> encodingSpecs,
            DType dtype, long rowCount, SegmentAllocator arena) {
        int segLen = IoBounds.toIntSize(seg.byteSize());

        // The trailing u32 length field must itself be in range before we read it.
        IoBounds.checkRange(segLen - 4L, 4, segLen);
        int fbLen = seg.get(LE_INT, segLen - 4L);
        long fbStart = segLen - 4L - fbLen;
        IoBounds.checkRange(fbStart, fbLen, segLen);

        // Verified whole on parse (FbsVerifier), so the accessors below stay inside it, every vector
        // fits in it, and its tables nest at most 64 deep.
        var fbArray = io.github.dfa1.vortex.core.fbs.FbsArray.getRootAsFbsArray(seg.asSlice(fbStart, fbLen));
        MemorySegment[] bufs = new MemorySegment[fbArray.buffersLength()];
        long dataOffset = 0;
        for (int i = 0; i < bufs.length; i++) {
            FbsBuffer bufDesc = fbArray.buffers(i);
            dataOffset += bufDesc.padding();
            bufs[i] = IoBounds.slice(seg, dataOffset, bufDesc.length());
            dataOffset += bufDesc.length();
        }
        var root = fbArray.root();
        if (root == null) {
            throw new VortexException("array flatbuffer missing root");
        }

        var ctx = new DecodeContext(convertArrayNode(root, encodingSpecs), dtype, rowCount, bufs, registry, arena);
        return registry.decode(ctx);
    }

    private static ArrayNode convertArrayNode(io.github.dfa1.vortex.core.fbs.FbsArrayNode fbs,
            List<EncodingId> encodingSpecs) {
        int encoding = fbs.encoding();
        if (encoding >= encodingSpecs.size()) {
            throw new VortexException("array encoding index " + encoding + " out of range [0, "
                    + encodingSpecs.size() + ")");
        }
        // Already parsed (and blank-guarded) at the footer boundary.
        EncodingId encodingId = encodingSpecs.get(encoding);

        ArrayNode[] children = new ArrayNode[fbs.childrenLength()];
        for (int i = 0; i < children.length; i++) {
            children[i] = convertArrayNode(fbs.children(i), encodingSpecs);
        }

        int[] bufferIndices = new int[fbs.buffersLength()];
        for (int i = 0; i < bufferIndices.length; i++) {
            bufferIndices[i] = fbs.buffers(i);
        }

        MemorySegment meta = fbs.metadataAsSegment();
        return new ArrayNode(encodingId, meta, children, bufferIndices);
    }
}
