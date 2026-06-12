package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.encoding.CascadeStep;
import io.github.dfa1.vortex.encoding.ChildSlot;
import io.github.dfa1.vortex.encoding.EncodeContext;
import io.github.dfa1.vortex.encoding.EncodeNode;
import io.github.dfa1.vortex.encoding.EncodeResult;
import io.github.dfa1.vortex.encoding.EncodingEncoder;
import io.github.dfa1.vortex.encoding.EncodingId;
import io.github.dfa1.vortex.encoding.PTypeIO;
import io.github.dfa1.vortex.proto.ScalarValue;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.util.List;

/// Write-only encoder for {@code fastlanes.for} (Frame of Reference).
public final class FrameOfReferenceEncodingEncoder implements EncodingEncoder {

    /// Public no-arg constructor required by {@link java.util.ServiceLoader}.
    public FrameOfReferenceEncodingEncoder() {
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

    @Override
    public CascadeStep encodeCascade(DType dtype, Object data, EncodeContext encodeCtx) {
        if (!(dtype instanceof DType.Primitive p)) {
            throw new VortexException(EncodingId.FASTLANES_FOR, "expected primitive dtype, got " + dtype);
        }
        PType ptype = p.ptype();
        long[] longs = toLongs(data, ptype);
        int n = longs.length;

        long ref = computeRef(longs, n);
        ByteBuffer meta = buildForMeta(ref, ptype);

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
        ScalarValue scalar = unsigned ? ScalarValue.ofUint64Value(ref) : ScalarValue.ofInt64Value(ref);
        return ByteBuffer.wrap(scalar.encode());
    }

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
