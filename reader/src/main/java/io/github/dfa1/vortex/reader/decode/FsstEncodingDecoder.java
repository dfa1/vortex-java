package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.VarBinArray;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.io.VortexFormat;
import io.github.dfa1.vortex.core.proto.ProtoFSSTMetadata;
import io.github.dfa1.vortex.core.proto.ProtoPType;
import io.github.dfa1.vortex.fsst.Decompressor;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/// Read-only decoder for `vortex.fsst`.
///
/// This class is a thin wire adapter over the standalone `vortex-fsst` module (issue #287): after
/// parsing the `vortex.fsst` wire buffers (symbol table, per-row uncompressed lengths, code offsets,
/// [ProtoFSSTMetadata]), it hands the symbol table and each row's code range to a [Decompressor],
/// which runs the FSST paper's Algorithm 1 decode.
public final class FsstEncodingDecoder implements EncodingDecoder {

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_FSST;
    }

    @Override
    public Array decode(DecodeContext ctx) {
        int numBufs = ctx.node().bufferIndices().length;
        if (numBufs != 3) {
            throw new VortexException(EncodingId.VORTEX_FSST, "expected 3 buffers, got " + numBufs);
        }
        int numChildren = ctx.node().children().length;
        if (numChildren < 2) {
            throw new VortexException(EncodingId.VORTEX_FSST, "expected at least 2 children, got " + numChildren);
        }

        // Proto3 omits fields at their default (zero) value on the wire, so an all-U8 metadata
        // message (both ptypes ordinal 0) encodes to zero bytes and the writer skips the
        // metadata segment entirely — absent metadata is a valid encoding of that default, not
        // a malformed file.
        MemorySegment rawMeta = ctx.metadata();
        ProtoFSSTMetadata meta;
        try {
            meta = rawMeta == null
                    ? new ProtoFSSTMetadata(ProtoPType.U8, ProtoPType.U8)
                    : ProtoFSSTMetadata.decode(rawMeta, 0, rawMeta.byteSize());
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

        // Read the wire symbol table into parallel code-indexed arrays once per chunk (there are at
        // most 255 symbols), then hand them to the decompressor. symbolsBuf carries one LSB-first
        // long per symbol, so its size divided by 8 is the symbol count: an empty table is written
        // as a 1-byte placeholder buffer (allocations are floored at 1 byte), which floors to 0
        // symbols here — an all-escape column decodes without touching the symbol table.
        int numSymbols = (int) (symbolsBuf.byteSize() / 8);
        long[] packedSymbols = new long[numSymbols];
        int[] symbolLengths = new int[numSymbols];
        for (int code = 0; code < numSymbols; code++) {
            packedSymbols[code] = symbolsBuf.getAtIndex(VortexFormat.LE_LONG, code);
            symbolLengths[code] = Byte.toUnsignedInt(symbolLensBuf.get(ValueLayout.JAVA_BYTE, code));
        }
        Decompressor decompressor = Decompressor.of(packedSymbols, symbolLengths);

        long totalUncompressed = 0L;
        for (long i = 0; i < n; i++) {
            totalUncompressed += readUnsigned(uncompLensSeg, i % uncompLensCap, uncompLenPType);
        }

        // Allocate 7 bytes of slack past the true logical length: the decompressor's unconditional
        // 8-byte-store trick writes a full 8 bytes for the final symbol even when it contributes as
        // few as 1 real byte. The slack is sliced off before the buffer is exposed, so callers still
        // see an exactly-sized buffer.
        MemorySegment outBytes = ctx.arena().allocate(totalUncompressed + 7);
        MemorySegment outOffsets = ctx.arena().allocate((n + 1) * 4L, 4);
        outOffsets.setAtIndex(VortexFormat.LE_INT, 0, 0);

        long outPos = 0L;
        for (long i = 0; i < n; i++) {
            long cStart = readUnsigned(codesOffsetsSeg, i % codesOffCap, codesOffPType);
            long cEnd = readUnsigned(codesOffsetsSeg, (i + 1) % codesOffCap, codesOffPType);
            outPos = decompressor.decompress(compressedBytes, cStart, cEnd, outBytes, outPos);
            outOffsets.setAtIndex(VortexFormat.LE_INT, i + 1, (int) outPos);
        }

        return new VarBinArray.OffsetMode(ctx.dtype(), n,
                outBytes.asSlice(0, totalUncompressed).asReadOnly(), outOffsets.asReadOnly(), PType.I32);
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
