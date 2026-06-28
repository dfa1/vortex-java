package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.ReadRegistry;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;

/// Decoding context passed to each [EncodingDecoder].
///
/// Buffers are [MemorySegment] slices materialized from the file's segment table;
/// children are decoded recursively via [#decodeChild(int)].
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

    /// Recursively decode child `i` using this context's dtype and row count.
    ///
    /// @param i zero-based child index within this node's children array
    /// @return the decoded [Array] for child `i`
    public Array decodeChild(int i) {
        ArrayNode child = node.children()[i];
        var childCtx = new DecodeContext(child, dtype, rowCount, segmentBuffers, registry, arena);
        return registry.decode(childCtx);
    }

    /// Recursively decode child `i` with an explicit dtype and row count.
    ///
    /// Use this overload when the child has a different logical type or length
    /// than the parent (e.g. run-end arrays, patch children, validity bitmaps).
    ///
    /// @param i        zero-based child index within this node's children array
    /// @param dtype    logical type to assign to the child context
    /// @param rowCount number of logical rows for the child
    /// @return the decoded [Array] for child `i`
    public Array decodeChild(int i, DType dtype, long rowCount) {
        ArrayNode child = node.children()[i];
        var childCtx = new DecodeContext(child, dtype, rowCount, segmentBuffers, registry, arena);
        return registry.decode(childCtx);
    }

    /// Recursively decode child `i` and return its primary backing segment.
    ///
    /// @param i zero-based child index within this node's children array
    /// @return the primary [MemorySegment] of the decoded child
    public MemorySegment decodeChildSegment(int i) {
        ArrayNode child = node.children()[i];
        var childCtx = new DecodeContext(child, dtype, rowCount, segmentBuffers, registry, arena);
        return registry.decodeAsSegment(childCtx);
    }

    /// Recursively decode child `i` with an explicit dtype and row count, returning its primary segment.
    ///
    /// @param i        zero-based child index within this node's children array
    /// @param dtype    logical type to assign to the child context
    /// @param rowCount number of logical rows for the child
    /// @return the primary [MemorySegment] of the decoded child
    public MemorySegment decodeChildSegment(int i, DType dtype, long rowCount) {
        ArrayNode child = node.children()[i];
        var childCtx = new DecodeContext(child, dtype, rowCount, segmentBuffers, registry, arena);
        return registry.decodeAsSegment(childCtx);
    }

    /// Materializes an already-decoded array into a flat primary segment, allocating lazy
    /// variants from this context's arena.
    ///
    /// Use when a decoder already holds a decoded child — e.g. after unwrapping a
    /// [io.github.dfa1.vortex.reader.array.MaskedArray] for its validity — and needs the
    /// raw buffer for a bulk read, rather than re-decoding via [#decodeChildSegment(int)].
    ///
    /// @param arr the decoded array to materialize
    /// @return the array's primary [MemorySegment]
    public MemorySegment materialize(Array arr) {
        return arr.materialize(arena);
    }

    /// Returns the buffer at position `i` in this node's bufferIndices.
    ///
    /// @param i zero-based index into this node's `bufferIndices` array
    /// @return the [MemorySegment] for the referenced segment buffer
    public MemorySegment buffer(int i) {
        return segmentBuffers[node.bufferIndices()[i]];
    }

    /// Returns the encoding-specific metadata bytes for this node, or `null` if absent.
    ///
    /// @return the metadata [MemorySegment], or `null`
    public MemorySegment metadata() {
        return node.metadata();
    }
}
