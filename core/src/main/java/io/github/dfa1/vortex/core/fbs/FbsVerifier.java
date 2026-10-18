package io.github.dfa1.vortex.core.fbs;

import static io.github.dfa1.vortex.core.io.VortexFormat.LE_INT;
import static io.github.dfa1.vortex.core.io.VortexFormat.LE_SHORT;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import io.github.dfa1.vortex.core.error.VortexException;

/// Checks a whole FlatBuffer before any accessor reads it, as the Rust reference does with
/// `flatbuffers::root::<T>()` on every buffer it parses.
///
/// The [FbsTable] accessors follow offsets without bounds checks, so an unverified crafted buffer
/// fails deep inside the reader as a raw JDK exception, at whichever site first touches the bad
/// offset. Every generated `getRootAsX` runs this first instead: it walks every table reachable from
/// the root through its generated `verify(FbsVerifier, long)` and rejects the buffer with a
/// [VortexException] if any offset, vtable, vector or string falls outside it. Once a buffer passes,
/// the accessors cannot read out of bounds.
///
/// Rules (the flatbuffers verifier's, with its default limits, which Vortex keeps):
/// - every table's soffset, vtable and object lie in the buffer, and the vtable is at least 4 bytes;
/// - every present field lies in the buffer, and every uoffset points into it;
/// - every vector's elements and every string's bytes plus NUL terminator lie in it;
/// - a `(required)` field is present;
/// - tables nest at most [#MAX_DEPTH] deep, and a buffer holds at most [#MAX_TABLES] tables.
///
/// Not yet checked, though Rust does: alignment, UTF-8 validity of strings, and the
/// total-apparent-size cap.
final class FbsVerifier {

    /// flatbuffers' default `max_depth`, which Vortex keeps (`VORTEX_MAX_LAYOUT_DEPTH`).
    static final int MAX_DEPTH = 64;

    /// flatbuffers' default `max_tables`, which Vortex keeps (`VORTEX_MAX_LAYOUT_TABLES`).
    static final int MAX_TABLES = 1_000_000;

    /// Verifies one table: the generated `verify` of the table's class.
    @FunctionalInterface
    interface TableVerifier {

        /// @param verifier the verifier walking the buffer
        /// @param position the table's absolute position
        void verify(FbsVerifier verifier, long position);
    }

    private final MemorySegment seg;
    private final long size;
    private int depth;
    private int tables;

    private FbsVerifier(MemorySegment seg) {
        this.seg = seg;
        this.size = seg.byteSize();
    }

    /// Verifies a finished buffer whose root table `root` describes.
    ///
    /// @param seg  the buffer
    /// @param root the root table's verifier
    /// @return the absolute position of the root table
    /// @throws VortexException if the buffer is malformed
    static long verifyRoot(MemorySegment seg, TableVerifier root) {
        var verifier = new FbsVerifier(seg);
        long position = verifier.uoffset(0);
        root.verify(verifier, position);
        return position;
    }

    /// Opens a table: checks its soffset, vtable and object, and the depth and table limits.
    /// Every `startTable` is paired with an [#endTable()].
    ///
    /// @param position the table's absolute position
    void startTable(long position) {
        if (++depth > MAX_DEPTH) {
            throw malformed("tables nest deeper than " + MAX_DEPTH);
        }
        if (++tables > MAX_TABLES) {
            throw malformed("more than " + MAX_TABLES + " tables");
        }
        check(position, Integer.BYTES, "table");
        long vtable = position - seg.get(LE_INT, position);
        check(vtable, 2 * Short.BYTES, "vtable");
        int vtableSize = Short.toUnsignedInt(seg.get(LE_SHORT, vtable));
        int objectSize = Short.toUnsignedInt(seg.get(LE_SHORT, vtable + Short.BYTES));
        if (vtableSize < 2 * Short.BYTES || vtableSize % Short.BYTES != 0) {
            throw malformed("vtable size " + vtableSize + " at " + vtable);
        }
        check(vtable, vtableSize, "vtable");
        check(position, objectSize, "table object");
    }

    /// Closes the table the last [#startTable(long)] opened.
    void endTable() {
        depth--;
    }

    /// Checks an inline field (a scalar, an enum, or a struct) of `size` bytes.
    ///
    /// @param table  the table's absolute position
    /// @param slot   the field's vtable slot offset (`4 + fieldIndex * 2`)
    /// @param size   the field's inline size in bytes
    /// @param name   the field name, for the error message
    /// @param required whether the schema marks the field `(required)`
    void inline(long table, int slot, int size, String name, boolean required) {
        field(table, slot, size, name, required);
    }

    /// Checks a string field.
    ///
    /// @param table    the table's absolute position
    /// @param slot     the field's vtable slot offset
    /// @param name     the field name, for the error message
    /// @param required whether the schema marks the field `(required)`
    void string(long table, int slot, String name, boolean required) {
        long at = field(table, slot, Integer.BYTES, name, required);
        if (at >= 0) {
            stringAt(uoffset(at));
        }
    }

    /// Checks a vector field of inline elements (scalars, enums or structs).
    ///
    /// @param table       the table's absolute position
    /// @param slot        the field's vtable slot offset
    /// @param elementSize the inline size of one element in bytes
    /// @param name        the field name, for the error message
    /// @param required    whether the schema marks the field `(required)`
    void vector(long table, int slot, int elementSize, String name, boolean required) {
        long at = field(table, slot, Integer.BYTES, name, required);
        if (at >= 0) {
            vectorAt(uoffset(at), elementSize);
        }
    }

    /// Checks a vector-of-strings field and every string in it.
    ///
    /// @param table    the table's absolute position
    /// @param slot     the field's vtable slot offset
    /// @param name     the field name, for the error message
    /// @param required whether the schema marks the field `(required)`
    void stringVector(long table, int slot, String name, boolean required) {
        long at = field(table, slot, Integer.BYTES, name, required);
        if (at >= 0) {
            long elements = uoffset(at);
            long count = vectorAt(elements, Integer.BYTES);
            for (long i = 0; i < count; i++) {
                stringAt(uoffset(elements + Integer.BYTES + i * Integer.BYTES));
            }
        }
    }

    /// Checks a vector-of-tables field and every table in it.
    ///
    /// @param table    the table's absolute position
    /// @param slot     the field's vtable slot offset
    /// @param name     the field name, for the error message
    /// @param required whether the schema marks the field `(required)`
    /// @param element  the element table's verifier
    void tableVector(long table, int slot, String name, boolean required, TableVerifier element) {
        long at = field(table, slot, Integer.BYTES, name, required);
        if (at >= 0) {
            long elements = uoffset(at);
            long count = vectorAt(elements, Integer.BYTES);
            for (long i = 0; i < count; i++) {
                element.verify(this, uoffset(elements + Integer.BYTES + i * Integer.BYTES));
            }
        }
    }

    /// Checks a table field and the table it points to.
    ///
    /// @param table    the table's absolute position
    /// @param slot     the field's vtable slot offset
    /// @param name     the field name, for the error message
    /// @param required whether the schema marks the field `(required)`
    /// @param child    the child table's verifier
    void table(long table, int slot, String name, boolean required, TableVerifier child) {
        long at = field(table, slot, Integer.BYTES, name, required);
        if (at >= 0) {
            child.verify(this, uoffset(at));
        }
    }

    /// Checks a union's discriminator field and returns it, so the generated code can verify the
    /// member table its value field points to with [#unionValue(long, int, String)]. A member type
    /// without a value, or a value without a member type, is malformed, as in Rust's `visit_union`
    /// (`InconsistentUnion`): the accessors would return null for the member the type names.
    ///
    /// @param table     the table's absolute position
    /// @param slot      the discriminator's vtable slot offset
    /// @param valueSlot the value's vtable slot offset
    /// @param name      the union field name, for the error message
    /// @return the discriminator (0 = NONE)
    int unionType(long table, int slot, int valueSlot, String name) {
        long at = field(table, slot, Byte.BYTES, name + "_type", false);
        int type = at >= 0 ? Byte.toUnsignedInt(seg.get(ValueLayout.JAVA_BYTE, at)) : 0;
        if ((type != 0) != (field(table, valueSlot, Integer.BYTES, name, false) >= 0)) {
            throw malformed("union '" + name + "' type " + type + " inconsistent with its value in table at " + table);
        }
        return type;
    }

    /// Resolves a union's value field to the member table's position.
    ///
    /// @param table the table's absolute position
    /// @param slot  the value's vtable slot offset
    /// @param name  the union field name, for the error message
    /// @return the member table's absolute position, or -1 when the value is absent
    long unionValue(long table, int slot, String name) {
        long at = field(table, slot, Integer.BYTES, name, false);
        return at >= 0 ? uoffset(at) : -1;
    }

    /// Returns a field's absolute position after checking it lies in the buffer, or -1 if absent.
    private long field(long table, int slot, int size, String name, boolean required) {
        long vtable = table - seg.get(LE_INT, table);
        int vtableSize = Short.toUnsignedInt(seg.get(LE_SHORT, vtable));
        int offset = slot < vtableSize ? Short.toUnsignedInt(seg.get(LE_SHORT, vtable + slot)) : 0;
        if (offset == 0) {
            if (required) {
                throw malformed("required field '" + name + "' missing in table at " + table);
            }
            return -1;
        }
        // The message is built only on failure: this runs for every field of every table verified.
        if (!inBuffer(table + offset, size)) {
            throw outside("field '" + name + "'", table + offset, size);
        }
        return table + offset;
    }

    /// Follows the uoffset at `at` and returns the position it points to, inside the buffer.
    private long uoffset(long at) {
        check(at, Integer.BYTES, "offset");
        long target = at + Integer.toUnsignedLong(seg.get(LE_INT, at));
        check(target, 1, "offset target");
        return target;
    }

    /// Checks a vector's length prefix and elements; returns its element count.
    private long vectorAt(long at, int elementSize) {
        check(at, Integer.BYTES, "vector length");
        long count = Integer.toUnsignedLong(seg.get(LE_INT, at));
        if (!inBuffer(at + Integer.BYTES, count * elementSize)) {
            throw outside("vector of " + count + " elements", at + Integer.BYTES, count * elementSize);
        }
        return count;
    }

    /// Checks a string's length prefix, bytes and NUL terminator.
    private void stringAt(long at) {
        long length = vectorAt(at, Byte.BYTES);
        long terminator = at + Integer.BYTES + length;
        check(terminator, 1, "string terminator");
        if (seg.get(ValueLayout.JAVA_BYTE, terminator) != 0) {
            throw malformed("string at " + at + " is not NUL-terminated");
        }
    }

    /// Checks that `[at, at + length)` lies in the buffer.
    private void check(long at, long length, String what) {
        if (!inBuffer(at, length)) {
            throw outside(what, at, length);
        }
    }

    /// Whether `[at, at + length)` lies in the buffer (the arithmetic cannot overflow: both are below
    /// 2^35 here, from u32 counts times element sizes of at most 8).
    private boolean inBuffer(long at, long length) {
        return at >= 0 && length >= 0 && at + length <= size;
    }

    private VortexException outside(String what, long at, long length) {
        return malformed(what + " [" + at + ", " + (at + length) + ") outside the " + size + "-byte buffer");
    }

    private static VortexException malformed(String reason) {
        return new VortexException("malformed flatbuffer: " + reason);
    }
}
