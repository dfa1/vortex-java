package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.VarBinArray;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.io.VortexFormat;
import io.github.dfa1.vortex.core.proto.ProtoFSSTMetadata;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/// Read-only decoder for `vortex.fsst`.
public final class FsstEncodingDecoder implements EncodingDecoder {

    private static final int ESCAPE = 0xFF;

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_FSST;
    }

    @Override
    public Array decode(DecodeContext ctx) {
        MemorySegment rawMeta = ctx.metadata();
        if (rawMeta == null) {
            throw new VortexException(EncodingId.VORTEX_FSST, "missing metadata");
        }
        ProtoFSSTMetadata meta;
        try {
            MemorySegment metaSeg = rawMeta;
            meta = ProtoFSSTMetadata.decode(metaSeg, 0, metaSeg.byteSize());
        } catch (IOException e) {
            throw new VortexException(EncodingId.VORTEX_FSST, "invalid metadata", e);
        }

        PType uncompLenPType = PType.fromOrdinal(meta.uncompressed_lengths_ptype().value());
        PType codesOffPType = PType.fromOrdinal(meta.codes_offsets_ptype().value());

        long n = ctx.rowCount();

        MemorySegment symbolsBuf = ctx.buffer(0);
        MemorySegment symbolLensBuf = ctx.buffer(1);
        MemorySegment compressedBytes = ctx.buffer(2);

        MemorySegment uncompLensSeg = ctx.decodeChildSegment(0, new DType.Primitive(uncompLenPType, false), n);
        MemorySegment codesOffsetsSeg = ctx.decodeChildSegment(1, new DType.Primitive(codesOffPType, false), n + 1);
        long uncompLensCap = SegmentBroadcast.capacity(uncompLensSeg, uncompLenPType.byteSize());
        long codesOffCap = SegmentBroadcast.capacity(codesOffsetsSeg, codesOffPType.byteSize());

        long totalUncompressed = 0L;
        for (long i = 0; i < n; i++) {
            totalUncompressed += readUnsigned(uncompLensSeg, i % uncompLensCap, uncompLenPType);
        }

        MemorySegment outBytes = ctx.arena().allocate(totalUncompressed);
        MemorySegment outOffsets = ctx.arena().allocate((n + 1) * 4L, 4);
        outOffsets.setAtIndex(VortexFormat.LE_INT, 0, 0);

        long outPos = 0L;
        for (long i = 0; i < n; i++) {
            long cStart = readUnsigned(codesOffsetsSeg, i % codesOffCap, codesOffPType);
            long cEnd = readUnsigned(codesOffsetsSeg, (i + 1) % codesOffCap, codesOffPType);
            outPos = decompressString(compressedBytes, symbolsBuf, symbolLensBuf,
                    cStart, cEnd, outBytes, outPos);
            outOffsets.setAtIndex(VortexFormat.LE_INT, i + 1, (int) outPos);
        }

        return new VarBinArray.OffsetMode(ctx.dtype(), n, outBytes.asReadOnly(), outOffsets.asReadOnly(), PType.I32);
    }

    private static long decompressString(
            MemorySegment compressed, MemorySegment symbols, MemorySegment symLens,
            long start, long end, MemorySegment out, long outPos
    ) {
        for (long j = start; j < end; j++) {
            int b = Byte.toUnsignedInt(compressed.get(ValueLayout.JAVA_BYTE, j));
            if (b == ESCAPE) {
                out.set(ValueLayout.JAVA_BYTE, outPos++, compressed.get(ValueLayout.JAVA_BYTE, ++j));
            } else {
                int symLen = Byte.toUnsignedInt(symLens.get(ValueLayout.JAVA_BYTE, b));
                long sym = symbols.getAtIndex(VortexFormat.LE_LONG, b);
                for (int k = 0; k < symLen; k++) {
                    out.set(ValueLayout.JAVA_BYTE, outPos++, (byte) (sym >>> (k * 8)));
                }
            }
        }
        return outPos;
    }

    private static long readUnsigned(MemorySegment seg, long idx, PType ptype) {
        return switch (ptype) {
            case U8 -> Byte.toUnsignedLong(seg.get(ValueLayout.JAVA_BYTE, idx));
            case U16 -> Short.toUnsignedLong(seg.get(VortexFormat.LE_SHORT, idx * 2));
            case U32 -> Integer.toUnsignedLong(seg.getAtIndex(VortexFormat.LE_INT, idx));
            case I32 -> seg.getAtIndex(VortexFormat.LE_INT, idx);
            case I64, U64 -> seg.getAtIndex(VortexFormat.LE_LONG, idx);
            default -> throw new VortexException(EncodingId.VORTEX_FSST, "unsupported ptype " + ptype);
        };
    }
}
