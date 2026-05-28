package io.github.dfa1.vortex.encoding;

import com.google.protobuf.InvalidProtocolBufferException;
import dev.vortex.proto.DTypeProtos;
import dev.vortex.proto.EncodingProtos;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/// Decoder for {@code vortex.runend} — run-end (RLE) encoding.
///
/// <p>Metadata: protobuf {@code RunEndMetadata} — {@code ends_ptype PType} (tag 1),
/// {@code num_runs u64} (tag 2), {@code offset u64} (tag 3).
///
/// <p>Child slot 0: run-end positions (unsigned, cumulative exclusive ends).
/// Child slot 1: run values (same dtype as parent, one per run).
///
/// <p>Decode: for each run i, repeat {@code values[i]} for positions
/// {@code [ends[i-1], ends[i])} in the output, skipping the first {@code offset} logical elements.
public final class RunEndCodec implements Decoder {

    @Override
    public String encodingId() {
        return "vortex.runend";
    }

    @Override
    public Array decode(DecodeContext ctx) {
        ByteBuffer rawMeta = ctx.metadata();
        if (rawMeta == null) {
            throw new IllegalStateException("vortex.runend: missing metadata");
        }
        byte[] metaBytes = new byte[rawMeta.remaining()];
        rawMeta.duplicate().get(metaBytes);

        EncodingProtos.RunEndMetadata meta;
        try {
            meta = EncodingProtos.RunEndMetadata.parseFrom(metaBytes);
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalStateException("vortex.runend: invalid metadata", e);
        }

        PType endsPtype  = ptypeFromProto(meta.getEndsPtype());
        long numRuns     = meta.getNumRuns();
        long offset      = meta.getOffset();

        if (!(ctx.dtype() instanceof DType.Primitive p)) {
            throw new IllegalStateException("vortex.runend: expected primitive dtype, got " + ctx.dtype());
        }
        PType valuePtype = p.ptype();
        long  n          = ctx.rowCount();

        DType endsDtype = new DType.Primitive(endsPtype, false);
        Array endsArr   = decodeChildAs(ctx, 0, endsDtype, numRuns);
        Array valuesArr = decodeChildAs(ctx, 1, ctx.dtype(), numRuns);

        return expand(endsArr.buffer(0), valuesArr.buffer(0),
            endsPtype, valuePtype, numRuns, offset, n, ctx.dtype());
    }

    // ── Expansion ─────────────────────────────────────────────────────────────

    private static Array expand(
        MemorySegment endsSeg, MemorySegment valuesSeg,
        PType endsPtype, PType valuePtype,
        long numRuns, long offset, long n,
        DType dtype
    ) {
        int  elemBytes = valuePtype.byteSize();
        byte[] outBytes = new byte[(int) (n * elemBytes)];
        ByteBuffer out = ByteBuffer.wrap(outBytes).order(ByteOrder.LITTLE_ENDIAN);

        long logicalPos = 0L; // output position (before offset subtraction)
        long outPos     = 0L; // filled output elements

        for (long run = 0; run < numRuns && outPos < n; run++) {
            long runEnd   = readUnsigned(endsSeg, run, endsPtype); // exclusive logical end
            long rawValue = readRaw(valuesSeg, run, valuePtype);

            // logical positions for this run: [logicalPos, runEnd)
            // after skipping offset: [max(logicalPos, offset), min(runEnd, offset+n))
            long writeStart = Math.max(logicalPos, offset);
            long writeEnd   = Math.min(runEnd, offset + n);

            for (long lp = writeStart; lp < writeEnd; lp++) {
                writeRaw(out, valuePtype, rawValue);
                outPos++;
            }

            logicalPos = runEnd;
        }

        return new Array(dtype, n,
            new MemorySegment[]{MemorySegment.ofArray(outBytes)}, new Array[0], ArrayStats.empty());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static Array decodeChildAs(DecodeContext parent, int childIdx, DType dtype, long rowCount) {
        ArrayNode childNode = parent.node().children()[childIdx];
        DecodeContext childCtx = new DecodeContext(
            childNode, dtype, rowCount, parent.segmentBuffers(), parent.registry(), parent.arena());
        return parent.registry().decode(childCtx);
    }

    private static long readUnsigned(MemorySegment seg, long i, PType ptype) {
        return switch (ptype) {
            case U8  -> Byte.toUnsignedLong(seg.get(ValueLayout.JAVA_BYTE, i));
            case U16 -> Short.toUnsignedLong(
                seg.get(ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), i * 2));
            case U32 -> Integer.toUnsignedLong(
                seg.get(ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), i * 4));
            case U64 -> seg.get(
                ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), i * 8);
            default  -> throw new IllegalStateException("vortex.runend: non-unsigned ends ptype " + ptype);
        };
    }

    private static long readRaw(MemorySegment seg, long i, PType ptype) {
        int sz = ptype.byteSize();
        return switch (sz) {
            case 1 -> Byte.toUnsignedLong(seg.get(ValueLayout.JAVA_BYTE, i));
            case 2 -> Short.toUnsignedLong(
                seg.get(ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), i * 2));
            case 4 -> Integer.toUnsignedLong(
                seg.get(ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), i * 4));
            case 8 -> seg.get(
                ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), i * 8);
            default -> throw new UnsupportedOperationException("vortex.runend: unsupported elem size " + sz);
        };
    }

    private static void writeRaw(ByteBuffer buf, PType ptype, long rawBits) {
        switch (ptype.byteSize()) {
            case 1 -> buf.put((byte) rawBits);
            case 2 -> buf.putShort((short) rawBits);
            case 4 -> buf.putInt((int) rawBits);
            case 8 -> buf.putLong(rawBits);
            default -> throw new UnsupportedOperationException("vortex.runend: unsupported ptype " + ptype);
        }
    }

    private static PType ptypeFromProto(DTypeProtos.PType proto) {
        return PType.values()[proto.getNumber()];
    }
}
