package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.ReadRegistry;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.nio.ByteBuffer;

/// Decoding context passed to each {@link EncodingDecoder}.
///
/// Buffers are {@link MemorySegment} slices materialized from the file's segment table;
/// children are decoded recursively via {@link #decodeChild(int)}.
/// The arena is scoped to one chunk epoch — all decode output allocated from it is
/// valid until the next chunk is opened.
///
/// @param node           the array node describing this encoding's tree structure
/// @param dtype          logical type expected for the decoded array
/// @param rowCount       number of logical rows to decode
/// @param segmentBuffers all segment buffers for the current flat segment, indexed by segment position
/// @param registry       read registry used for recursive child decoding
/// @param arena          allocator for decode output; lifetime matches the current chunk epoch
@SuppressWarnings("java:S6218") // internal data carrier; record components are arrays of immutable primitives or refs that flow through pipelines without ever being compared.
public record DecodeContext(
        ArrayNode node,
        DType dtype,
        long rowCount,
        MemorySegment[] segmentBuffers,
        ReadRegistry registry,
        SegmentAllocator arena
) {

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
    /// Use this overload when the child has a different logical type or length
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

    /// Returns the buffer at position {@code i} in this node's bufferIndices.
    ///
    /// @param i zero-based index into this node's {@code bufferIndices} array
    /// @return the {@link MemorySegment} for the referenced segment buffer
    public MemorySegment buffer(int i) {
        return segmentBuffers[node.bufferIndices()[i]];
    }

    /// Returns the encoding-specific metadata bytes for this node, or {@code null} if absent.
    ///
    /// @return the metadata {@link ByteBuffer}, or {@code null}
    public ByteBuffer metadata() {
        return node.metadata();
    }
}
