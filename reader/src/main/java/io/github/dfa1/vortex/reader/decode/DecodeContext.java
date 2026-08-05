package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.error.VortexException;
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
        var childCtx = new DecodeContext(child(i), dtype, rowCount, segmentBuffers, registry, arena);
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
        var childCtx = new DecodeContext(child(i), dtype, rowCount, segmentBuffers, registry, arena);
        return registry.decode(childCtx);
    }

    /// Recursively decode child `i` and return its primary backing segment.
    ///
    /// @param i zero-based child index within this node's children array
    /// @return the primary [MemorySegment] of the decoded child
    public MemorySegment decodeChildSegment(int i) {
        var childCtx = new DecodeContext(child(i), dtype, rowCount, segmentBuffers, registry, arena);
        return registry.decodeAsSegment(childCtx);
    }

    /// Recursively decode child `i` with an explicit dtype and row count, returning its primary segment.
    ///
    /// @param i        zero-based child index within this node's children array
    /// @param dtype    logical type to assign to the child context
    /// @param rowCount number of logical rows for the child
    /// @return the primary [MemorySegment] of the decoded child
    public MemorySegment decodeChildSegment(int i, DType dtype, long rowCount) {
        var childCtx = new DecodeContext(child(i), dtype, rowCount, segmentBuffers, registry, arena);
        return registry.decodeAsSegment(childCtx);
    }

    /// Returns buffer `bufferPosition` of child `i` without decoding that child.
    ///
    /// Package-private: it exists only so decoders that read a child's raw segment
    /// directly (the legacy dict layout) go through the same bounds-checked accessors as
    /// every other read instead of indexing the children and segment arrays by hand.
    ///
    /// @param i              zero-based child index within this node's children array
    /// @param bufferPosition zero-based index into the child's `bufferIndices` array
    /// @return the child's [MemorySegment] at that position
    MemorySegment childBuffer(int i, int bufferPosition) {
        var childCtx = new DecodeContext(child(i), dtype, rowCount, segmentBuffers, registry, arena);
        return childCtx.buffer(bufferPosition);
    }

    /// Returns child `i` of this node, rejecting an index the node does not have.
    ///
    /// The child vector is shaped by untrusted file data — a node may declare fewer
    /// children than its encoding requires — so a missing child must fail as a
    /// [VortexException] rather than a raw `ArrayIndexOutOfBoundsException` (ADR 0003).
    ///
    /// @param i zero-based child index within this node's children array
    /// @return the child [ArrayNode]
    private ArrayNode child(int i) {
        ArrayNode[] children = node.children();
        if (i < 0 || i >= children.length) {
            throw new VortexException(node.encodingId(),
                    "child index " + i + " out of bounds for " + children.length + " child(ren)");
        }
        return children[i];
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

    /// Returns the number of segment buffers available to this context.
    ///
    /// @return the segment buffer count
    public int bufferCount() {
        return segmentBuffers.length;
    }

    /// Returns the buffer at position `i` in this node's bufferIndices.
    ///
    /// Both the position and the segment index it holds come from untrusted file data: a
    /// node may declare fewer buffers than its encoding reads, or point at a segment that
    /// does not exist. Either way the read fails as a [VortexException] rather than a raw
    /// `ArrayIndexOutOfBoundsException` (ADR 0003).
    ///
    /// @param i zero-based index into this node's `bufferIndices` array
    /// @return the [MemorySegment] for the referenced segment buffer
    public MemorySegment buffer(int i) {
        int[] bufferIndices = node.bufferIndices();
        if (i < 0 || i >= bufferIndices.length) {
            throw new VortexException(node.encodingId(),
                    "buffer position " + i + " out of bounds for " + bufferIndices.length + " declared buffer(s)");
        }
        int segmentIndex = bufferIndices[i];
        if (segmentIndex < 0 || segmentIndex >= bufferCount()) {
            throw new VortexException(node.encodingId(),
                    "buffer index " + segmentIndex + " out of bounds for " + bufferCount() + " segment(s)");
        }
        return segmentBuffers[segmentIndex];
    }

    /// Returns the encoding-specific metadata bytes for this node, or `null` if absent.
    ///
    /// @return the metadata [MemorySegment], or `null`
    public MemorySegment metadata() {
        return node.metadata();
    }
}
