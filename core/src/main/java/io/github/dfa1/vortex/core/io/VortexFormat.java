package io.github.dfa1.vortex.core.io;

import java.lang.foreign.MemorySegment;

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

    private VortexFormat() {
    }
}
