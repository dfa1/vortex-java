package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.model.PType;

import java.lang.foreign.MemorySegment;

/// One data buffer of an encoded array, with the byte alignment of the elements it holds.
///
/// The alignment is written into the buffer's descriptor and must be exactly the element type's
/// natural alignment, as the Rust writer declares it. The Rust reader keeps a buffer's declared
/// alignment: declaring less than the element needs fails array construction ("Misaligned buffer
/// cannot be used to build PrimitiveArray"), and declaring more makes any row-range slice that
/// does not land on a multiple of it panic and abort the JVM.
///
/// @param data      the buffer bytes
/// @param alignment byte alignment of the buffer's elements; a power of two
public record EncodedBuffer(MemorySegment data, int alignment) {

    /// Validates that `alignment` is a positive power of two.
    public EncodedBuffer {
        if (alignment <= 0 || Integer.bitCount(alignment) != 1) {
            throw new IllegalArgumentException("alignment must be a power of two: " + alignment);
        }
    }

    /// A buffer of `ptype` values, aligned to the type's width.
    ///
    /// @param data  the buffer bytes
    /// @param ptype the element type
    /// @return the buffer with `ptype`'s alignment
    public static EncodedBuffer of(MemorySegment data, PType ptype) {
        return new EncodedBuffer(data, ptype.byteSize());
    }

    /// A buffer of bytes with no alignment requirement: bitmaps, string bytes, opaque payloads.
    ///
    /// @param data the buffer bytes
    /// @return the buffer with alignment 1
    public static EncodedBuffer bytes(MemorySegment data) {
        return new EncodedBuffer(data, 1);
    }
}
