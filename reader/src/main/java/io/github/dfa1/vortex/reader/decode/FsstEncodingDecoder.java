package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.VarBinOffsetArray;
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

    /// Rows decoded per [Decompressor#decompress] call on the fast path. Batching (rather than one
    /// whole-chunk call) keeps each call's loop short enough that execution stays in the cleanly
    /// compiled method entry instead of an OSR-compiled mega-loop — a single 50k-row call measured
    /// ~1.8x slower per byte than short calls on the same data — while still amortizing the per-row
    /// offset read down to one read per batch.
    private static final int ROWS_PER_DECODE_BATCH = 256;

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

        if (n >= Integer.MAX_VALUE) {
            throw new VortexException(EncodingId.VORTEX_FSST, "row count too large: " + n);
        }

        // The decoded row boundaries are fully determined by the uncompressed-lengths child, so the
        // output offsets are its prefix sums — computed in one per-ptype loop (no switch, no
        // modulo in the body; hot-loop rule) that also yields the total for sizing the output.
        MemorySegment outOffsets = ctx.arena().allocate((n + 1) * 4L, 4);
        outOffsets.setAtIndex(VortexFormat.LE_INT, 0, 0);
        long totalUncompressed = writeDecodedOffsets(uncompLensSeg, outOffsets, n, uncompLenPType, uncompLensCap);

        // The output offsets are I32 prefix sums, so a decoded chunk past 2 GB would silently wrap
        // (and adversarial I32/I64-typed lengths can make the running sum go negative). Reject both
        // before sizing/allocating outBytes, so a hostile length child can never drive a wrapped or
        // enormous allocation.
        if (totalUncompressed > Integer.MAX_VALUE || totalUncompressed < 0) {
            throw new VortexException(EncodingId.VORTEX_FSST,
                    "decoded length too large: " + totalUncompressed);
        }

        // Allocate 7 bytes of slack past the true logical length: the decompressor's unconditional
        // 8-byte-store trick writes a full 8 bytes for the final symbol even when it contributes as
        // few as 1 real byte. The slack is sliced off before the buffer is exposed, so callers still
        // see an exactly-sized buffer.
        MemorySegment outBytes = ctx.arena().allocate(totalUncompressed + 7);

        if (codesOffCap == 0) {
            // No physical offsets at all: only valid for a zero-row chunk, where there is nothing
            // to decode.
            if (n > 0) {
                throw new VortexException(EncodingId.VORTEX_FSST, "empty codes-offsets child");
            }
        } else if (codesOffCap == 1) {
            // Constant-broadcast offsets child: all n + 1 logical offsets share one physical value,
            // so every row's code range is the empty [off, off) and the column decodes to nothing.
            // The batched fast path below must NOT run here — it reads offsets at row indices (256,
            // 512, ...), which would run off this 1-element segment. A non-zero claimed length means
            // the lengths child disagrees with the (empty) code ranges, i.e. a malformed file.
            if (totalUncompressed != 0) {
                throw new VortexException(EncodingId.VORTEX_FSST, "constant code offsets imply an "
                        + "empty column but uncompressed lengths claim " + totalUncompressed + " bytes");
            }
        } else if (codesOffCap >= n + 1) {
            // Fast path. Row i's code range is [offsets[i], offsets[i+1]) out of ONE shared offsets
            // array, so consecutive rows are contiguous by construction and the code stream decodes
            // in row batches — only every ROWS_PER_DECODE_BATCH-th offset is read (no per-row
            // offset reads, no per-row switch/modulo).
            long firstOffset = readUnsigned(codesOffsetsSeg, 0, codesOffPType);
            long lastOffset = readUnsigned(codesOffsetsSeg, Math.min(n, codesOffCap - 1), codesOffPType);
            if (firstOffset > lastOffset || lastOffset > compressedBytes.byteSize()) {
                throw new VortexException(EncodingId.VORTEX_FSST, "invalid code offsets: ["
                        + firstOffset + ", " + lastOffset + ") of " + compressedBytes.byteSize());
            }
            long outPos = 0L;
            long batchStart = firstOffset;
            for (long i = 0; i < n; i += ROWS_PER_DECODE_BATCH) {
                long batchEndRow = Math.min(i + ROWS_PER_DECODE_BATCH, n);
                long batchEnd = batchEndRow == n
                        ? lastOffset
                        : readUnsigned(codesOffsetsSeg, batchEndRow, codesOffPType);
                outPos = decompressor.decompress(compressedBytes, batchStart, batchEnd, outBytes, outPos);
                batchStart = batchEnd;
            }
            if (outPos != totalUncompressed) {
                throw new VortexException(EncodingId.VORTEX_FSST, "decoded " + outPos
                        + " bytes but uncompressed lengths claim " + totalUncompressed);
            }
        } else {
            // Defensive broadcast path (1 < physical offsets < n + 1): ranges wrap around the
            // physical elements, so decode row by row and record the offsets the decode actually
            // produced, overwriting the claimed prefix sums.
            long outPos = 0L;
            for (long i = 0; i < n; i++) {
                long cStart = readUnsigned(codesOffsetsSeg, i % codesOffCap, codesOffPType);
                long cEnd = readUnsigned(codesOffsetsSeg, (i + 1) % codesOffCap, codesOffPType);
                outPos = decompressor.decompress(compressedBytes, cStart, cEnd, outBytes, outPos);
                outOffsets.setAtIndex(VortexFormat.LE_INT, i + 1, (int) outPos);
            }
        }

        return new VarBinOffsetArray(ctx.dtype(), n,
                outBytes.asSlice(0, totalUncompressed).asReadOnly(), outOffsets.asReadOnly(), PType.I32);
    }

    /// Writes the prefix sums of the `count` unsigned per-row lengths in `seg` into `outOffsets`
    /// (I32 slots `1 .. count`; slot 0 is the caller's) and returns the total. Branch-split per
    /// ptype and on the broadcast case so the per-element loop bodies carry no switch and no
    /// modulo (hot-loop rule).
    private static long writeDecodedOffsets(MemorySegment seg, MemorySegment outOffsets, long count,
                                            PType ptype, long cap) {
        if (cap == 0 && count > 0) {
            throw new VortexException(EncodingId.VORTEX_FSST, "empty uncompressed-lengths child");
        }
        long sum = 0L;
        if (cap >= count) {
            switch (ptype) {
                case U8 -> {
                    for (long i = 0; i < count; i++) {
                        sum += Byte.toUnsignedLong(seg.get(ValueLayout.JAVA_BYTE, i));
                        outOffsets.setAtIndex(VortexFormat.LE_INT, i + 1, (int) sum);
                    }
                }
                case U16 -> {
                    for (long i = 0; i < count; i++) {
                        sum += Short.toUnsignedLong(seg.get(VortexFormat.LE_SHORT, i * 2));
                        outOffsets.setAtIndex(VortexFormat.LE_INT, i + 1, (int) sum);
                    }
                }
                case U32 -> {
                    for (long i = 0; i < count; i++) {
                        sum += Integer.toUnsignedLong(seg.getAtIndex(VortexFormat.LE_INT, i));
                        outOffsets.setAtIndex(VortexFormat.LE_INT, i + 1, (int) sum);
                    }
                }
                case I32 -> {
                    for (long i = 0; i < count; i++) {
                        sum += seg.getAtIndex(VortexFormat.LE_INT, i);
                        outOffsets.setAtIndex(VortexFormat.LE_INT, i + 1, (int) sum);
                    }
                }
                case I64, U64 -> {
                    for (long i = 0; i < count; i++) {
                        sum += seg.getAtIndex(VortexFormat.LE_LONG, i);
                        outOffsets.setAtIndex(VortexFormat.LE_INT, i + 1, (int) sum);
                    }
                }
                default -> throw new VortexException(EncodingId.VORTEX_FSST, "unsupported ptype " + ptype);
            }
            return sum;
        }
        // Broadcast slow path (constant-encoded child): only ever a handful of physical elements.
        for (long i = 0; i < count; i++) {
            sum += readUnsigned(seg, i % cap, ptype);
            outOffsets.setAtIndex(VortexFormat.LE_INT, i + 1, (int) sum);
        }
        return sum;
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
