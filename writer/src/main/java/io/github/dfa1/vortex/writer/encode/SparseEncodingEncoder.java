package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.encoding.EncodingId;
import io.github.dfa1.vortex.encoding.PTypeIO;
import io.github.dfa1.vortex.proto.PatchesMetadata;
import io.github.dfa1.vortex.proto.ScalarValue;
import io.github.dfa1.vortex.proto.SparseMetadata;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/// Write-only encoder for {@code vortex.sparse}.
public final class SparseEncodingEncoder implements EncodingEncoder {

    /// Public no-arg constructor required by {@link java.util.ServiceLoader}.
    public SparseEncodingEncoder() {
    }

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_SPARSE;
    }

    @Override
    public boolean accepts(DType dtype) {
        return dtype instanceof DType.Primitive;
    }

    @Override
    public EncodeResult encode(DType dtype, Object data, EncodeContext ctx) {
        if (!(dtype instanceof DType.Primitive p)) {
            throw new VortexException(EncodingId.VORTEX_SPARSE,
                    "encode only supports Primitive dtype, got " + dtype);
        }
        PType ptype = p.ptype();
        int n = arrayLength(data, ptype);

        List<Integer> patchIdx = new ArrayList<>();
        List<Long> patchBits = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            long bits = readBits(data, ptype, i);
            if (bits != 0L) {
                patchIdx.add(i);
                patchBits.add(bits);
            }
        }

        int numPatches = patchIdx.size();
        PType idxPtype = chooseIdxPtype(n);

        ScalarValue fillScalar = zeroScalar(ptype);
        byte[] fillBytes = fillScalar.encode();
        MemorySegment fillBuf = ctx.arena().allocate(fillBytes.length);
        MemorySegment.copy(MemorySegment.ofArray(fillBytes), 0, fillBuf, 0, fillBytes.length);

        MemorySegment idxBuf = buildIdxBuf(patchIdx, idxPtype, numPatches, ctx);
        MemorySegment valBuf = buildValBuf(patchBits, ptype, numPatches, ctx);

        PatchesMetadata patchesMeta = new PatchesMetadata(
                numPatches,
                0L,
                io.github.dfa1.vortex.proto.PType.fromValue(idxPtype.ordinal()),
                null,
                null,
                null
        );
        byte[] metaBytes = new SparseMetadata(patchesMeta).encode();

        EncodeNode idxNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, 1);
        EncodeNode valNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, 2);
        EncodeNode root = new EncodeNode(EncodingId.VORTEX_SPARSE, ByteBuffer.wrap(metaBytes),
                new EncodeNode[]{idxNode, valNode}, new int[]{0});
        return new EncodeResult(root, List.of(fillBuf, idxBuf, valBuf), null, null);
    }

    private static int arrayLength(Object data, PType ptype) {
        return switch (ptype) {
            case I8, U8 -> ((byte[]) data).length;
            case I16, U16 -> ((short[]) data).length;
            case I32, U32 -> ((int[]) data).length;
            case I64, U64 -> ((long[]) data).length;
            case F32 -> ((float[]) data).length;
            case F64 -> ((double[]) data).length;
            default -> throw new VortexException(EncodingId.VORTEX_SPARSE, "unsupported ptype: " + ptype);
        };
    }

    private static long readBits(Object data, PType ptype, int i) {
        return switch (ptype) {
            case I8 -> ((byte[]) data)[i];
            case U8 -> Byte.toUnsignedLong(((byte[]) data)[i]);
            case I16 -> ((short[]) data)[i];
            case U16 -> Short.toUnsignedLong(((short[]) data)[i]);
            case I32 -> ((int[]) data)[i];
            case U32 -> Integer.toUnsignedLong(((int[]) data)[i]);
            case I64, U64 -> ((long[]) data)[i];
            case F32 -> Float.floatToRawIntBits(((float[]) data)[i]);
            case F64 -> Double.doubleToRawLongBits(((double[]) data)[i]);
            default -> throw new VortexException(EncodingId.VORTEX_SPARSE, "unsupported ptype: " + ptype);
        };
    }

    private static PType chooseIdxPtype(int n) {
        if (n <= 0xFF) {
            return PType.U8;
        } else if (n <= 0xFFFF) {
            return PType.U16;
        } else {
            return PType.U32;
        }
    }

    private static ScalarValue zeroScalar(PType ptype) {
        return switch (ptype) {
            case I8, I16, I32, I64 -> ScalarValue.ofInt64Value(0L);
            case U8, U16, U32, U64 -> ScalarValue.ofUint64Value(0L);
            case F32 -> ScalarValue.ofF32Value(0.0f);
            case F64 -> ScalarValue.ofF64Value(0.0);
            default -> throw new VortexException(EncodingId.VORTEX_SPARSE, "unsupported ptype: " + ptype);
        };
    }

    private static MemorySegment buildIdxBuf(List<Integer> patchIdx, PType idxPtype, int numPatches, EncodeContext ctx) {
        int elemBytes = idxPtype.byteSize();
        MemorySegment seg = ctx.arena().allocate(Math.max(1L, (long) numPatches * elemBytes), elemBytes);
        for (int i = 0; i < numPatches; i++) {
            PTypeIO.set(seg, (long) i * elemBytes, idxPtype, patchIdx.get(i));
        }
        return seg;
    }

    private static MemorySegment buildValBuf(List<Long> patchBits, PType ptype, int numPatches, EncodeContext ctx) {
        int elemBytes = ptype.byteSize();
        MemorySegment seg = ctx.arena().allocate(Math.max(1L, (long) numPatches * elemBytes), elemBytes);
        for (int i = 0; i < numPatches; i++) {
            PTypeIO.set(seg, (long) i * elemBytes, ptype, patchBits.get(i));
        }
        return seg;
    }
}
