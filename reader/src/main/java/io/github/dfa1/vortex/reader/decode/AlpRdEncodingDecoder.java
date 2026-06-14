package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.MaterializedDoubleArray;
import io.github.dfa1.vortex.reader.array.FloatArray;
import io.github.dfa1.vortex.encoding.EncodingId;
import io.github.dfa1.vortex.encoding.PTypeIO;
import io.github.dfa1.vortex.proto.ALPRDMetadata;
import io.github.dfa1.vortex.proto.PatchesMetadata;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;

/// Read-only decoder for {@code vortex.alprd}.
public final class AlpRdEncodingDecoder implements EncodingDecoder {
    private static final DType U16_DTYPE = new DType.Primitive(PType.U16, false);
    private static final DType U32_DTYPE = new DType.Primitive(PType.U32, false);
    private static final DType U64_DTYPE = new DType.Primitive(PType.U64, false);

    /// Public no-arg constructor required by {@link java.util.ServiceLoader}.
    public AlpRdEncodingDecoder() {
    }

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_ALPRD;
    }

    @Override
    public boolean accepts(DType dtype) {
        if (!(dtype instanceof DType.Primitive p)) {
            return false;
        }
        return p.ptype() == PType.F32 || p.ptype() == PType.F64;
    }

    @Override
    public Array decode(DecodeContext ctx) {
        ALPRDMetadata meta = parseMeta(ctx);

        if (!(ctx.dtype() instanceof DType.Primitive p)) {
            throw new VortexException(EncodingId.VORTEX_ALPRD,
                    "expected primitive dtype, got " + ctx.dtype());
        }

        int rightBitWidth = meta.right_bit_width();
        int dictLen = meta.dict_len();
        short[] dict = new short[dictLen];
        for (int i = 0; i < dictLen; i++) {
            dict[i] = (short) (meta.dict().get(i) & 0xFFFF);
        }

        long n = ctx.rowCount();
        PType ptype = p.ptype();

        return switch (ptype) {
            case F64 -> decodeF64(ctx, meta, dict, rightBitWidth, n);
            case F32 -> decodeF32(ctx, meta, dict, rightBitWidth, n);
            default -> throw new VortexException(EncodingId.VORTEX_ALPRD, "unsupported dtype " + ptype);
        };
    }

    private static Array decodeF64(DecodeContext ctx, ALPRDMetadata meta, short[] dict, int rightBitWidth, long n) {
        MemorySegment leftSeg = ctx.decodeChildSegment(0, U16_DTYPE, n);
        MemorySegment rightSeg = ctx.decodeChildSegment(1, U64_DTYPE, n);
        long leftCap = SegmentBroadcast.capacity(leftSeg, 2);
        long rightCap = SegmentBroadcast.capacity(rightSeg, 8);
        MemorySegment out = ctx.arena().allocate(n * Long.BYTES, Long.BYTES);

        for (long i = 0; i < n; i++) {
            int code = Short.toUnsignedInt(leftSeg.getAtIndex(PTypeIO.LE_SHORT, i % leftCap));
            long leftBits = (long) (dict[code] & 0xFFFF) << rightBitWidth;
            long rightBits = rightSeg.getAtIndex(PTypeIO.LE_LONG, i % rightCap);
            out.setAtIndex(PTypeIO.LE_LONG, i, leftBits | rightBits);
        }

        if (meta.patches() != null) {
            applyPatchesF64(ctx, meta.patches(), out, rightSeg, rightCap, rightBitWidth);
        }

        return new MaterializedDoubleArray(ctx.dtype(), n, out.asReadOnly());
    }

    private static Array decodeF32(DecodeContext ctx, ALPRDMetadata meta, short[] dict, int rightBitWidth, long n) {
        MemorySegment leftSeg = ctx.decodeChildSegment(0, U16_DTYPE, n);
        MemorySegment rightSeg = ctx.decodeChildSegment(1, U32_DTYPE, n);
        long leftCap = SegmentBroadcast.capacity(leftSeg, 2);
        long rightCap = SegmentBroadcast.capacity(rightSeg, 4);
        MemorySegment out = ctx.arena().allocate(n * Integer.BYTES, Integer.BYTES);

        for (long i = 0; i < n; i++) {
            int code = Short.toUnsignedInt(leftSeg.getAtIndex(PTypeIO.LE_SHORT, i % leftCap));
            int leftBits = (dict[code] & 0xFFFF) << rightBitWidth;
            int rightBits = rightSeg.getAtIndex(PTypeIO.LE_INT, i % rightCap);
            out.setAtIndex(PTypeIO.LE_INT, i, leftBits | rightBits);
        }

        if (meta.patches() != null) {
            applyPatchesF32(ctx, meta.patches(), out, rightSeg, rightCap, rightBitWidth);
        }

        return new FloatArray(ctx.dtype(), n, out.asReadOnly());
    }

    private static void applyPatchesF64(DecodeContext ctx, PatchesMetadata pm,
            MemorySegment out, MemorySegment rightSeg, long rightCap, int rightBitWidth) {
        long numPatches = pm.len();
        long offset = pm.offset();
        PType idxPtype = PType.fromOrdinal(pm.indices_ptype().value());

        MemorySegment idxSeg = ctx.decodeChildSegment(2, new DType.Primitive(idxPtype, false), numPatches);
        MemorySegment valSeg = ctx.decodeChildSegment(3, U16_DTYPE, numPatches);
        int idxBytes = idxPtype.byteSize();
        long valCap = SegmentBroadcast.capacity(valSeg, 2);

        for (long j = 0; j < numPatches; j++) {
            long absIdx = readUnsigned(idxSeg, SegmentBroadcast.elementOffset(idxSeg, j, idxBytes), idxPtype) - offset;
            short actualLeftU16 = valSeg.getAtIndex(PTypeIO.LE_SHORT, j % valCap);
            long leftBits = (long) (actualLeftU16 & 0xFFFF) << rightBitWidth;
            long rightBits = rightSeg.getAtIndex(PTypeIO.LE_LONG, absIdx % rightCap);
            out.setAtIndex(PTypeIO.LE_LONG, absIdx, leftBits | rightBits);
        }
    }

    private static void applyPatchesF32(DecodeContext ctx, PatchesMetadata pm,
            MemorySegment out, MemorySegment rightSeg, long rightCap, int rightBitWidth) {
        long numPatches = pm.len();
        long offset = pm.offset();
        PType idxPtype = PType.fromOrdinal(pm.indices_ptype().value());

        MemorySegment idxSeg = ctx.decodeChildSegment(2, new DType.Primitive(idxPtype, false), numPatches);
        MemorySegment valSeg = ctx.decodeChildSegment(3, U16_DTYPE, numPatches);
        int idxBytes = idxPtype.byteSize();
        long valCap = SegmentBroadcast.capacity(valSeg, 2);

        for (long j = 0; j < numPatches; j++) {
            long absIdx = readUnsigned(idxSeg, SegmentBroadcast.elementOffset(idxSeg, j, idxBytes), idxPtype) - offset;
            short actualLeftU16 = valSeg.getAtIndex(PTypeIO.LE_SHORT, j % valCap);
            int leftBits = (actualLeftU16 & 0xFFFF) << rightBitWidth;
            int rightBits = rightSeg.getAtIndex(PTypeIO.LE_INT, absIdx % rightCap);
            out.setAtIndex(PTypeIO.LE_INT, (int) absIdx, leftBits | rightBits);
        }
    }

    private static long readUnsigned(MemorySegment seg, long off, PType ptype) {
        return switch (ptype) {
            case U8 -> Byte.toUnsignedLong(seg.get(ValueLayout.JAVA_BYTE, off));
            case U16 -> Short.toUnsignedLong(seg.get(PTypeIO.LE_SHORT, off));
            case U32 -> Integer.toUnsignedLong(seg.get(PTypeIO.LE_INT, off));
            case U64 -> seg.get(PTypeIO.LE_LONG, off);
            default -> throw new VortexException(EncodingId.VORTEX_ALPRD,
                    "non-unsigned patch index ptype " + ptype);
        };
    }

    private static ALPRDMetadata parseMeta(DecodeContext ctx) {
        ByteBuffer rawMeta = ctx.metadata();
        if (rawMeta == null || !rawMeta.hasRemaining()) {
            return new ALPRDMetadata(0, 0, java.util.List.of(),
                    io.github.dfa1.vortex.proto.PType.fromValue(PType.U16.ordinal()), null);
        }
        try {
            MemorySegment metaSeg = MemorySegment.ofBuffer(rawMeta.duplicate());
            return ALPRDMetadata.decode(metaSeg, 0, metaSeg.byteSize());
        } catch (IOException e) {
            throw new VortexException(EncodingId.VORTEX_ALPRD, "invalid metadata", e);
        }
    }
}
