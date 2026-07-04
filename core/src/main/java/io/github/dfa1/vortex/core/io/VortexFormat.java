package io.github.dfa1.vortex.core.io;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import java.nio.ByteOrder;

/// Wire-format constants for the Vortex file format.
///
/// The file ends with an 8-byte trailer: `version(u16 LE) | postscriptLen(u16 LE) | magic(4)`.
/// The magic bytes `VTXF` identify the file as Vortex; [#VERSION] is the format version
/// produced by this implementation and the only one accepted on read.
public final class VortexFormat {

    /// File-format magic bytes (`VTXF`), exposed as a read-only [MemorySegment].
    /// Any attempt to write through this segment throws `UnsupportedOperationException`.
    public static final MemorySegment MAGIC =
            MemorySegment.ofArray(new byte[]{'V', 'T', 'X', 'F'}).asReadOnly();

    /// Length of the magic-bytes sequence in bytes.
    public static final int MAGIC_SIZE = 4;

    /// Size of the file trailer in bytes: `version(u16) | postscriptLen(u16) | magic(4)`.
    public static final int TRAILER_SIZE = 8;

    /// File-format version this implementation reads and writes.
    /// Files with any other version are rejected up front rather than silently mis-parsed.
    public static final int VERSION = 1;

    // All multi-byte integers in the Vortex wire format are little-endian — trailer fields,
    // spec-table indexes, buffer scaffolding, and element values alike. These unaligned
    // little-endian layouts are the single source for every wire read/write; nothing outside
    // this class defines its own withOrder(LITTLE_ENDIAN) copy.

    /// Unaligned little-endian layout for 16-bit shorts.
    public static final ValueLayout.OfShort LE_SHORT = ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    /// Unaligned little-endian layout for 32-bit ints.
    public static final ValueLayout.OfInt LE_INT = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    /// Unaligned little-endian layout for 64-bit longs.
    public static final ValueLayout.OfLong LE_LONG = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    /// Unaligned little-endian layout for 32-bit floats.
    public static final ValueLayout.OfFloat LE_FLOAT = ValueLayout.JAVA_FLOAT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    /// Unaligned little-endian layout for 64-bit doubles.
    public static final ValueLayout.OfDouble LE_DOUBLE = ValueLayout.JAVA_DOUBLE_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    private VortexFormat() {
    }
}
