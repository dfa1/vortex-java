package io.github.dfa1.vortex.core.fbs;

import static io.github.dfa1.vortex.core.io.VortexFormat.LE_DOUBLE;
import static io.github.dfa1.vortex.core.io.VortexFormat.LE_FLOAT;
import static io.github.dfa1.vortex.core.io.VortexFormat.LE_INT;
import static io.github.dfa1.vortex.core.io.VortexFormat.LE_LONG;
import static io.github.dfa1.vortex.core.io.VortexFormat.LE_SHORT;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/// MemorySegment-native base for generated FlatBuffers struct accessors.
///
/// Unlike a table, a FlatBuffers struct is a fixed-size, inline record with no
/// vtable: every field lives at a compile-time-constant byte offset from the
/// struct position, and every field is always present. Generated accessors read
/// directly at `position + fieldOffset`.
///
/// Package-private: only the generated struct accessors in this package extend it.
class FbsInlineStruct {

    /// The backing buffer.
    protected MemorySegment seg;

    /// This struct's byte position within [#seg].
    protected long pos;

    /// Creates an unpositioned cursor; call [#init(MemorySegment, long)] before use.
    protected FbsInlineStruct() {
    }

    /// Positions this cursor at a struct.
    ///
    /// @param segment  backing buffer
    /// @param position struct position within the buffer
    protected final void init(MemorySegment segment, long position) {
        this.seg = segment;
        this.pos = position;
    }

    /// @param fieldOffset constant byte offset of the field within the struct
    /// @return the signed byte field
    protected final byte readByte(int fieldOffset) {
        return seg.get(ValueLayout.JAVA_BYTE, pos + fieldOffset);
    }

    /// @param fieldOffset constant byte offset of the field within the struct
    /// @return the little-endian 16-bit field
    protected final short readShort(int fieldOffset) {
        return seg.get(LE_SHORT, pos + fieldOffset);
    }

    /// @param fieldOffset constant byte offset of the field within the struct
    /// @return the little-endian 32-bit field
    protected final int readInt(int fieldOffset) {
        return seg.get(LE_INT, pos + fieldOffset);
    }

    /// @param fieldOffset constant byte offset of the field within the struct
    /// @return the little-endian 64-bit field
    protected final long readLong(int fieldOffset) {
        return seg.get(LE_LONG, pos + fieldOffset);
    }

    /// @param fieldOffset constant byte offset of the field within the struct
    /// @return the little-endian 32-bit float field
    protected final float readFloat(int fieldOffset) {
        return seg.get(LE_FLOAT, pos + fieldOffset);
    }

    /// @param fieldOffset constant byte offset of the field within the struct
    /// @return the little-endian 64-bit double field
    protected final double readDouble(int fieldOffset) {
        return seg.get(LE_DOUBLE, pos + fieldOffset);
    }
}
