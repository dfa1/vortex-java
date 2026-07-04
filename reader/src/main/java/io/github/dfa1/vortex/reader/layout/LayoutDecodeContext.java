package io.github.dfa1.vortex.reader.layout;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.reader.SegmentSpec;
import io.github.dfa1.vortex.reader.array.Array;

import java.lang.foreign.SegmentAllocator;

/// Capabilities a [LayoutDecoder] needs to decode one layout subtree.
///
/// A context is bound to a single decode epoch — in particular a single [#arena()] — so a
/// [LayoutDecoder] never chooses where its output lands; the caller (the scan) supplies the
/// arena that owns the chunk it is filling. Recursion into child layouts routes back through
/// the [LayoutRegistry] via [#decodeChild(Layout, DType)], so a decoder handling one layout id
/// need not know how any other id decodes.
public interface LayoutDecodeContext {

    /// Decodes a child layout by routing it back through the [LayoutRegistry], into the same
    /// arena and epoch as the current decode. This is the recursion primitive: a container
    /// layout (chunked, zoned, dict) decodes its children without knowing their layout ids.
    ///
    /// @param child the child layout node to decode
    /// @param dtype logical type the child decodes to
    /// @return the decoded child [Array]
    Array decodeChild(Layout child, DType dtype);

    /// Decodes the encoded array stored in a single flat segment, delegating to the underlying
    /// file handle.
    ///
    /// @param spec     the segment to read and decode
    /// @param dtype    logical type of the decoded array
    /// @param rowCount number of logical rows in the segment
    /// @return the decoded [Array]
    Array decodeFlatSegment(SegmentSpec spec, DType dtype, long rowCount);

    /// Resolves a segment index (as stored on a flat [Layout]) to its [SegmentSpec] in the
    /// file's segment table.
    ///
    /// @param index the segment index recorded on a flat layout node
    /// @return the [SegmentSpec] at that index
    SegmentSpec segmentSpec(int index);

    /// Returns the allocator for decode output. Its lifetime matches the chunk epoch being
    /// filled, so decoders allocate expansion buffers here rather than on the heap.
    ///
    /// @return the arena for this decode epoch
    SegmentAllocator arena();
}
