package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.array.ByteArray;
import io.github.dfa1.vortex.core.array.IntArray;
import io.github.dfa1.vortex.core.array.LongArray;
import io.github.dfa1.vortex.core.array.ShortArray;
import io.github.dfa1.vortex.encoding.EncodingId;
import io.github.dfa1.vortex.encoding.PTypeIO;
import io.github.dfa1.vortex.encoding.SegmentBroadcast;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/// Read-only decoder for {@code vortex.zigzag} — zigzag-decoded signed integers.
public final class ZigZagEncodingDecoder implements EncodingDecoder {

    /// Public no-arg constructor required by {@link java.util.ServiceLoader}.
    public ZigZagEncodingDecoder() {
    }

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_ZIGZAG;
    }

    @Override
    public boolean accepts(DType dtype) {
        if (!(dtype instanceof DType.Primitive p)) {
            return false;
        }
        PType pt = p.ptype();
        return pt == PType.I8 || pt == PType.I16 || pt == PType.I32 || pt == PType.I64;
    }

    @Override
    public Array decode(DecodeContext ctx) {
        if (!(ctx.dtype() instanceof DType.Primitive p)) {
            throw new VortexException(EncodingId.VORTEX_ZIGZAG, "expected primitive dtype, got " + ctx.dtype());
        }
        PType signed = p.ptype();
        PType unsigned = toUnsigned(signed);
        long n = ctx.rowCount();

        MemorySegment src = ctx.decodeChildSegment(0, new DType.Primitive(unsigned, false), n);
        int elemBytes = signed.byteSize();
        long srcCap = SegmentBroadcast.capacity(src, elemBytes);
        MemorySegment dst = ctx.arena().allocate(n * elemBytes);

        return switch (signed) {
            case I8 -> {
                for (long i = 0; i < n; i++) {
                    int u = Byte.toUnsignedInt(src.get(ValueLayout.JAVA_BYTE, i % srcCap));
                    dst.set(ValueLayout.JAVA_BYTE, i, (byte) ((u >>> 1) ^ -(u & 1)));
                }
                yield new ByteArray(ctx.dtype(), n, dst);
            }
            case I16 -> {
                for (long i = 0; i < n; i++) {
                    int u = Short.toUnsignedInt(src.get(PTypeIO.LE_SHORT, (i % srcCap) * 2));
                    dst.set(PTypeIO.LE_SHORT, i * 2, (short) ((u >>> 1) ^ -(u & 1)));
                }
                yield new ShortArray(ctx.dtype(), n, dst);
            }
            case I32 -> {
                for (long i = 0; i < n; i++) {
                    int u = src.get(PTypeIO.LE_INT, (i % srcCap) * 4);
                    dst.set(PTypeIO.LE_INT, i * 4, (u >>> 1) ^ -(u & 1));
                }
                yield new IntArray(ctx.dtype(), n, dst);
            }
            case I64 -> {
                for (long i = 0; i < n; i++) {
                    long u = src.get(PTypeIO.LE_LONG, (i % srcCap) * 8);
                    dst.set(PTypeIO.LE_LONG, i * 8, (u >>> 1) ^ -(u & 1));
                }
                yield new LongArray(ctx.dtype(), n, dst);
            }
            default -> throw new VortexException(EncodingId.VORTEX_ZIGZAG, "unreachable");
        };
    }

    private static PType toUnsigned(PType signed) {
        return switch (signed) {
            case I8 -> PType.U8;
            case I16 -> PType.U16;
            case I32 -> PType.U32;
            case I64 -> PType.U64;
            default -> throw new VortexException(EncodingId.VORTEX_ZIGZAG, "not a signed integer: " + signed);
        };
    }
}
