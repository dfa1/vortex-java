package io.github.dfa1.vortex.core.fbs;

import static io.github.dfa1.vortex.core.io.VortexFormat.LE_DOUBLE;
import static io.github.dfa1.vortex.core.io.VortexFormat.LE_FLOAT;
import static io.github.dfa1.vortex.core.io.VortexFormat.LE_INT;
import static io.github.dfa1.vortex.core.io.VortexFormat.LE_LONG;
import static io.github.dfa1.vortex.core.io.VortexFormat.LE_SHORT;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/// MemorySegment-native FlatBuffers builder — the write counterpart of [FbsTable].
///
/// Reimplements the standard FlatBuffers serialization algorithm (build back-to-front,
/// power-of-two field alignment, vtable construction with deduplication, default-value
/// omission) directly over an FFM [MemorySegment], with no `java.nio.ByteBuffer` and no
/// `com.google.flatbuffers` runtime. The emitted bytes are a valid FlatBuffer that the
/// Rust reference (and the flatc reader) can read.
///
/// Usage mirrors the generated `createX` / `startX` / `addX` / `endX` helpers: scalars
/// and offsets are added in reverse field order between [#startTable(int)] and
/// [#endTable()]; vectors between [#startVector(int, int, int)] and [#endVector()].
public final class FbsBuilder {

    private static final int SIZEOF_SHORT = 2;
    private static final int SIZEOF_INT = 4;

    private MemorySegment buf;
    private int space;
    private int minalign = 1;

    private int[] vtable = new int[0];
    private int vtableInUse;
    private int objectStart;

    private int[] vtables = new int[16];
    private int numVtables;

    private int vectorNumElems;
    private boolean finished;
    private boolean forceDefaults;

    /// Creates a builder with a small initial capacity that grows on demand.
    public FbsBuilder() {
        this(1024);
    }

    /// Creates a builder with the given initial capacity.
    ///
    /// @param initialCapacity initial backing-buffer size in bytes (must be positive)
    public FbsBuilder(int initialCapacity) {
        this.buf = MemorySegment.ofArray(new byte[Math.max(1, initialCapacity)]);
        this.space = (int) buf.byteSize();
    }

    private int capacity() {
        return (int) buf.byteSize();
    }

    /// @return the current write position, measured from the end of the buffer
    public int offset() {
        return capacity() - space;
    }

    private void growBuffer() {
        int oldCap = capacity();
        if ((oldCap & 0xC0000000) != 0) {
            throw new IllegalStateException("FlatBuffer too large");
        }
        // The constructor guarantees capacity >= 1, so doubling always grows.
        int newCap = oldCap << 1;
        MemorySegment grown = MemorySegment.ofArray(new byte[newCap]);
        MemorySegment.copy(buf, 0, grown, (long) newCap - oldCap, oldCap);
        buf = grown;
        space += newCap - oldCap;
    }

    /// Aligns and reserves space for the next write.
    ///
    /// @param size            alignment/size of the value about to be written (power of two)
    /// @param additionalBytes extra bytes that will follow (e.g. a vector's payload)
    public void prep(int size, int additionalBytes) {
        if (size > minalign) {
            minalign = size;
        }
        int alignSize = ((~(offset() + additionalBytes)) + 1) & (size - 1);
        while (space < alignSize + size + additionalBytes) {
            growBuffer();
        }
        pad(alignSize);
    }

    /// Writes `n` zero padding bytes (used by generated struct serializers to fill
    /// inter-field and trailing gaps).
    ///
    /// @param n number of padding bytes
    public void pad(int n) {
        for (int i = 0; i < n; i++) {
            buf.set(ValueLayout.JAVA_BYTE, --space, (byte) 0);
        }
    }

    /// @param x byte to write at the current position
    public void putByte(byte x) {
        buf.set(ValueLayout.JAVA_BYTE, space -= 1, x);
    }

    /// @param x little-endian short to write
    public void putShort(short x) {
        buf.set(LE_SHORT, space -= SIZEOF_SHORT, x);
    }

    /// @param x little-endian int to write
    public void putInt(int x) {
        buf.set(LE_INT, space -= SIZEOF_INT, x);
    }

    /// @param x little-endian long to write
    public void putLong(long x) {
        buf.set(LE_LONG, space -= 8, x);
    }

    /// @param x little-endian float to write
    public void putFloat(float x) {
        buf.set(LE_FLOAT, space -= SIZEOF_INT, x);
    }

    /// @param x little-endian double to write
    public void putDouble(double x) {
        buf.set(LE_DOUBLE, space -= 8, x);
    }

    /// @param x byte to add (aligned)
    public void addByte(byte x) {
        prep(1, 0);
        putByte(x);
    }

    /// @param x short to add (aligned)
    public void addShort(short x) {
        prep(SIZEOF_SHORT, 0);
        putShort(x);
    }

    /// @param x int to add (aligned)
    public void addInt(int x) {
        prep(SIZEOF_INT, 0);
        putInt(x);
    }

    /// @param x long to add (aligned)
    public void addLong(long x) {
        prep(8, 0);
        putLong(x);
    }

    /// @param x float to add (aligned)
    public void addFloat(float x) {
        prep(SIZEOF_INT, 0);
        putFloat(x);
    }

    /// @param x double to add (aligned)
    public void addDouble(double x) {
        prep(8, 0);
        putDouble(x);
    }

    /// Writes a relative offset (uoffset) to a previously written object.
    ///
    /// @param off absolute offset of the referenced object
    public void addOffset(int off) {
        prep(SIZEOF_INT, 0);
        putInt(offset() - off + SIZEOF_INT);
    }

    // ---- tables ---------------------------------------------------------------

    /// Begins a table with `numFields` vtable slots.
    ///
    /// @param numFields number of fields declared by the table
    public void startTable(int numFields) {
        if (vtable.length < numFields) {
            vtable = new int[numFields];
        } else {
            Arrays.fill(vtable, 0, numFields, 0);
        }
        vtableInUse = numFields;
        objectStart = offset();
    }

    private void slot(int voffset) {
        vtable[voffset] = offset();
    }

    /// When enabled, fields equal to their default are still written (instead of omitted),
    /// e.g. to force-serialize a `null_count = 0` so readers can distinguish it from unknown.
    ///
    /// @param force whether to force-write default-valued fields
    public void forceDefaults(boolean force) {
        this.forceDefaults = force;
    }

    /// @param voffset field slot
    /// @param x       value
    /// @param def     default; the field is omitted when equal
    public void addBoolean(int voffset, boolean x, boolean def) {
        if (forceDefaults || x != def) {
            addByte((byte) (x ? 1 : 0));
            slot(voffset);
        }
    }

    /// @param voffset field slot
    /// @param x       value
    /// @param def     default; the field is omitted when equal
    public void addByte(int voffset, int x, int def) {
        if (forceDefaults || x != def) {
            addByte((byte) x);
            slot(voffset);
        }
    }

    /// @param voffset field slot
    /// @param x       value
    /// @param def     default; the field is omitted when equal
    public void addShort(int voffset, int x, int def) {
        if (forceDefaults || x != def) {
            addShort((short) x);
            slot(voffset);
        }
    }

    /// @param voffset field slot
    /// @param x       value
    /// @param def     default; the field is omitted when equal
    public void addInt(int voffset, int x, int def) {
        if (forceDefaults || x != def) {
            addInt(x);
            slot(voffset);
        }
    }

    /// @param voffset field slot
    /// @param x       value
    /// @param def     default; the field is omitted when equal
    public void addLong(int voffset, long x, long def) {
        if (forceDefaults || x != def) {
            addLong(x);
            slot(voffset);
        }
    }

    /// @param voffset field slot
    /// @param x       value
    /// @param def     default; the field is omitted when equal
    public void addFloat(int voffset, float x, float def) {
        if (forceDefaults || x != def) {
            addFloat(x);
            slot(voffset);
        }
    }

    /// @param voffset field slot
    /// @param x       value
    /// @param def     default; the field is omitted when equal
    public void addDouble(int voffset, double x, double def) {
        if (forceDefaults || x != def) {
            addDouble(x);
            slot(voffset);
        }
    }

    /// Adds an offset field (to a table, vector or string).
    ///
    /// @param voffset field slot
    /// @param x       absolute offset of the referenced object
    /// @param def     default; the field is omitted when equal (0 = absent)
    public void addOffset(int voffset, int x, int def) {
        if (forceDefaults || x != def) {
            addOffset(x);
            slot(voffset);
        }
    }

    /// Adds an inline struct field (the struct must have just been written).
    ///
    /// @param voffset field slot
    /// @param x       offset of the inline struct (must equal the current offset)
    /// @param def     default; the field is omitted when equal
    public void addStruct(int voffset, int x, int def) {
        if (forceDefaults || x != def) {
            if (x != offset()) {
                throw new IllegalStateException("struct must be serialized inline");
            }
            slot(voffset);
        }
    }

    /// Finishes the current table, emitting its vtable (deduplicated).
    ///
    /// @return the offset of the finished table
    public int endTable() {
        addInt(0); // soffset placeholder
        int vtableloc = offset();

        int i = vtableInUse - 1;
        while (i >= 0 && vtable[i] == 0) {
            i--;
        }
        int trimmedSize = i + 1;
        for (; i >= 0; i--) {
            addShort((short) (vtable[i] != 0 ? vtableloc - vtable[i] : 0));
        }
        addShort((short) (vtableloc - objectStart));
        addShort((short) ((trimmedSize + 2) * SIZEOF_SHORT));

        int cap = capacity();
        int existing = 0;
        outer:
        for (int j = 0; j < numVtables; j++) {
            int vt1 = cap - vtables[j];
            int vt2 = space;
            int len = buf.get(LE_SHORT, vt1) & 0xFFFF;
            if (len != (buf.get(LE_SHORT, vt2) & 0xFFFF)) {
                continue;
            }
            for (int k = SIZEOF_SHORT; k < len; k += SIZEOF_SHORT) {
                if (buf.get(LE_SHORT, (long) vt1 + k) != buf.get(LE_SHORT, (long) vt2 + k)) {
                    continue outer;
                }
            }
            existing = vtables[j];
            break;
        }

        if (existing != 0) {
            space = cap - vtableloc;
            buf.set(LE_INT, space, existing - vtableloc);
        } else {
            if (numVtables == vtables.length) {
                vtables = Arrays.copyOf(vtables, numVtables * 2);
            }
            vtables[numVtables++] = offset();
            buf.set(LE_INT, (long) cap - vtableloc, offset() - vtableloc);
        }
        return vtableloc;
    }

    // ---- vectors & strings ----------------------------------------------------

    /// Begins a vector. Elements are added in reverse order, then [#endVector()] called.
    ///
    /// @param elemSize  byte size of one element
    /// @param numElems  number of elements
    /// @param alignment element alignment
    public void startVector(int elemSize, int numElems, int alignment) {
        vectorNumElems = numElems;
        prep(SIZEOF_INT, elemSize * numElems);
        prep(alignment, elemSize * numElems);
    }

    /// Finishes the current vector.
    ///
    /// @return the offset of the finished vector
    public int endVector() {
        putInt(vectorNumElems);
        return offset();
    }

    /// Writes a byte vector in one shot.
    ///
    /// @param data the bytes
    /// @return the offset of the finished vector
    public int createByteVector(byte[] data) {
        int length = data.length;
        startVector(1, length, 1);
        space -= length;
        MemorySegment.copy(data, 0, buf, ValueLayout.JAVA_BYTE, space, length);
        return endVector();
    }

    /// Writes a length-prefixed, null-terminated UTF-8 string.
    ///
    /// @param s the string
    /// @return the offset of the finished string
    public int createString(String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        addByte((byte) 0);
        int length = bytes.length;
        startVector(1, length, 1);
        space -= length;
        MemorySegment.copy(bytes, 0, buf, ValueLayout.JAVA_BYTE, space, length);
        return endVector();
    }

    // ---- finishing ------------------------------------------------------------

    /// Finishes the buffer with the given root table.
    ///
    /// @param rootTable offset of the root table
    public void finish(int rootTable) {
        prep(minalign, SIZEOF_INT);
        addOffset(rootTable);
        finished = true;
    }

    /// @return a zero-copy slice covering exactly the finished FlatBuffer
    public MemorySegment dataSegment() {
        if (!finished) {
            throw new IllegalStateException("buffer not finished");
        }
        return buf.asSlice(space, (long) capacity() - space);
    }

    /// @return a copy of the finished FlatBuffer as a byte array
    public byte[] sizedByteArray() {
        return dataSegment().toArray(ValueLayout.JAVA_BYTE);
    }
}
