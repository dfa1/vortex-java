package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.core.BoundedSegment;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.array.Array;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.nio.ByteBuffer;

/// Decoding context passed to each [Encoding].
///
/// Buffers are `MemorySegment` slices materialized from the file's segment table;
/// children are decoded recursively via [#decodeChild(int)].
/// The arena is scoped to one chunk epoch — all decode output allocated from it is
/// valid until the next chunk is opened.
///
/// @param node           the array node describing this encoding's tree structure
/// @param dtype          logical type expected for the decoded array
/// @param rowCount       number of logical rows to decode
/// @param segmentBuffers all segment buffers for the current flat segment, indexed by segment position
/// @param registry       encoding registry used for recursive child decoding
/// @param arena          allocator for decode output; lifetime matches the current chunk epoch
public record DecodeContext(
        ArrayNode node,
        DType dtype,
        long rowCount,
        BoundedSegment[] segmentBuffers,
        Registry registry,
        SegmentAllocator arena
) {
    /// Convenience factory that wraps raw {@link MemorySegment} buffers as {@link BoundedSegment}s
    /// for tests and other callers that produce synthetic, trusted buffer arrays. Production
    /// decoders receive their buffers from {@link FlatSegmentDecoder}, which already wraps them
    /// against the parent flat segment.
    ///
    /// @param node      array node describing this encoding's tree structure
    /// @param dtype     logical type expected for the decoded array
    /// @param rowCount  number of logical rows to decode
    /// @param rawBufs   raw segment buffers; each wrapped as {@code "test buffer i"}
    /// @param registry  encoding registry used for recursive child decoding
    /// @param arena     allocator for decode output
    /// @return a {@link DecodeContext} backed by bounded views of {@code rawBufs}
    public static DecodeContext ofRawBuffers(
            ArrayNode node, DType dtype, long rowCount,
            MemorySegment[] rawBufs, Registry registry, SegmentAllocator arena) {
        BoundedSegment[] wrapped = new BoundedSegment[rawBufs.length];
        for (int i = 0; i < rawBufs.length; i++) {
            wrapped[i] = new BoundedSegment(rawBufs[i], "test buffer " + i);
        }
        return new DecodeContext(node, dtype, rowCount, wrapped, registry, arena);
    }

    /// Recursively decode child {@code i} using this context's dtype and row count.
    ///
    /// @param i zero-based child index within this node's children array
    /// @return the decoded {@link Array} for child {@code i}
    public Array decodeChild(int i) {
        ArrayNode child = node.children()[i];
        var childCtx = new DecodeContext(child, dtype, rowCount, segmentBuffers, registry, arena);
        return registry.decode(childCtx);
    }

    /// Recursively decode child {@code i} with an explicit dtype and row count.
    ///
    /// <p>Use this overload when the child has a different logical type or length
    /// than the parent (e.g. run-end arrays, patch children, validity bitmaps).
    ///
    /// @param i        zero-based child index within this node's children array
    /// @param dtype    logical type to assign to the child context
    /// @param rowCount number of logical rows for the child
    /// @return the decoded {@link Array} for child {@code i}
    public Array decodeChild(int i, DType dtype, long rowCount) {
        ArrayNode child = node.children()[i];
        var childCtx = new DecodeContext(child, dtype, rowCount, segmentBuffers, registry, arena);
        return registry.decode(childCtx);
    }

    /// Recursively decode child {@code i} and return its primary backing segment.
    ///
    /// @param i zero-based child index within this node's children array
    /// @return the primary {@link MemorySegment} of the decoded child
    public MemorySegment decodeChildSegment(int i) {
        ArrayNode child = node.children()[i];
        var childCtx = new DecodeContext(child, dtype, rowCount, segmentBuffers, registry, arena);
        return registry.decodeAsSegment(childCtx);
    }

    /// Recursively decode child {@code i} with an explicit dtype and row count, returning its primary segment.
    ///
    /// @param i        zero-based child index within this node's children array
    /// @param dtype    logical type to assign to the child context
    /// @param rowCount number of logical rows for the child
    /// @return the primary {@link MemorySegment} of the decoded child
    public MemorySegment decodeChildSegment(int i, DType dtype, long rowCount) {
        ArrayNode child = node.children()[i];
        var childCtx = new DecodeContext(child, dtype, rowCount, segmentBuffers, registry, arena);
        return registry.decodeAsSegment(childCtx);
    }

    /// Return the buffer at position `i` in this node's bufferIndices.
    ///
    /// @param i zero-based index into this node's {@code bufferIndices} array
    /// @return the {@link BoundedSegment} for the referenced segment buffer
    public BoundedSegment buffer(int i) {
        return segmentBuffers[node.bufferIndices()[i]];
    }

    /// Returns the encoding-specific metadata bytes for this node, or {@code null} if absent.
    ///
    /// @return the metadata {@link java.nio.ByteBuffer}, or {@code null}
    public ByteBuffer metadata() {
        return node.metadata();
    }
}
