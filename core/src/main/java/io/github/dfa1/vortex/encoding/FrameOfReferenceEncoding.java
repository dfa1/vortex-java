package io.github.dfa1.vortex.encoding;

import com.google.protobuf.InvalidProtocolBufferException;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.array.BoolArray;
import io.github.dfa1.vortex.core.array.ByteArray;
import io.github.dfa1.vortex.core.array.DoubleArray;
import io.github.dfa1.vortex.core.array.IntArray;
import io.github.dfa1.vortex.core.array.LongArray;
import io.github.dfa1.vortex.core.array.MaskedArray;
import io.github.dfa1.vortex.core.array.ShortArray;
import io.github.dfa1.vortex.proto.ScalarProtos;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.util.List;

/// Decoder for {@code fastlanes.for} (Frame of Reference).
///
/// <p>Metadata: raw {@code ScalarValue} protobuf bytes — the reference (minimum) value.
/// Child slot 0: encoded residuals array (same dtype as parent, typically bitpacked).
/// Decode: {@code output[i] = encoded[i] + reference} (wrapping arithmetic).
public final class FrameOfReferenceEncoding implements Encoding {

    /// Creates a new {@code FrameOfReferenceEncoding} instance; use via {@link EncodingRegistry}.
    public FrameOfReferenceEncoding() {
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
    public EncodeResult encode(DType dtype, Object data, EncodeContext ctx) {
        return Encoder.encode(dtype, data, ctx);
    }

    @Override
    public CascadeStep encodeCascade(DType dtype, Object data, EncodeContext encodeCtx) {
        return Encoder.encodeCascade(dtype, data);
    }

    @Override
    public Array decode(DecodeContext ctx) {
        return Decoder.decode(ctx);
    }

    private static final class Encoder {

        private static EncodeResult encode(DType dtype, Object data, EncodeContext ctx) {
            if (!(dtype instanceof DType.Primitive p)) {
                throw new VortexException(EncodingId.FASTLANES_FOR, "expected primitive dtype, got " + dtype);
            }
            PType ptype = p.ptype();
            long[] longs = toLongs(data, ptype);
            int n = longs.length;

            long ref = computeRef(longs, n);
            MemorySegment residuals = toResidualBuffer(longs, ref, ptype, ctx);
            ByteBuffer meta = buildForMeta(ref, ptype);

            EncodeNode child = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, 0);
            EncodeNode root = new EncodeNode(EncodingId.FASTLANES_FOR, meta, new EncodeNode[]{child}, new int[0]);
            return new EncodeResult(root, List.of(residuals), null, null);
        }

        private static CascadeStep encodeCascade(DType dtype, Object data) {
            if (!(dtype instanceof DType.Primitive p)) {
                throw new VortexException(EncodingId.FASTLANES_FOR, "expected primitive dtype, got " + dtype);
            }
            PType ptype = p.ptype();
            long[] longs = toLongs(data, ptype);
            int n = longs.length;

            long ref = computeRef(longs, n);
            ByteBuffer meta = buildForMeta(ref, ptype);

            // residuals are the open child slot — compressor will bitpack them
            EncodeNode partialRoot = new EncodeNode(EncodingId.FASTLANES_FOR, meta, new EncodeNode[1], new int[0]);
            ChildSlot slot = new ChildSlot(dtype, residualsAsNativeArray(longs, ref, ptype), 0);
            return new CascadeStep(partialRoot, List.of(), List.of(slot), null, null, true);
        }

        private static long computeRef(long[] longs, int n) {
            long ref = n > 0 ? longs[0] : 0L;
            for (long v : longs) {
                if (v < ref) {
                    ref = v;
                }
            }
            return ref;
        }

        private static ByteBuffer buildForMeta(long ref, PType ptype) {
            boolean unsigned = switch (ptype) {
                case U8, U16, U32, U64 -> true;
                default -> false;
            };
            ScalarProtos.ScalarValue scalar = unsigned
                                                      ? ScalarProtos.ScalarValue.newBuilder().setUint64Value(ref).build()
                                                      : ScalarProtos.ScalarValue.newBuilder().setInt64Value(ref).build();
            return ByteBuffer.wrap(scalar.toByteArray());
        }

        /// Returns residuals as a Java primitive array of the correct type (for passing as ChildSlot data).
        private static Object residualsAsNativeArray(long[] longs, long ref, PType ptype) {
            int n = longs.length;
            return switch (ptype) {
                case I8, U8 -> {
                    byte[] r = new byte[n];
                    for (int i = 0; i < n; i++) {
                        r[i] = (byte) (longs[i] - ref);
                    }
                    yield r;
                }
                case I16, U16 -> {
                    short[] r = new short[n];
                    for (int i = 0; i < n; i++) {
                        r[i] = (short) (longs[i] - ref);
                    }
                    yield r;
                }
                case I32, U32 -> {
                    int[] r = new int[n];
                    for (int i = 0; i < n; i++) {
                        r[i] = (int) (longs[i] - ref);
                    }
                    yield r;
                }
                case I64, U64 -> {
                    long[] r = new long[n];
                    for (int i = 0; i < n; i++) {
                        r[i] = longs[i] - ref;
                    }
                    yield r;
                }
                default -> throw new VortexException(EncodingId.FASTLANES_FOR, "unsupported ptype: " + ptype);
            };
        }

        private static long[] toLongs(Object data, PType ptype) {
            return switch (ptype) {
                case I8, U8 -> {
                    byte[] arr = (byte[]) data;
                    long[] r = new long[arr.length];
                    for (int i = 0; i < arr.length; i++) {
                        r[i] = ptype == PType.U8 ? Byte.toUnsignedLong(arr[i]) : arr[i];
                    }
                    yield r;
                }
                case I16, U16 -> {
                    short[] arr = (short[]) data;
                    long[] r = new long[arr.length];
                    for (int i = 0; i < arr.length; i++) {
                        r[i] = ptype == PType.U16 ? Short.toUnsignedLong(arr[i]) : arr[i];
                    }
                    yield r;
                }
                case I32, U32 -> {
                    int[] arr = (int[]) data;
                    long[] r = new long[arr.length];
                    for (int i = 0; i < arr.length; i++) {
                        r[i] = ptype == PType.U32 ? Integer.toUnsignedLong(arr[i]) : arr[i];
                    }
                    yield r;
                }
                case I64, U64 -> (long[]) data;
                default -> throw new VortexException(EncodingId.FASTLANES_FOR, "unsupported ptype: " + ptype);
            };
        }

        private static MemorySegment toResidualBuffer(long[] longs, long ref, PType ptype, EncodeContext ctx) {
            int n = longs.length;
            int elemBytes = ptype.byteSize();
            MemorySegment seg = ctx.arena().allocate((long) n * elemBytes, elemBytes);
            for (int i = 0; i < n; i++) {
                long r = longs[i] - ref;
                PTypeIO.set(seg, (long) i * elemBytes, ptype, r);
            }
            return seg;
        }
    }

    private static final class Decoder {

        private static Array decode(DecodeContext ctx) {
            ByteBuffer rawMeta = ctx.metadata();
            if (rawMeta == null || !rawMeta.hasRemaining()) {
                throw new VortexException(EncodingId.FASTLANES_FOR, "missing metadata");
            }
            ScalarProtos.ScalarValue scalar;
            try {
                scalar = ScalarProtos.ScalarValue.parseFrom(rawMeta.duplicate());
            } catch (InvalidProtocolBufferException e) {
                throw new VortexException(EncodingId.FASTLANES_FOR, "invalid metadata", e);
            }

            Array encoded = ctx.decodeChild(0);

            // Nullable primitive: child decodes as MaskedArray; extract values and propagate validity.
            BoolArray validity = null;
            Array rawEncoded = encoded;
            if (encoded instanceof MaskedArray masked) {
                rawEncoded = masked.child(0);
                validity = (BoolArray) masked.child(1);
            }

            if (!(ctx.dtype() instanceof DType.Primitive p)) {
                throw new VortexException(EncodingId.FASTLANES_FOR, "expected primitive dtype, got " + ctx.dtype());
            }

            long ref = referenceValue(scalar);
            if (ref == 0L) {
                return validity != null ? new MaskedArray(rawEncoded, validity) : rawEncoded;
            }

            MemorySegment src = rawEncoded.buffer(0);
            long n = ctx.rowCount();
            MemorySegment dst = applyReference(src, n, p.ptype(), ref, ctx.arena());
            Array result = switch (p.ptype()) {
                case I64, U64 -> new LongArray(ctx.dtype(), n, dst);
                case I32, U32 -> new IntArray(ctx.dtype(), n, dst);
                case F64 -> new DoubleArray(ctx.dtype(), n, dst);
                case I16, U16 -> new ShortArray(ctx.dtype(), n, dst);
                case I8, U8 -> new ByteArray(ctx.dtype(), n, dst);
                default -> throw new VortexException(EncodingId.FASTLANES_FOR, "unsupported ptype " + p.ptype());
            };
            return validity != null ? new MaskedArray(result, validity) : result;
        }

        private static long referenceValue(ScalarProtos.ScalarValue scalar) {
            return switch (scalar.getKindCase()) {
                case INT64_VALUE -> scalar.getInt64Value();
                case UINT64_VALUE -> scalar.getUint64Value();
                case KIND_NOT_SET -> 0L;
                default -> throw new VortexException(EncodingId.FASTLANES_FOR,
                        "unexpected scalar kind " + scalar.getKindCase());
            };
        }

        private static MemorySegment applyReference(MemorySegment src, long n, PType ptype, long ref, SegmentAllocator arena) {
            int wordBytes = ptype.byteSize();
            MemorySegment dst = arena.allocate(n * wordBytes);
            switch (ptype) {
                case I8, U8 -> {
                    for (long off = 0, end = n; off < end; off++) {
                        byte v = src.get(ValueLayout.JAVA_BYTE, off);
                        dst.set(ValueLayout.JAVA_BYTE, off, (byte) (v + (byte) ref));
                    }
                }
                case I16, U16 -> {
                    for (long off = 0, end = n * 2; off < end; off += 2) {
                        short v = src.get(PTypeIO.LE_SHORT, off);
                        dst.set(PTypeIO.LE_SHORT, off, (short) (v + (short) ref));
                    }
                }
                case I32, U32 -> {
                    for (long off = 0, end = n * 4; off < end; off += 4) {
                        int v = src.get(PTypeIO.LE_INT, off);
                        dst.set(PTypeIO.LE_INT, off, v + (int) ref);
                    }
                }
                case I64, U64 -> {
                    for (long off = 0, end = n * 8; off < end; off += 8) {
                        long v = src.get(PTypeIO.LE_LONG, off);
                        dst.set(PTypeIO.LE_LONG, off, v + ref);
                    }
                }
                default -> throw new VortexException(EncodingId.FASTLANES_FOR, "unsupported ptype " + ptype);
            }
            return dst;
        }
    }
}
