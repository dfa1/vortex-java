package io.github.dfa1.vortex.reader;

import io.github.dfa1.vortex.core.IoBounds;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.core.VortexFormat;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;

/// Parsed Vortex file trailer.
///
/// The 8-byte trailer at the end of every Vortex file encodes
/// `version(u16 LE) | postscriptLen(u16 LE) | magic(4)`. This record
/// captures the two integer fields after the magic check has passed.
///
/// @param version       file-format version (must equal [VortexFormat#VERSION])
/// @param postscriptLen length in bytes of the postscript blob immediately preceding the trailer
record Trailer(int version, int postscriptLen) {

    private static final ValueLayout.OfShort LE_SHORT =
            ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    /// Parse the 8-byte trailer from a [MemorySegment] view and validate magic, version, and
    /// postscript length against the body size.
    ///
    /// @param trailerSeg the trailer slice, must be exactly [VortexFormat#TRAILER_SIZE] bytes
    /// @param bodyBytes  number of bytes in the file body (i.e. `fileSize - TRAILER_SIZE`)
    /// @return validated [Trailer]
    /// @throws VortexException if the magic mismatches, the version is unsupported, or
    ///                         postscriptLen is zero or exceeds `bodyBytes`
    static Trailer parse(MemorySegment trailerSeg, long bodyBytes) {
        int version = Short.toUnsignedInt(trailerSeg.get(LE_SHORT, 0));
        int postscriptLen = Short.toUnsignedInt(trailerSeg.get(LE_SHORT, 2));

        MemorySegment magicSlice = IoBounds.slice(trailerSeg, 4, VortexFormat.MAGIC_SIZE);
        if (magicSlice.mismatch(VortexFormat.MAGIC) != -1) {
            throw new VortexException(
                "invalid magic bytes [%02x %02x %02x %02x]".formatted(
                    magicSlice.get(ValueLayout.JAVA_BYTE, 0),
                    magicSlice.get(ValueLayout.JAVA_BYTE, 1),
                    magicSlice.get(ValueLayout.JAVA_BYTE, 2),
                    magicSlice.get(ValueLayout.JAVA_BYTE, 3)));
        }

        if (version != VortexFormat.VERSION) {
            throw new VortexException(
                "unsupported file version=" + version
                    + " (this reader supports version " + VortexFormat.VERSION + ")");
        }
        if (postscriptLen == 0) {
            throw new VortexException("invalid postscript: length is zero");
        }
        if (postscriptLen > bodyBytes) {
            throw new VortexException(
                "invalid postscript: length=" + postscriptLen
                    + " exceeds file body size=" + bodyBytes);
        }

        return new Trailer(version, postscriptLen);
    }
}
