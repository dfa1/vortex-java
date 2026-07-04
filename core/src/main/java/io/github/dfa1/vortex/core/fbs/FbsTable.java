package io.github.dfa1.vortex.core.fbs;

import static io.github.dfa1.vortex.core.io.VortexFormat.LE_DOUBLE;
import static io.github.dfa1.vortex.core.io.VortexFormat.LE_FLOAT;
import static io.github.dfa1.vortex.core.io.VortexFormat.LE_INT;
import static io.github.dfa1.vortex.core.io.VortexFormat.LE_LONG;
import static io.github.dfa1.vortex.core.io.VortexFormat.LE_SHORT;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

/// MemorySegment-native base for generated FlatBuffers table accessors.
///
/// A FlatBuffers table is addressed by a position into a little-endian buffer.
/// The first 4 bytes at that position are a signed offset (`soffset`) back to the
/// table's vtable; the vtable maps each field's index to a 16-bit offset relative
/// to the table position (0 = field absent, read its default). This base provides
/// the small set of primitives the generated code needs to navigate that layout
/// over an FFM [MemorySegment] instead of a `java.nio.ByteBuffer`.
///
/// Instances are mutable cursors (position + segment) reused during a scan, mirroring
/// the flatc-generated `__assign` pattern: zero allocation on the hot read path.
///
/// Bounds: these accessors do not validate offsets or lengths read from the buffer
/// (e.g. [#readStringAt(long)] allocates `new byte[len]` with `len` taken straight from
/// the blob). This matches the previous `com.google.flatbuffers` runtime — the caller is
/// expected to have framed the FlatBuffer from a trusted region. File-level framing
/// (segment offsets/lengths, trailer, postscript) is range-checked upstream by the
/// reader's `IoBounds`; intra-FlatBuffer fields are not.
///
/// Package-private: only the generated table accessors in this package extend it.
class FbsTable {

    /// The backing buffer (typically a zero-copy slice of the mmap'd file).
    protected MemorySegment seg;

    /// This table's byte position within [#seg].
    protected long pos;

    /// Creates an unpositioned cursor; call [#init(MemorySegment, long)] before use.
    protected FbsTable() {
    }

    /// Positions this cursor at a table.
    ///
    /// @param segment  backing buffer
    /// @param position table position within the buffer
    protected final void init(MemorySegment segment, long position) {
        this.seg = segment;
        this.pos = position;
    }

    /// Resolves the root table position of a finished FlatBuffer.
    ///
    /// @param segment buffer whose first 4 bytes hold the uoffset to the root table
    /// @param start   byte position of the buffer start within `segment`
    /// @return the absolute position of the root table
    static long rootPosition(MemorySegment segment, long start) {
        return start + segment.get(LE_INT, start);
    }

    /// Looks up a field's offset relative to this table.
    ///
    /// @param vtableOffset the field's vtable slot (`4 + fieldIndex * 2`)
    /// @return the field's offset relative to the table position, or 0 if the field
    ///         is absent (caller falls back to the field default)
    protected final int fieldOffset(int vtableOffset) {
        long vtable = pos - seg.get(LE_INT, pos);
        int vtableSize = seg.get(LE_SHORT, vtable) & 0xFFFF;
        return vtableOffset < vtableSize ? (seg.get(LE_SHORT, vtable + vtableOffset) & 0xFFFF) : 0;
    }

    /// Follows a `uoffset` stored at an absolute position.
    ///
    /// @param at absolute position of the 32-bit uoffset
    /// @return the absolute position it points to
    protected final long indirect(long at) {
        return at + seg.get(LE_INT, at);
    }

    /// Resolves the absolute position of a vector's first element.
    ///
    /// @param fieldOffset the field offset returned by [#fieldOffset(int)] (non-zero)
    /// @return absolute position of element 0 (past the 32-bit length prefix)
    protected final long vectorElements(int fieldOffset) {
        long at = fieldOffset + pos;
        return at + seg.get(LE_INT, at) + 4;
    }

    /// Reads a vector's element count.
    ///
    /// @param fieldOffset the field offset returned by [#fieldOffset(int)] (non-zero)
    /// @return number of elements
    protected final int vectorLength(int fieldOffset) {
        long at = fieldOffset + pos;
        at += seg.get(LE_INT, at);
        return seg.get(LE_INT, at);
    }

    /// Resolves the position of a union's selected member table.
    ///
    /// @param fieldOffset the union value field offset (non-zero)
    /// @return absolute position of the member table
    protected final long unionMemberPosition(int fieldOffset) {
        long at = fieldOffset + pos;
        return at + seg.get(LE_INT, at);
    }

    /// Positions another table cursor onto this table's buffer (used by generated union
    /// accessors to project the selected member table without exposing the buffer).
    ///
    /// @param other    the cursor to position
    /// @param position the table position
    /// @param <T>      the cursor type
    /// @return `other`, positioned
    protected final <T extends FbsTable> T locate(T other, long position) {
        other.seg = this.seg;
        other.pos = position;
        return other;
    }

    /// Reads a length-prefixed UTF-8 string whose `uoffset` lives at an absolute position.
    /// Use `readStringAt(pos + fieldOffset)` for a string field and
    /// `readStringAt(vectorElements(o) + j * 4)` for the j-th element of a string vector.
    ///
    /// @param uoffsetAt absolute position of the 32-bit offset to the string
    /// @return the decoded string
    protected final String readStringAt(long uoffsetAt) {
        long start = uoffsetAt + seg.get(LE_INT, uoffsetAt);
        int len = seg.get(LE_INT, start);
        byte[] bytes = new byte[len];
        MemorySegment.copy(seg, ValueLayout.JAVA_BYTE, start + 4, bytes, 0, len);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /// Returns a zero-copy slice of a byte/ubyte vector field.
    ///
    /// @param fieldOffset the field offset returned by [#fieldOffset(int)] (non-zero)
    /// @return a slice covering exactly the vector's bytes
    protected final MemorySegment byteVector(int fieldOffset) {
        long elements = vectorElements(fieldOffset);
        return seg.asSlice(elements, vectorLength(fieldOffset));
    }

    /// @param at absolute position
    /// @return the signed byte at `at`
    protected final byte readByte(long at) {
        return seg.get(ValueLayout.JAVA_BYTE, at);
    }

    /// @param at absolute position
    /// @return the little-endian 16-bit value at `at`
    protected final short readShort(long at) {
        return seg.get(LE_SHORT, at);
    }

    /// @param at absolute position
    /// @return the little-endian 32-bit value at `at`
    protected final int readInt(long at) {
        return seg.get(LE_INT, at);
    }

    /// @param at absolute position
    /// @return the little-endian 64-bit value at `at`
    protected final long readLong(long at) {
        return seg.get(LE_LONG, at);
    }

    /// @param at absolute position
    /// @return the little-endian 32-bit float at `at`
    protected final float readFloat(long at) {
        return seg.get(LE_FLOAT, at);
    }

    /// @param at absolute position
    /// @return the little-endian 64-bit double at `at`
    protected final double readDouble(long at) {
        return seg.get(LE_DOUBLE, at);
    }
}
