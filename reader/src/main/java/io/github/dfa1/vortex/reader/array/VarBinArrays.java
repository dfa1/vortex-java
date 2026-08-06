package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.error.VortexException;

import java.lang.foreign.MemorySegment;

/// Package-private helpers shared by the `VarBin*Array` implementations of [VarBinArray].
final class VarBinArrays {

    private VarBinArrays() {
    }

    /// Validates that element bytes `[start, end)` lie inside `bytes` and returns the
    /// element length.
    ///
    /// Offsets arrive from an untrusted file and are deliberately not scanned at decode
    /// time (VarBin decode stays zero-copy and lazy), so a non-monotonic, negative or
    /// past-the-end pair has to be rejected here — as a [VortexException], never as a raw
    /// `NegativeArraySizeException` from `new byte[end - start]` or an
    /// `IndexOutOfBoundsException` from [MemorySegment#copy(MemorySegment, long, MemorySegment, long, long)]
    /// (ADR 0003).
    ///
    /// @param bytes data buffer the offsets index into
    /// @param start start offset of the element
    /// @param end   end offset of the element, exclusive
    /// @return the element length in bytes
    static int checkedLength(MemorySegment bytes, long start, long end) {
        long len = end - start;
        if (start < 0 || len < 0 || end > bytes.byteSize() || len > Integer.MAX_VALUE) {
            throw new VortexException("varbin element bytes [" + start + ", " + end
                    + ") out of range for a data buffer of " + bytes.byteSize() + " bytes");
        }
        return (int) len;
    }
}
