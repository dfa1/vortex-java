package io.github.dfa1.vortex.encoding;

import com.google.protobuf.InvalidProtocolBufferException;
import dev.vortex.proto.ScalarProtos;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/// Decoder for {@code fastlanes.for} (Frame of Reference).
///
/// <p>Metadata: raw {@code ScalarValue} protobuf bytes — the reference (minimum) value.
/// Child slot 0: encoded residuals array (same dtype as parent, typically bitpacked).
/// Decode: {@code output[i] = encoded[i] + reference} (wrapping arithmetic).
public final class FrameOfReferenceCodec implements Decoder {

    @Override
    public String encodingId() {
        return "fastlanes.for";
    }

    @Override
    public Array decode(DecodeContext ctx) {
        ByteBuffer rawMeta = ctx.metadata();
        if (rawMeta == null || !rawMeta.hasRemaining()) {
            throw new IllegalStateException("fastlanes.for: missing metadata");
        }
        byte[] metaBytes = new byte[rawMeta.remaining()];
        rawMeta.duplicate().get(metaBytes);

        ScalarProtos.ScalarValue scalar;
        try {
            scalar = ScalarProtos.ScalarValue.parseFrom(metaBytes);
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalStateException("fastlanes.for: invalid metadata", e);
        }

        Array encoded;
        try {
            encoded = ctx.decodeChild(0);
        } catch (IOException e) {
            throw new IllegalStateException("fastlanes.for: failed to decode child", e);
        }

        if (!(ctx.dtype() instanceof DType.Primitive p)) {
            throw new IllegalStateException("fastlanes.for: expected primitive dtype, got " + ctx.dtype());
        }

        long ref = referenceValue(scalar);
        if (ref == 0L) {
            return encoded;
        }

        MemorySegment src = encoded.buffer(0);
        long n = ctx.rowCount();
        MemorySegment dst = applyReference(src, n, p.ptype(), ref);
        return new Array(ctx.dtype(), n, new MemorySegment[]{dst}, new Array[0], ArrayStats.empty());
    }

    private static long referenceValue(ScalarProtos.ScalarValue scalar) {
        return switch (scalar.getKindCase()) {
            case INT64_VALUE  -> scalar.getInt64Value();
            case UINT64_VALUE -> scalar.getUint64Value();
            case KIND_NOT_SET -> 0L;
            default -> throw new IllegalStateException(
                "fastlanes.for: unexpected scalar kind " + scalar.getKindCase());
        };
    }

    private static MemorySegment applyReference(MemorySegment src, long n, PType ptype, long ref) {
        int wordBytes = ptype.byteSize();
        byte[] bytes = new byte[(int) (n * wordBytes)];
        MemorySegment dst = MemorySegment.ofArray(bytes);
        switch (ptype) {
            case I8, U8 -> {
                for (long off = 0, end = n; off < end; off++) {
                    byte v = src.get(ValueLayout.JAVA_BYTE, off);
                    dst.set(ValueLayout.JAVA_BYTE, off, (byte) (v + (byte) ref));
                }
            }
            case I16, U16 -> {
                var layout = ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
                for (long off = 0, end = n * 2; off < end; off += 2) {
                    short v = src.get(layout, off);
                    dst.set(layout, off, (short) (v + (short) ref));
                }
            }
            case I32, U32 -> {
                var layout = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
                for (long off = 0, end = n * 4; off < end; off += 4) {
                    int v = src.get(layout, off);
                    dst.set(layout, off, v + (int) ref);
                }
            }
            case I64, U64 -> {
                var layout = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
                for (long off = 0, end = n * 8; off < end; off += 8) {
                    long v = src.get(layout, off);
                    dst.set(layout, off, v + ref);
                }
            }
            default -> throw new UnsupportedOperationException(
                "fastlanes.for: unsupported ptype " + ptype);
        }
        return dst;
    }
}
