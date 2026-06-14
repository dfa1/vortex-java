package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.encoding.EncodingId;
import io.github.dfa1.vortex.encoding.PTypeIO;
import io.github.dfa1.vortex.proto.ScalarValue;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.ArraySegments;
import io.github.dfa1.vortex.reader.array.BoolArray;
import io.github.dfa1.vortex.reader.array.LazyForIntArray;
import io.github.dfa1.vortex.reader.array.LazyForLongArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.MaterializedByteArray;
import io.github.dfa1.vortex.reader.array.MaterializedDoubleArray;
import io.github.dfa1.vortex.reader.array.MaterializedShortArray;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;

/// Read-only decoder for {@code fastlanes.for} (Frame of Reference).
public final class FrameOfReferenceEncodingDecoder implements EncodingDecoder {

    /// Public no-arg constructor required by {@link java.util.ServiceLoader}.
    public FrameOfReferenceEncodingDecoder() {
    }

    @Override
    public EncodingId encodingId() {
        return EncodingId.FASTLANES_FOR;
    }

    @Override
    public boolean accepts(DType dtype) {
        return dtype instanceof DType.Primitive p && !p.ptype().isFloating();
    }

    @Override
    public Array decode(DecodeContext ctx) {
        ByteBuffer rawMeta = ctx.metadata();
        if (rawMeta == null || !rawMeta.hasRemaining()) {
            throw new VortexException(EncodingId.FASTLANES_FOR, "missing metadata");
        }
        ScalarValue scalar;
        try {
            MemorySegment metaSeg = MemorySegment.ofBuffer(rawMeta.duplicate());
            scalar = ScalarValue.decode(metaSeg, 0, metaSeg.byteSize());
        } catch (IOException e) {
            throw new VortexException(EncodingId.FASTLANES_FOR, "invalid metadata", e);
        }

        Array encoded = ctx.decodeChild(0);

        BoolArray validity = null;
        Array rawEncoded = encoded;
        if (encoded instanceof MaskedArray masked) {
            rawEncoded = masked.inner();
            validity = masked.validity();
        }

        if (!(ctx.dtype() instanceof DType.Primitive p)) {
            throw new VortexException(EncodingId.FASTLANES_FOR, "expected primitive dtype, got " + ctx.dtype());
        }

        long ref = referenceValue(scalar);
        if (ref == 0L) {
            return validity != null ? new MaskedArray(rawEncoded, validity) : rawEncoded;
        }

        MemorySegment src = ArraySegments.of(rawEncoded);
        long n = ctx.rowCount();
        Array result = switch (p.ptype()) {
            case I64, U64 -> new LazyForLongArray(ctx.dtype(), n, src, ref, ctx.arena());
            case I32, U32 -> new LazyForIntArray(ctx.dtype(), n, src, (int) ref, ctx.arena());
            default -> materialiseEager(ctx, src, n, p.ptype(), ref);
        };
        return validity != null ? new MaskedArray(result, validity) : result;
    }

    private static Array materialiseEager(DecodeContext ctx, MemorySegment src, long n, PType ptype, long ref) {
        MemorySegment dst = applyReference(src, n, ptype, ref, ctx.arena());
        return switch (ptype) {
            case F64 -> new MaterializedDoubleArray(ctx.dtype(), n, dst);
            case I16, U16 -> new MaterializedShortArray(ctx.dtype(), n, dst);
            case I8, U8 -> new MaterializedByteArray(ctx.dtype(), n, dst);
            default -> throw new VortexException(EncodingId.FASTLANES_FOR, "unsupported ptype " + ptype);
        };
    }

    private static long referenceValue(ScalarValue scalar) {
        if (scalar.int64_value() != null) {
            return scalar.int64_value();
        }
        if (scalar.uint64_value() != null) {
            return scalar.uint64_value();
        }
        return 0L;
    }

    private static MemorySegment applyReference(MemorySegment src, long n, PType ptype, long ref, SegmentAllocator arena) {
        int wordBytes = ptype.byteSize();
        MemorySegment dst = arena.allocate(n * wordBytes);
        switch (ptype) {
            case I8, U8 -> {
                for (long off = 0, end = n; off < end; off++) {
                    dst.set(ValueLayout.JAVA_BYTE, off, (byte) (src.get(ValueLayout.JAVA_BYTE, off) + (byte) ref));
                }
            }
            case I16, U16 -> {
                for (long off = 0, end = n * 2; off < end; off += 2) {
                    dst.set(PTypeIO.LE_SHORT, off, (short) (src.get(PTypeIO.LE_SHORT, off) + (short) ref));
                }
            }
            default -> throw new VortexException(EncodingId.FASTLANES_FOR, "unsupported ptype " + ptype);
        }
        return dst;
    }
}
