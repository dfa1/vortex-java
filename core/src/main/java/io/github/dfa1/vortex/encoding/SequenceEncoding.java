package io.github.dfa1.vortex.encoding;

import com.google.protobuf.InvalidProtocolBufferException;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.array.ByteArray;
import io.github.dfa1.vortex.core.array.DoubleArray;
import io.github.dfa1.vortex.core.array.Float16Array;
import io.github.dfa1.vortex.core.array.FloatArray;
import io.github.dfa1.vortex.core.array.IntArray;
import io.github.dfa1.vortex.core.array.LongArray;
import io.github.dfa1.vortex.core.array.ShortArray;
import io.github.dfa1.vortex.proto.EncodingProtos;
import io.github.dfa1.vortex.proto.ScalarProtos;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.util.List;

/// Decoder for {@code vortex.sequence}: {@code A[i] = base + i * multiplier}.
///
/// No buffers, no children. Metadata is a protobuf {@code SequenceMetadata}
/// with {@code base} (tag 1) and {@code multiplier} (tag 2) as {@code ScalarValue}.
/// Output is allocated on the heap; not backed by the file's mapped region.
public final class SequenceEncoding implements Encoding {

    /// Creates a new {@code SequenceEncoding} instance; use via {@link Registry}.
    public SequenceEncoding() {
    }

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_SEQUENCE;
    }

    @Override
    public boolean accepts(DType dtype) {
        return dtype instanceof DType.Primitive;
    }

    @Override
    public EncodeResult encode(DType dtype, Object data, EncodeContext ctx) {
        return Encoder.encode(dtype, data);
    }

    @Override
    public Array decode(DecodeContext ctx) {
        return Decoder.decode(ctx);
    }

    private static final class Encoder {

        private static EncodeResult encode(DType dtype, Object data) {
            if (!(dtype instanceof DType.Primitive p)) {
                throw new VortexException(EncodingId.VORTEX_SEQUENCE, "encode only supports Primitive dtype, got " + dtype);
            }
            PType pt = p.ptype();
            return switch (pt) {
                case I8, I16, I32, I64, U8, U16, U32, U64 -> encodeInteger(pt, data);
                case F32 -> encodeF32((float[]) data);
                case F64 -> encodeF64((double[]) data);
                case F16 -> encodeF16((short[]) data);
            };
        }

        private static EncodeResult encodeInteger(PType pt, Object data) {
            int n = intArrayLength(pt, data);
            long base = 0;
            long multiplier = 0;
            if (n > 0) {
                base = readLong(pt, data, 0);
                multiplier = n > 1 ? readLong(pt, data, 1) - base : 0;
                for (int i = 2; i < n; i++) {
                    long expected = base + (long) i * multiplier;
                    if (readLong(pt, data, i) != expected) {
                        throw new VortexException(EncodingId.VORTEX_SEQUENCE, "not an arithmetic sequence at index " + i);
                    }
                }
            }
            ScalarProtos.ScalarValue baseScalar = buildIntScalar(pt, base);
            ScalarProtos.ScalarValue mulScalar = buildIntScalar(pt, multiplier);
            return buildResult(baseScalar, mulScalar);
        }

        private static EncodeResult encodeF32(float[] data) {
            float base = data.length > 0 ? data[0] : 0f;
            float mul = data.length > 1 ? data[1] - base : 0f;
            for (int i = 2; i < data.length; i++) {
                if (data[i] != base + (float) i * mul) {
                    throw new VortexException(EncodingId.VORTEX_SEQUENCE, "not an arithmetic sequence at index " + i);
                }
            }
            ScalarProtos.ScalarValue baseScalar = ScalarProtos.ScalarValue.newBuilder().setF32Value(base).build();
            ScalarProtos.ScalarValue mulScalar = ScalarProtos.ScalarValue.newBuilder().setF32Value(mul).build();
            return buildResult(baseScalar, mulScalar);
        }

        private static EncodeResult encodeF64(double[] data) {
            double base = data.length > 0 ? data[0] : 0.0;
            double mul = data.length > 1 ? data[1] - base : 0.0;
            for (int i = 2; i < data.length; i++) {
                if (data[i] != base + (double) i * mul) {
                    throw new VortexException(EncodingId.VORTEX_SEQUENCE, "not an arithmetic sequence at index " + i);
                }
            }
            ScalarProtos.ScalarValue baseScalar = ScalarProtos.ScalarValue.newBuilder().setF64Value(base).build();
            ScalarProtos.ScalarValue mulScalar = ScalarProtos.ScalarValue.newBuilder().setF64Value(mul).build();
            return buildResult(baseScalar, mulScalar);
        }

        private static EncodeResult encodeF16(short[] data) {
            short baseShort = data.length > 0 ? data[0] : 0;
            float baseF = Float.float16ToFloat(baseShort);
            float mulF = data.length > 1 ? Float.float16ToFloat(data[1]) - baseF : 0f;
            short mulShort = Float.floatToFloat16(mulF);
            for (int i = 2; i < data.length; i++) {
                short expected = Float.floatToFloat16(baseF + (float) i * mulF);
                if (data[i] != expected) {
                    throw new VortexException(EncodingId.VORTEX_SEQUENCE, "not an arithmetic sequence at index " + i);
                }
            }
            ScalarProtos.ScalarValue baseScalar = ScalarProtos.ScalarValue.newBuilder()
                                                          .setF16Value(Short.toUnsignedLong(baseShort)).build();
            ScalarProtos.ScalarValue mulScalar = ScalarProtos.ScalarValue.newBuilder()
                                                        .setF16Value(Short.toUnsignedLong(mulShort)).build();
            return buildResult(baseScalar, mulScalar);
        }

        private static EncodeResult buildResult(ScalarProtos.ScalarValue base, ScalarProtos.ScalarValue mul) {
            EncodingProtos.SequenceMetadata meta = EncodingProtos.SequenceMetadata.newBuilder()
                                                           .setBase(base)
                                                           .setMultiplier(mul)
                                                           .build();
            ByteBuffer metaBuf = ByteBuffer.wrap(meta.toByteArray());
            EncodeNode node = new EncodeNode(EncodingId.VORTEX_SEQUENCE, metaBuf, new EncodeNode[0], new int[]{});
            return new EncodeResult(node, List.of(), null, null);
        }

        private static ScalarProtos.ScalarValue buildIntScalar(PType pt, long value) {
            return switch (pt) {
                case U8, U16, U32, U64 -> ScalarProtos.ScalarValue.newBuilder().setUint64Value(value).build();
                default -> ScalarProtos.ScalarValue.newBuilder().setInt64Value(value).build();
            };
        }

        private static int intArrayLength(PType pt, Object data) {
            return switch (pt) {
                case I8, U8 -> ((byte[]) data).length;
                case I16, U16 -> ((short[]) data).length;
                case I32, U32 -> ((int[]) data).length;
                case I64, U64 -> ((long[]) data).length;
                default -> throw new VortexException(EncodingId.VORTEX_SEQUENCE, "unsupported ptype: " + pt);
            };
        }

        private static long readLong(PType pt, Object data, int i) {
            return switch (pt) {
                case I8, U8 -> ((byte[]) data)[i];
                case I16, U16 -> ((short[]) data)[i];
                case I32, U32 -> ((int[]) data)[i];
                case I64, U64 -> ((long[]) data)[i];
                default -> throw new VortexException(EncodingId.VORTEX_SEQUENCE, "unsupported ptype: " + pt);
            };
        }
    }

    private static final class Decoder {

        private static Array decode(DecodeContext ctx) {
            ByteBuffer metaBuf = ctx.metadata();
            if (metaBuf == null || !metaBuf.hasRemaining()) {
                throw new VortexException(EncodingId.VORTEX_SEQUENCE, "missing metadata");
            }
            EncodingProtos.SequenceMetadata meta;
            try {
                meta = EncodingProtos.SequenceMetadata.parseFrom(metaBuf.duplicate());
            } catch (InvalidProtocolBufferException e) {
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
                EncodingProtos.SequenceMetadata meta, PType pt, long n, DType dtype, SegmentAllocator arena
        ) {
            long base = signedValue(meta.getBase());
            long mul = signedValue(meta.getMultiplier());
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
                case I64, U64 -> new LongArray(dtype, n, seg);
                case I32, U32 -> new IntArray(dtype, n, seg);
                case I16, U16 -> new ShortArray(dtype, n, seg);
                case I8, U8 -> new ByteArray(dtype, n, seg);
                default -> throw new VortexException(EncodingId.VORTEX_SEQUENCE, "unsupported ptype " + pt);
            };
        }

        private static Array decodeF32(EncodingProtos.SequenceMetadata meta, long n, DType dtype, SegmentAllocator arena) {
            float base = meta.getBase().getF32Value();
            float mul = meta.getMultiplier().getF32Value();
            MemorySegment seg = arena.allocate(n * 4L);
            for (long i = 0; i < n; i++) {
                seg.setAtIndex(PTypeIO.LE_FLOAT, i, base + i * mul);
            }
            return new FloatArray(dtype, n, seg);
        }

        private static Array decodeF64(EncodingProtos.SequenceMetadata meta, long n, DType dtype, SegmentAllocator arena) {
            double base = meta.getBase().getF64Value();
            double mul = meta.getMultiplier().getF64Value();
            MemorySegment seg = arena.allocate(n * 8L);
            for (long i = 0; i < n; i++) {
                seg.setAtIndex(PTypeIO.LE_DOUBLE, i, base + i * mul);
            }
            return new DoubleArray(dtype, n, seg);
        }

        private static Array decodeF16(EncodingProtos.SequenceMetadata meta, long n, DType dtype, SegmentAllocator arena) {
            short baseShort = (short) meta.getBase().getF16Value();
            short mulShort = (short) meta.getMultiplier().getF16Value();
            float base = Float.float16ToFloat(baseShort);
            float mul = Float.float16ToFloat(mulShort);
            MemorySegment seg = arena.allocate(n * 2L);
            for (long i = 0; i < n; i++) {
                seg.setAtIndex(PTypeIO.LE_SHORT, i, Float.floatToFloat16(base + i * mul));
            }
            return new Float16Array(dtype, n, seg);
        }

        private static long signedValue(io.github.dfa1.vortex.proto.ScalarProtos.ScalarValue sv) {
            return switch (sv.getKindCase()) {
                case INT64_VALUE -> sv.getInt64Value();
                case UINT64_VALUE -> sv.getUint64Value();
                case KIND_NOT_SET -> 0L;
                default ->
                        throw new VortexException(EncodingId.VORTEX_SEQUENCE, "unexpected scalar kind " + sv.getKindCase());
            };
        }
    }
}
