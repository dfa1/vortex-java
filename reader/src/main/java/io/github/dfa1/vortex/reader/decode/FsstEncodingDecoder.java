package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.LazyFsstVarBinArray;
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
/// [ProtoFSSTMetadata]), it builds a [Decompressor] bound to the symbol table and hands it — along
/// with the still-compressed code stream and the per-row length/offset children — to a
/// [LazyFsstVarBinArray], which decompresses each row's code range only when that row is actually
/// read (ADR 0026). No decompression happens here.
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

        // These children carry one length/offset per row (or n+1 for offsets) — proportional to
        // row count, not to the compressed string bytes — so decoding them here is unavoidable
        // metadata cost, not the eager decompression this class exists to avoid.
        MemorySegment uncompLensSeg = ctx.decodeChildSegment(0, new DType.Primitive(uncompLenPType, false), n);
        MemorySegment codesOffsetsSeg = ctx.decodeChildSegment(1, new DType.Primitive(codesOffPType, false), n + 1);

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

        // Bounds the codes-offsets child's `n + 1` element count decoded above; not, unlike the old
        // eager path, about sizing a flat I32 offsets buffer (there is none anymore).
        if (n >= Integer.MAX_VALUE) {
            throw new VortexException(EncodingId.VORTEX_FSST, "row count too large: " + n);
        }

        // No decompression happens here: every per-row length, code range, and bounds check is
        // deferred to LazyFsstVarBinArray's accessors, which run only for rows a caller actually
        // reads (ADR 0026) — mirroring VarBinArrays' "offsets are not scanned at decode time"
        // convention for the other VarBinArray implementations.
        return new LazyFsstVarBinArray(ctx.dtype(), n, decompressor, compressedBytes,
                uncompLensSeg, uncompLenPType, codesOffsetsSeg, codesOffPType);
    }
}
