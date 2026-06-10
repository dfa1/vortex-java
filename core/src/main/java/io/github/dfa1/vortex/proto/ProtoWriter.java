package io.github.dfa1.vortex.proto;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/// Growing-buffer writer for proto3 wire-format payloads.
/// Generated record types call into this from their {@code encode()} methods.
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
