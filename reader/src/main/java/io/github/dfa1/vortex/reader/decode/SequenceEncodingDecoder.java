package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.io.PTypeIO;
import io.github.dfa1.vortex.core.proto.ProtoScalarValue;
import io.github.dfa1.vortex.core.proto.ProtoSequenceMetadata;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.MaterializedByteArray;
import io.github.dfa1.vortex.reader.array.MaterializedDoubleArray;
import io.github.dfa1.vortex.reader.array.MaterializedFloat16Array;
import io.github.dfa1.vortex.reader.array.MaterializedFloatArray;
import io.github.dfa1.vortex.reader.array.MaterializedIntArray;
import io.github.dfa1.vortex.reader.array.MaterializedLongArray;
import io.github.dfa1.vortex.reader.array.MaterializedShortArray;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.ValueLayout;

/// Read-only decoder for `vortex.sequence` — `A[i] = base + i * multiplier`.
public final class SequenceEncodingDecoder implements EncodingDecoder {

    /// Public no-arg constructor required by [java.util.ServiceLoader].
    public SequenceEncodingDecoder() {
    }

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_SEQUENCE;
    }

    @Override
    public Array decode(DecodeContext ctx) {
        MemorySegment metaBuf = ctx.metadata();
        if (metaBuf == null || metaBuf.byteSize() == 0) {
            throw new VortexException(EncodingId.VORTEX_SEQUENCE, "missing metadata");
        }
        ProtoSequenceMetadata meta;
        try {
            MemorySegment seg = metaBuf;
            meta = ProtoSequenceMetadata.decode(seg, 0, seg.byteSize());
        } catch (IOException e) {
            throw new VortexException(EncodingId.VORTEX_SEQUENCE, "invalid metadata", e);
        }

        if (!(ctx.dtype() instanceof DType.Primitive p)) {
            throw new VortexException(EncodingId.VORTEX_SEQUENCE, "expected primitive dtype, got " + ctx.dtype());
        }

        long n = ctx.rowCount();
        PType pt = p.ptype();
        return switch (pt) {
            case I8, I16, I32, I64, U8, U16, U32, U64 -> decodeInteger(meta, pt, n, ctx.dtype(), ctx.arena());
            case F32 -> decodeF32(meta, n, ctx.dtype(), ctx.arena());
            case F64 -> decodeF64(meta, n, ctx.dtype(), ctx.arena());
            case F16 -> decodeF16(meta, n, ctx.dtype(), ctx.arena());
        };
    }

    private static Array decodeInteger(
            ProtoSequenceMetadata meta, PType pt, long n, DType dtype, SegmentAllocator arena
    ) {
        long base = signedValue(meta.base());
        long mul = signedValue(meta.multiplier());
        int elemBytes = pt.byteSize();
        MemorySegment seg = arena.allocate(n * elemBytes);
        for (long i = 0; i < n; i++) {
            long v = base + i * mul;
            switch (pt) {
                case I8, U8 -> seg.set(ValueLayout.JAVA_BYTE, i, (byte) v);
                case I16, U16 -> seg.setAtIndex(PTypeIO.LE_SHORT, i, (short) v);
                case I32, U32 -> seg.setAtIndex(PTypeIO.LE_INT, i, (int) v);
                case I64, U64 -> seg.setAtIndex(PTypeIO.LE_LONG, i, v);
                default -> throw new IllegalStateException("unreachable");
            }
        }
        return switch (pt) {
            case I64, U64 -> new MaterializedLongArray(dtype, n, seg);
            case I32, U32 -> new MaterializedIntArray(dtype, n, seg);
            case I16, U16 -> new MaterializedShortArray(dtype, n, seg);
            case I8, U8 -> new MaterializedByteArray(dtype, n, seg);
            default -> throw new VortexException(EncodingId.VORTEX_SEQUENCE, "unsupported ptype " + pt);
        };
    }

    private static Array decodeF32(ProtoSequenceMetadata meta, long n, DType dtype, SegmentAllocator arena) {
        float base = meta.base().f32_value();
        float mul = meta.multiplier().f32_value();
        MemorySegment seg = arena.allocate(n * 4L);
        for (long i = 0; i < n; i++) {
            seg.setAtIndex(PTypeIO.LE_FLOAT, i, base + i * mul);
        }
        return new MaterializedFloatArray(dtype, n, seg);
    }

    private static Array decodeF64(ProtoSequenceMetadata meta, long n, DType dtype, SegmentAllocator arena) {
        double base = meta.base().f64_value();
        double mul = meta.multiplier().f64_value();
        MemorySegment seg = arena.allocate(n * 8L);
        for (long i = 0; i < n; i++) {
            seg.setAtIndex(PTypeIO.LE_DOUBLE, i, base + i * mul);
        }
        return new MaterializedDoubleArray(dtype, n, seg);
    }

    private static Array decodeF16(ProtoSequenceMetadata meta, long n, DType dtype, SegmentAllocator arena) {
        short baseShort = (short) meta.base().f16_value().longValue();
        short mulShort = (short) meta.multiplier().f16_value().longValue();
        float base = Float.float16ToFloat(baseShort);
        float mul = Float.float16ToFloat(mulShort);
        MemorySegment seg = arena.allocate(n * 2L);
        for (long i = 0; i < n; i++) {
            seg.setAtIndex(PTypeIO.LE_SHORT, i, Float.floatToFloat16(base + i * mul));
        }
        return new MaterializedFloat16Array(dtype, n, seg);
    }

    private static long signedValue(ProtoScalarValue sv) {
        if (sv == null) {
            return 0L;
        }
        if (sv.int64_value() != null) {
            return sv.int64_value();
        }
        if (sv.uint64_value() != null) {
            return sv.uint64_value();
        }
        return 0L;
    }
}
