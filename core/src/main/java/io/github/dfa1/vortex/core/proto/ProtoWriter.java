package io.github.dfa1.vortex.core.proto;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/// Growing-buffer writer for proto3 wire-format payloads.
/// Generated record types call into this from their `encode()` methods.
/// Package-private — generated code lives in the same package.
final class ProtoWriter {

    private byte[] buf;
    private int pos;

    ProtoWriter() {
        this.buf = new byte[64];
        this.pos = 0;
    }

    byte[] toByteArray() {
        return Arrays.copyOf(buf, pos);
    }

    void writeTag(int fieldNumber, int wireType) {
        writeVarint64(WireType.tag(fieldNumber, wireType));
    }

    void writeVarint32(int value) {
        writeVarint64(value & 0xffffffffL);
    }

    void writeVarint64(long value) {
        while ((value & ~0x7fL) != 0L) {
            ensure(1);
            buf[pos++] = (byte) ((value & 0x7f) | 0x80);
            value >>>= 7;
        }
        ensure(1);
        buf[pos++] = (byte) value;
    }

    void writeSint64(long value) {
        writeVarint64((value << 1) ^ (value >> 63));
    }

    void writeFixed32(int value) {
        ensure(4);
        buf[pos] = (byte) value;
        buf[pos + 1] = (byte) (value >>> 8);
        buf[pos + 2] = (byte) (value >>> 16);
        buf[pos + 3] = (byte) (value >>> 24);
        pos += 4;
    }

    void writeFixed64(long value) {
        ensure(8);
        for (int i = 0; i < 8; i++) {
            buf[pos + i] = (byte) (value >>> (i * 8));
        }
        pos += 8;
    }

    void writeFloat(float value) {
        writeFixed32(Float.floatToRawIntBits(value));
    }

    void writeDouble(double value) {
        writeFixed64(Double.doubleToRawLongBits(value));
    }

    void writeBool(boolean value) {
        ensure(1);
        buf[pos++] = (byte) (value ? 1 : 0);
    }

    /// Writes a length-prefixed UTF-8 byte sequence.
    void writeString(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarint32(bytes.length);
        writeRaw(bytes);
    }

    /// Writes a length-prefixed raw byte sequence.
    void writeBytes(byte[] value) {
        writeVarint32(value.length);
        writeRaw(value);
    }

    /// Writes an already-encoded nested message as a length-prefixed block.
    void writeEmbedded(byte[] encoded) {
        writeVarint32(encoded.length);
        writeRaw(encoded);
    }

    /// Reserves space for a length-delimited region whose payload size is unknown until written.
    /// Returns a mark to pass back to [#endLenDelim(int)]; the caller writes the payload
    /// in between. Avoids the alloc/copy round-trip of writing into a temporary `ProtoWriter`.
    ///
    /// Reserves the worst-case 5 bytes for a varint32 length; [#endLenDelim] backpatches
    /// the actual length and shifts the payload left if a shorter varint suffices.
    int beginLenDelim() {
        ensure(MAX_LEN_VARINT);
        int mark = pos;
        pos += MAX_LEN_VARINT;
        return mark;
    }

    /// Finalises a length-delimited region opened by [#beginLenDelim()].
    /// Writes the payload length as a varint at the reserved offset and shifts the payload
    /// left if the varint is shorter than 5 bytes.
    void endLenDelim(int mark) {
        int payloadStart = mark + MAX_LEN_VARINT;
        int payloadEnd = pos;
        int payloadLen = payloadEnd - payloadStart;
        int lenVarintSize = varintSize(payloadLen);
        if (lenVarintSize < MAX_LEN_VARINT) {
            int shift = MAX_LEN_VARINT - lenVarintSize;
            System.arraycopy(buf, payloadStart, buf, payloadStart - shift, payloadLen);
            pos -= shift;
        }
        // Write the varint at offset `mark` without disturbing `pos`.
        int writeAt = mark;
        long v = payloadLen & 0xffffffffL;
        while ((v & ~0x7fL) != 0L) {
            buf[writeAt++] = (byte) ((v & 0x7f) | 0x80);
            v >>>= 7;
        }
        buf[writeAt] = (byte) v;
    }

    private static int varintSize(int value) {
        // Branchless: derive varint byte count from position of highest set bit.
        // `| 1` collapses value=0 into the 1-byte case (otherwise numberOfLeadingZeros(0)=32 → 0 bytes).
        // (31 - lz) is the index of the highest set bit (0..31); dividing by 7 and adding 1
        // maps bit-width → varint byte count (1..5). Compiles to LZCNT + sub + strength-reduced
        // divide-by-7, ~3 cycles vs the 4-branch cascade.
        return (31 - Integer.numberOfLeadingZeros(value | 1)) / 7 + 1;
    }

    private static final int MAX_LEN_VARINT = 5;

    private void writeRaw(byte[] bytes) {
        ensure(bytes.length);
        System.arraycopy(bytes, 0, buf, pos, bytes.length);
        pos += bytes.length;
    }

    private static final int MAX_CAP = Integer.MAX_VALUE - 8;

    private void ensure(int extra) {
        if (extra < 0 || pos > MAX_CAP - extra) {
            throw new OutOfMemoryError("proto writer would exceed " + MAX_CAP + " bytes");
        }
        int needed = pos + extra;
        if (needed > buf.length) {
            long newCap = ((long) buf.length) << 1;
            while (newCap < needed) {
                newCap <<= 1;
            }
            if (newCap > MAX_CAP) {
                newCap = MAX_CAP;
            }
            buf = Arrays.copyOf(buf, (int) newCap);
        }
    }
}
