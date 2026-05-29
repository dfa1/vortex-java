package io.github.dfa1.vortex.encoding;

import com.google.protobuf.InvalidProtocolBufferException;
import dev.vortex.proto.EncodingProtos;
import io.github.dfa1.vortex.core.Array;
import io.github.dfa1.vortex.core.ArrayStats;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.array.IntArray;
import io.github.dfa1.vortex.core.array.VarBinArray;
import io.github.dfa1.vortex.core.VortexException;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/// Decoder for {@code vortex.fsst} — Fast Static Symbol Table string compression.
///
/// <p>Wire format (3-buffer current format):
/// <ul>
///   <li>Buffer 0: symbol table — up to 256 entries, each 8 bytes LE (Symbol = u64)</li>
///   <li>Buffer 1: symbol_lengths — 1 byte per symbol, actual byte count in the symbol</li>
///   <li>Buffer 2: compressed code bytes for all strings concatenated</li>
///   <li>Child 0: uncompressed_lengths — primitive array (I32/I64) of original string lengths</li>
///   <li>Child 1: codes_offsets — primitive array (I32/I64) of offsets into buf[2], length = n+1</li>
/// </ul>
///
/// <p>Output: varbin-compatible {@code Array} (buffer[0] = uncompressed bytes, child[0] = I32 offsets).
public final class FsstCodec implements Codec {

    private static final int ESCAPE = 0xFF;

    private static final ValueLayout.OfLong   LE_LONG  = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfInt    LE_INT   = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfShort  LE_SHORT = ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    @Override
    public CodecId encodingId() {
        return CodecId.VORTEX_FSST;
    }

    @Override
    public EncodeResult encode(DType dtype, Object data) {
        throw new UnsupportedOperationException("encode not supported by " + encodingId());
    }

    @Override
    public Array decode(DecodeContext ctx) {
        ByteBuffer rawMeta = ctx.metadata();
        if (rawMeta == null) {
            throw new VortexException(CodecId.VORTEX_FSST, "missing metadata");
        }
        EncodingProtos.FSSTMetadata meta;
        try {
            meta = EncodingProtos.FSSTMetadata.parseFrom(rawMeta.duplicate());
        } catch (InvalidProtocolBufferException e) {
            throw new VortexException(CodecId.VORTEX_FSST, "invalid metadata", e);
        }

        PType uncompLenPType = PType.values()[meta.getUncompressedLengthsPtype().getNumber()];
        PType codesOffPType  = PType.values()[meta.getCodesOffsetsPtype().getNumber()];

        long n = ctx.rowCount();

        MemorySegment symbolsBuf      = ctx.buffer(0); // 8 bytes per symbol (LE u64)
        MemorySegment symbolLensBuf   = ctx.buffer(1); // 1 byte per symbol
        MemorySegment compressedBytes = ctx.buffer(2); // FSST-compressed heap

        // Decode child arrays
        Array uncompLens = decodeChild(ctx, 0, uncompLenPType, n);
        Array codesOffsets = decodeChild(ctx, 1, codesOffPType, n + 1);
        MemorySegment uncompLensSeg   = uncompLens.buffer(0);
        MemorySegment codesOffsetsSeg = codesOffsets.buffer(0);

        // Total uncompressed size (sum of uncompressed_lengths)
        long totalUncompressed = 0L;
        for (long i = 0; i < n; i++) {
            totalUncompressed += readUnsigned(uncompLensSeg, i, uncompLenPType);
        }

        MemorySegment outBytes   = ctx.arena().allocate(totalUncompressed);
        MemorySegment outOffsets = ctx.arena().allocate((n + 1) * 4L, 4);
        outOffsets.setAtIndex(LE_INT, 0, 0);

        long outPos = 0L;
        for (long i = 0; i < n; i++) {
            long cStart = readUnsigned(codesOffsetsSeg, i, codesOffPType);
            long cEnd   = readUnsigned(codesOffsetsSeg, i + 1, codesOffPType);
            outPos = decompressString(compressedBytes, symbolsBuf, symbolLensBuf,
                    cStart, cEnd, outBytes, outPos);
            outOffsets.setAtIndex(LE_INT, i + 1, (int) outPos);
        }

        DType i32 = new DType.Primitive(PType.I32, false);
        Array offsets = new IntArray(i32, n + 1, outOffsets.asReadOnly(), ArrayStats.empty());
        return new VarBinArray(ctx.dtype(), n, outBytes.asReadOnly(), offsets, PType.I32, ArrayStats.empty());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Array decodeChild(DecodeContext parent, int idx, PType ptype, long rowCount) {
        ArrayNode childNode = parent.node().children()[idx];
        DType dtype = new DType.Primitive(ptype, false);
        DecodeContext childCtx = new DecodeContext(
                childNode, dtype, rowCount,
                parent.segmentBuffers(), parent.registry(), parent.arena());
        return parent.registry().decode(childCtx);
    }

    private static long decompressString(
            MemorySegment compressed, MemorySegment symbols, MemorySegment symLens,
            long start, long end, MemorySegment out, long outPos
    ) {
        for (long j = start; j < end; j++) {
            int b = Byte.toUnsignedInt(compressed.get(ValueLayout.JAVA_BYTE, j));
            if (b == ESCAPE) {
                // next byte is literal
                out.set(ValueLayout.JAVA_BYTE, outPos++, compressed.get(ValueLayout.JAVA_BYTE, ++j));
            } else {
                int symLen = Byte.toUnsignedInt(symLens.get(ValueLayout.JAVA_BYTE, b));
                // symbols[b] is 8 bytes LE; emit first symLen bytes
                long sym = symbols.getAtIndex(LE_LONG, b);
                for (int k = 0; k < symLen; k++) {
                    out.set(ValueLayout.JAVA_BYTE, outPos++, (byte) (sym >>> (k * 8)));
                }
            }
        }
        return outPos;
    }

    private static long readUnsigned(MemorySegment seg, long idx, PType ptype) {
        return switch (ptype) {
            case U8  -> Byte.toUnsignedLong(seg.get(ValueLayout.JAVA_BYTE, idx));
            case U16 -> Short.toUnsignedLong(seg.get(LE_SHORT, idx * 2));
            case U32 -> Integer.toUnsignedLong(seg.getAtIndex(LE_INT, idx));
            case I32 -> seg.getAtIndex(LE_INT, idx);
            case I64, U64 -> seg.getAtIndex(LE_LONG, idx);
            default  -> throw new VortexException(CodecId.VORTEX_FSST, "unsupported ptype " + ptype);
        };
    }
}
