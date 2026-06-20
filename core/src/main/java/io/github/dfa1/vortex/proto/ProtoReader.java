package io.github.dfa1.vortex.proto;

import static io.github.dfa1.vortex.encoding.PTypeIO.LE_INT;
import static io.github.dfa1.vortex.encoding.PTypeIO.LE_LONG;
import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

/// Stateful cursor over a [MemorySegment] that decodes proto3 wire-format primitives.
/// Generated record types call into this from their `decode(...)` static factories.
/// Package-private — generated code lives in the same package.
final class ProtoReader {

    private static final ValueLayout.OfByte BYTE = ValueLayout.JAVA_BYTE;

    private final MemorySegment seg;
    private final long end;
    private long pos;

    ProtoReader(MemorySegment seg, long off, long len) {
        if (off < 0 || len < 0 || off + len > seg.byteSize()) {
            throw new IndexOutOfBoundsException("proto reader range out of bounds: off=" + off + " len=" + len + " segSize=" + seg.byteSize());
        }
        this.seg = seg;
        this.pos = off;
        this.end = off + len;
    }

    boolean hasMore() {
        return pos < end;
    }

    long position() {
        return pos;
    }

    /// Reads an unsigned varint into an `int`. Up to 5 bytes.
    int readVarint32() throws IOException {
        long v = readVarint64();
        return (int) v;
    }

    /// Reads an unsigned varint into a `long`. Up to 10 bytes.
    long readVarint64() throws IOException {
        long result = 0;
        int shift = 0;
        while (shift < 64) {
            if (pos >= end) {
                throw new IOException("truncated varint at " + pos);
            }
            byte b = seg.get(BYTE, pos++);
            result |= ((long) (b & 0x7f)) << shift;
            if ((b & 0x80) == 0) {
                return result;
            }
            shift += 7;
        }
        throw new IOException("varint overflow at " + pos);
    }

    /// Reads a zigzag-encoded signed varint into a `long`.
    long readSint64() throws IOException {
        long n = readVarint64();
        return (n >>> 1) ^ -(n & 1);
    }

    /// Reads a little-endian 32-bit fixed value.
    int readFixed32() throws IOException {
        if (pos + 4 > end) {
            throw new IOException("truncated fixed32 at " + pos);
        }
        int v = seg.get(LE_INT, pos);
        pos += 4;
        return v;
    }

    /// Reads a little-endian 64-bit fixed value.
    long readFixed64() throws IOException {
        if (pos + 8 > end) {
            throw new IOException("truncated fixed64 at " + pos);
        }
        long v = seg.get(LE_LONG, pos);
        pos += 8;
        return v;
    }

    float readFloat() throws IOException {
        return Float.intBitsToFloat(readFixed32());
    }

    double readDouble() throws IOException {
        return Double.longBitsToDouble(readFixed64());
    }

    boolean readBool() throws IOException {
        return readVarint64() != 0L;
    }

    /// Reads a length-delimited UTF-8 string.
    String readString() throws IOException {
        int len = readVarint32();
        ensureBytes(len);
        byte[] bytes = new byte[len];
        MemorySegment.copy(seg, BYTE, pos, bytes, 0, len);
        pos += len;
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /// Reads a length-delimited byte sequence into a fresh `byte[]`.
    /// Use [#readLenDelimSegment()] for zero-copy access when the consumer can hold a segment slice.
    byte[] readBytes() throws IOException {
        int len = readVarint32();
        ensureBytes(len);
        byte[] bytes = new byte[len];
        MemorySegment.copy(seg, BYTE, pos, bytes, 0, len);
        pos += len;
        return bytes;
    }

    /// Returns a zero-copy slice covering the next length-delimited payload (length varint, then bytes).
    /// Used by generated code to decode nested messages without allocating intermediate arrays.
    MemorySegment readLenDelimSegment() throws IOException {
        int len = readVarint32();
        ensureBytes(len);
        MemorySegment slice = seg.asSlice(pos, len);
        pos += len;
        return slice;
    }

    /// Skips the field whose wire type is given. Used to discard unknown fields during decode.
    void skipField(int wireType) throws IOException {
        switch (wireType) {
            case WireType.VARINT -> {
                readVarint64();
            }
            case WireType.FIXED64 -> {
                if (pos + 8 > end) {
                    throw new IOException("truncated FIXED64 skip");
                }
                pos += 8;
            }
            case WireType.LEN -> {
                int len = readVarint32();
                ensureBytes(len);
                pos += len;
            }
            case WireType.FIXED32 -> {
                if (pos + 4 > end) {
                    throw new IOException("truncated FIXED32 skip");
                }
                pos += 4;
            }
            default -> throw new IOException("unsupported wire type for skip: " + wireType);
        }
    }

    /// Consumes a packed-repeated payload, invoking `consumer.accept(this)` for each element.
    /// The cursor advances over each element; loop ends when the packed region is exhausted.
    void readPacked(int len, PackedElementReader consumer) throws IOException {
        long packedEnd = pos + len;
        if (packedEnd > end) {
            throw new IOException("truncated packed region");
        }
        while (pos < packedEnd) {
            consumer.readOne(this);
        }
        if (pos != packedEnd) {
            throw new IOException("packed region overrun");
        }
    }

    @FunctionalInterface
    interface PackedElementReader {
        void readOne(ProtoReader reader) throws IOException;
    }

    private void ensureBytes(int len) throws IOException {
        if (len < 0 || pos + len > end) {
            throw new IOException("truncated len-delimited region: requested " + len + " bytes at " + pos);
        }
    }
}
