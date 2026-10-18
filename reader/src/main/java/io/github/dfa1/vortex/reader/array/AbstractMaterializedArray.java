package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.reader.decode.SegmentBroadcast;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.util.Optional;

/// Shared cold-path plumbing for the buffer-backed `Materialized*` arrays: the
/// `dtype` / `length` / `buffer` triple, the trivial accessors, and the zero-copy
/// `materialize` / `segmentIfPresent` contract every variant shares.
///
/// Subclasses keep their own typed element access and the branch-split hot loops
/// (`getX` / `fold` / `forEach`): those must stay monomorphic in the leaf class so the
/// JIT can vectorize them, and so are deliberately not hoisted here. This base only
/// holds the cold boilerplate.
///
/// Not `implements Array`: that interface is sealed to the typed element families
/// ([IntArray], [LongArray], …), which each leaf implements directly while inheriting
/// the common methods from here.
abstract class AbstractMaterializedArray {

    final DType dtype;
    final long length;
    final MemorySegment buffer;
    /// Number of physical elements the buffer actually holds, derived from its byte size
    /// and the element width. Equals [#length] except for broadcast buffers (e.g. a
    /// `ConstantEncoding` of one element), where the leaf hot loops take `i % elementCount`.
    final long elementCount;

    AbstractMaterializedArray(DType dtype, long length, MemorySegment buffer) {
        this.dtype = dtype;
        this.length = length;
        this.buffer = buffer;
        // Only a full buffer or a single broadcast element: an empty one for a non-empty array made
        // the leaves' `i % elementCount` divide by zero (a fuzzer finding), so it is refused here.
        this.elementCount = switch (dtype) {
            case DType.Primitive p -> SegmentBroadcast.capacity(buffer, p.ptype().byteSize(), length);
            default -> length;
        };
    }

    public final DType dtype() {
        return dtype;
    }

    public final long length() {
        return length;
    }

    /// Returns the backing buffer directly — it is already the contiguous segment the
    /// array's materialize contract promises, so no copy or allocation is needed.
    ///
    /// @param arena unused; the existing buffer is returned as-is
    /// @return the backing segment
    @SuppressWarnings("java:S1172") // arena is contractual: this implements Array#materialize(SegmentAllocator) for the leaf classes; unused only because the buffer is already materialized.
    public final MemorySegment materialize(SegmentAllocator arena) {
        return buffer;
    }

    public final Optional<MemorySegment> segmentIfPresent() {
        return Optional.of(buffer);
    }
}
