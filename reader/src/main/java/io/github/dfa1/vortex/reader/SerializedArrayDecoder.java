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

    /// Hard cap on array-node recursion depth. The encoded array tree nests through child nodes
    /// (validity, patches, run-ends, dictionary codes/values, …); a crafted or self-referential
    /// FlatBuffer can drive [#convertArrayNode] into unbounded recursion and a [StackOverflowError]
    /// — an `Error`, so it would bypass the [VortexException]
    /// contract. 64 is well past any real encoding's nesting.
    static final int MAX_ARRAY_TREE_DEPTH = 64;

    /// Wire size of the `Buffer` struct in `array.fbs` (u16 padding, u8 alignment, u8 compression,
    /// u32 length).
    private static final int BUFFER_STRUCT_BYTES = 8;

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

        MemorySegment[] bufs;
        ArrayNode rootNode;
        // The FlatBuffer runtime trusts the offsets it follows (see FbsTable): a crafted vtable or
        // vector offset fails inside it as a raw IndexOutOfBoundsException, so the whole parse is
        // framed here, as PostscriptParser frames the footer blobs.
        try {
            var fbArray = io.github.dfa1.vortex.core.fbs.FbsArray.getRootAsFbsArray(seg.asSlice(fbStart, fbLen));
            int numBuffers = vectorCount(fbArray.buffersLength(), BUFFER_STRUCT_BYTES, fbLen);
            bufs = new MemorySegment[numBuffers];
            long dataOffset = 0;
            for (int i = 0; i < numBuffers; i++) {
                FbsBuffer bufDesc = fbArray.buffers(i);
                dataOffset += bufDesc.padding();
                bufs[i] = IoBounds.slice(seg, dataOffset, bufDesc.length());
                dataOffset += bufDesc.length();
            }
            rootNode = convertArrayNode(fbArray.root(), encodingSpecs, fbLen, 0);
        } catch (VortexException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new VortexException("malformed array flatbuffer", e);
        }

        var ctx = new DecodeContext(rootNode, dtype, rowCount, bufs, registry, arena);
        return registry.decode(ctx);
    }

    private static ArrayNode convertArrayNode(
            io.github.dfa1.vortex.core.fbs.FbsArrayNode fbs,
            List<EncodingId> encodingSpecs,
            int fbLen,
            int depth
    ) {
        if (depth > MAX_ARRAY_TREE_DEPTH) {
            throw new VortexException(
                    "array tree depth exceeds limit (" + MAX_ARRAY_TREE_DEPTH + ")");
        }
        int encoding = fbs.encoding();
        if (encoding >= encodingSpecs.size()) {
            throw new VortexException("array encoding index " + encoding + " out of range [0, "
                    + encodingSpecs.size() + ")");
        }
        // Already parsed (and blank-guarded) at the footer boundary.
        EncodingId encodingId = encodingSpecs.get(encoding);

        ArrayNode[] children = new ArrayNode[vectorCount(fbs.childrenLength(), Integer.BYTES, fbLen)];
        for (int i = 0; i < children.length; i++) {
            children[i] = convertArrayNode(fbs.children(i), encodingSpecs, fbLen, depth + 1);
        }

        int[] bufferIndices = new int[vectorCount(fbs.buffersLength(), Short.BYTES, fbLen)];
        for (int i = 0; i < bufferIndices.length; i++) {
            bufferIndices[i] = fbs.buffers(i);
        }

        MemorySegment meta = fbs.metadataAsSegment();
        return new ArrayNode(encodingId, meta, children, bufferIndices);
    }

    /// Checks a FlatBuffer vector's claimed length before it sizes a Java array: the elements must
    /// fit in the FlatBuffer, else a crafted length near [Integer#MAX_VALUE] is an
    /// [OutOfMemoryError] (an `Error`, past the [VortexException] framing) before any element is read.
    private static int vectorCount(int count, int elementBytes, int fbLen) {
        if (count < 0 || (long) count * elementBytes > fbLen) {
            throw new VortexException("array flatbuffer vector of " + count + " elements exceeds its "
                    + fbLen + "-byte flatbuffer");
        }
        return count;
    }
}
