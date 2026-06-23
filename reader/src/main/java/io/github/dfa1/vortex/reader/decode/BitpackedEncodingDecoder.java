package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.encoding.EncodingId;
import io.github.dfa1.vortex.encoding.PTypeIO;
import io.github.dfa1.vortex.proto.BitPackedMetadata;
import io.github.dfa1.vortex.proto.PatchesMetadata;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.MaterializedByteArray;
import io.github.dfa1.vortex.reader.array.MaterializedIntArray;
import io.github.dfa1.vortex.reader.array.MaterializedLongArray;
import io.github.dfa1.vortex.reader.array.MaterializedShortArray;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;

/// Read-only decoder for `fastlanes.bitpacked`.
public final class BitpackedEncodingDecoder implements EncodingDecoder {
    private static final int[] FL_ORDER = {0, 4, 2, 6, 1, 5, 3, 7};

    /// Public no-arg constructor required by [java.util.ServiceLoader].
    public BitpackedEncodingDecoder() {
    }

    @Override
    public EncodingId encodingId() {
        return EncodingId.FASTLANES_BITPACKED;
    }

    @Override
    public boolean accepts(DType dtype) {
        if (!(dtype instanceof DType.Primitive p)) {
            return false;
        }
        return switch (p.ptype()) {
            case I8, I16, I32, I64, U8, U16, U32, U64 -> true;
            default -> false;
        };
    }

    @Override
    public Array decode(DecodeContext ctx) {
        ByteBuffer rawMeta = ctx.metadata();
        // proto3 elides default-valued fields, so BitPackedMetadata(0, 0, null) serialises
        // to a 0-byte payload and the writer skips the empty vector. Treat absent metadata
        // as all-defaults rather than rejecting — happens when bit_width=0 (constant
        // residuals nested under FoR / RLE).
        BitPackedMetadata meta;
        if (rawMeta == null || !rawMeta.hasRemaining()) {
            meta = new BitPackedMetadata(0, 0, null);
        } else {
            try {
                MemorySegment metaSeg = MemorySegment.ofBuffer(rawMeta.duplicate());
                meta = BitPackedMetadata.decode(metaSeg, 0, metaSeg.byteSize());
            } catch (IOException e) {
                throw new VortexException(EncodingId.FASTLANES_BITPACKED, "invalid metadata", e);
            }
        }

        int bitWidth = meta.bit_width();
        int offset = meta.offset();
        PType ptype = ((DType.Primitive) ctx.dtype()).ptype();
        int typeBits = ptype.bits();
        long rowCount = ctx.rowCount();

        MemorySegment packed = ctx.buffer(0);
        MemorySegment output = ctx.arena().allocate(rowCount * ptype.byteSize());
        fastlanesUnpackToSeg(packed, bitWidth, offset, typeBits, rowCount, output);

        if (meta.patches() != null) {
            applyPatches(ctx, meta.patches(), output, ptype.byteSize());
        }

        return switch (ptype) {
            case I64, U64 -> new MaterializedLongArray(ctx.dtype(), rowCount, output);
            case I32, U32 -> new MaterializedIntArray(ctx.dtype(), rowCount, output);
            case I16, U16 -> new MaterializedShortArray(ctx.dtype(), rowCount, output);
            case I8, U8 -> new MaterializedByteArray(ctx.dtype(), rowCount, output);
            default -> throw new VortexException(EncodingId.FASTLANES_BITPACKED, "unsupported ptype " + ptype);
        };
    }

    private static void fastlanesUnpackToSeg(
            MemorySegment buf, int bitWidth, int offset, int typeBits, long rowCount,
            MemorySegment output) {
        if (bitWidth == 0) {
            return;
        }
        switch (typeBits) {
            case 8 -> unpackLoop8(buf, bitWidth, offset, rowCount, output);
            case 16 -> unpackLoop16(buf, bitWidth, offset, rowCount, output);
            case 32 -> unpackLoop32(buf, bitWidth, offset, rowCount, output);
            case 64 -> unpackLoop64(buf, bitWidth, offset, rowCount, output);
            default ->
                    throw new VortexException(EncodingId.FASTLANES_BITPACKED, "unsupported typeBits: " + typeBits);
        }
    }

    /// Per-row unpack schedule for one FastLanes block, precomputed once per decode call. Every
    /// array is indexed by `row` in `[0, typeBits)`. This setup is identical for the 8/16/32/64-bit
    /// unpackers, so it lives here; the per-element unpack loops stay specialised per width because
    /// their typed `ValueLayout` access must constant-fold for the JIT to vectorise them.
    ///
    /// @param shifts            low-bit shift to apply to the current word, per row
    /// @param remainingBits     bits spilling into the next word (0 when the value fits one word)
    /// @param currentBits       bits taken from the current word (`bitWidth - remainingBits`)
    /// @param loMasks           mask for the low part read from the current word
    /// @param hiMasks           mask for the high part read from the next word
    /// @param currWordByteBase  byte offset of the current word within the block
    /// @param nextWordByteBase  byte offset of the next word within the block
    /// @param outRowByteOff     byte offset of the row within the transposed output block
    private record UnpackSchedule(
            int[] shifts, int[] remainingBits, int[] currentBits,
            long[] loMasks, long[] hiMasks,
            long[] currWordByteBase, long[] nextWordByteBase, long[] outRowByteOff) {
    }

    private static UnpackSchedule schedule(int typeBits, int bitWidth) {
        int lanes = 1024 / typeBits;
        int elemBytes = typeBits / 8;
        int[] shifts = new int[typeBits];
        int[] remainingBits = new int[typeBits];
        int[] currentBits = new int[typeBits];
        long[] loMasks = new long[typeBits];
        long[] hiMasks = new long[typeBits];
        long[] currWordByteBase = new long[typeBits];
        long[] nextWordByteBase = new long[typeBits];
        long[] outRowByteOff = new long[typeBits];
        for (int row = 0; row < typeBits; row++) {
            int currWord = (row * bitWidth) / typeBits;
            int nextWord = ((row + 1) * bitWidth) / typeBits;
            shifts[row] = (row * bitWidth) % typeBits;
            int rem = (nextWord > currWord) ? ((row + 1) * bitWidth) % typeBits : 0;
            remainingBits[row] = rem;
            int curr = bitWidth - rem;
            currentBits[row] = curr;
            loMasks[row] = rem > 0 ? (1L << curr) - 1L : 0L;
            hiMasks[row] = rem > 0 ? (1L << rem) - 1L : 0L;
            currWordByteBase[row] = (long) lanes * currWord * elemBytes;
            nextWordByteBase[row] = rem > 0 ? (long) lanes * nextWord * elemBytes : 0L;
            int o = row / 8;
            int s = row % 8;
            outRowByteOff[row] = (long) (FL_ORDER[o] * 16 + s * 128) * elemBytes;
        }
        return new UnpackSchedule(shifts, remainingBits, currentBits, loMasks, hiMasks,
                currWordByteBase, nextWordByteBase, outRowByteOff);
    }

    private static void unpackLoop8(MemorySegment buf, int bitWidth, int offset, long rowCount, MemorySegment out) {
        final int lanes = 128;
        long totalElems = rowCount + offset;
        int blockCount = (int) ((totalElems + 1023) / 1024);
        long bitMask = (1L << bitWidth) - 1L;

        UnpackSchedule sch = schedule(8, bitWidth);
        int[] shifts = sch.shifts();
        int[] remainingBits = sch.remainingBits();
        int[] currentBits = sch.currentBits();
        long[] loMasks = sch.loMasks();
        long[] hiMasks = sch.hiMasks();
        long[] currWordByteBase = sch.currWordByteBase();
        long[] nextWordByteBase = sch.nextWordByteBase();
        long[] outRowByteOff = sch.outRowByteOff();

        long blockByteOff = 0L;
        long blockByteStride = 128L * bitWidth;
        for (int block = 0; block < blockCount; block++, blockByteOff += blockByteStride) {
            int blockLogicStart = block * 1024 - offset;
            boolean fullBlock = blockLogicStart >= 0 && blockLogicStart + 1023L < rowCount;

            if (fullBlock) {
                for (int row = 0; row < 8; row++) {
                    int shift = shifts[row];
                    int rem = remainingBits[row];
                    int curr = currentBits[row];
                    long outBase = blockLogicStart + outRowByteOff[row];
                    long wordBase = blockByteOff + currWordByteBase[row];
                    if (rem > 0) {
                        long hiBase = blockByteOff + nextWordByteBase[row];
                        long loMask = loMasks[row];
                        long hiMask = hiMasks[row];
                        for (int lane = 0; lane < lanes; lane++) {
                            long lo = (Byte.toUnsignedLong(buf.get(ValueLayout.JAVA_BYTE, wordBase + lane)) >>> shift) & loMask;
                            long hi = Byte.toUnsignedLong(buf.get(ValueLayout.JAVA_BYTE, hiBase + lane)) & hiMask;
                            out.set(ValueLayout.JAVA_BYTE, outBase + lane, (byte) (lo | (hi << curr)));
                        }
                    } else {
                        for (int lane = 0; lane < lanes; lane++) {
                            out.set(ValueLayout.JAVA_BYTE, outBase + lane,
                                    (byte) ((Byte.toUnsignedLong(buf.get(ValueLayout.JAVA_BYTE, wordBase + lane)) >>> shift) & bitMask));
                        }
                    }
                }
            } else {
                for (int row = 0; row < 8; row++) {
                    int shift = shifts[row];
                    int rem = remainingBits[row];
                    int curr = currentBits[row];
                    int o = row / 8;
                    int s = row % 8;
                    int baseIdx = blockLogicStart + FL_ORDER[o] * 16 + s * 128;
                    long wordBase = blockByteOff + currWordByteBase[row];
                    long hiBase = rem > 0 ? blockByteOff + nextWordByteBase[row] : 0L;
                    long loMask = loMasks[row];
                    long hiMask = hiMasks[row];
                    for (int lane = 0; lane < lanes; lane++) {
                        int logicalIdx = baseIdx + lane;
                        if (logicalIdx < 0 || logicalIdx >= rowCount) {
                            continue;
                        }
                        long src = Byte.toUnsignedLong(buf.get(ValueLayout.JAVA_BYTE, wordBase + lane));
                        long value;
                        if (rem > 0) {
                            long lo = (src >>> shift) & loMask;
                            long hi = Byte.toUnsignedLong(buf.get(ValueLayout.JAVA_BYTE, hiBase + lane)) & hiMask;
                            value = lo | (hi << curr);
                        } else {
                            value = (src >>> shift) & bitMask;
                        }
                        out.set(ValueLayout.JAVA_BYTE, logicalIdx, (byte) value);
                    }
                }
            }
        }
    }

    private static void unpackLoop16(MemorySegment buf, int bitWidth, int offset, long rowCount, MemorySegment out) {
        final int lanes = 64;
        long totalElems = rowCount + offset;
        int blockCount = (int) ((totalElems + 1023) / 1024);
        long bitMask = (1L << bitWidth) - 1L;

        UnpackSchedule sch = schedule(16, bitWidth);
        int[] shifts = sch.shifts();
        int[] remainingBits = sch.remainingBits();
        int[] currentBits = sch.currentBits();
        long[] loMasks = sch.loMasks();
        long[] hiMasks = sch.hiMasks();
        long[] currWordByteBase = sch.currWordByteBase();
        long[] nextWordByteBase = sch.nextWordByteBase();
        long[] outRowByteOff = sch.outRowByteOff();

        long blockByteOff = 0L;
        long blockByteStride = 128L * bitWidth;
        for (int block = 0; block < blockCount; block++, blockByteOff += blockByteStride) {
            int blockLogicStart = block * 1024 - offset;
            boolean fullBlock = blockLogicStart >= 0 && blockLogicStart + 1023L < rowCount;
            long blockOutByteBase = blockLogicStart * 2L;

            if (fullBlock) {
                for (int row = 0; row < 16; row++) {
                    int shift = shifts[row];
                    int rem = remainingBits[row];
                    int curr = currentBits[row];
                    long outBase = blockOutByteBase + outRowByteOff[row];
                    long wordBase = blockByteOff + currWordByteBase[row];
                    if (rem > 0) {
                        long hiBase = blockByteOff + nextWordByteBase[row];
                        long loMask = loMasks[row];
                        long hiMask = hiMasks[row];
                        long laneOff = 0L;
                        for (int lane = 0; lane < lanes; lane++, laneOff += 2L) {
                            long lo = (Short.toUnsignedLong(buf.get(PTypeIO.LE_SHORT, wordBase + laneOff)) >>> shift) & loMask;
                            long hi = Short.toUnsignedLong(buf.get(PTypeIO.LE_SHORT, hiBase + laneOff)) & hiMask;
                            out.set(PTypeIO.LE_SHORT, outBase + laneOff, (short) (lo | (hi << curr)));
                        }
                    } else {
                        long laneOff = 0L;
                        for (int lane = 0; lane < lanes; lane++, laneOff += 2L) {
                            out.set(PTypeIO.LE_SHORT, outBase + laneOff,
                                    (short) ((Short.toUnsignedLong(buf.get(PTypeIO.LE_SHORT, wordBase + laneOff)) >>> shift) & bitMask));
                        }
                    }
                }
            } else {
                for (int row = 0; row < 16; row++) {
                    int shift = shifts[row];
                    int rem = remainingBits[row];
                    int curr = currentBits[row];
                    int o = row / 8;
                    int s = row % 8;
                    int baseIdx = blockLogicStart + FL_ORDER[o] * 16 + s * 128;
                    long wordBase = blockByteOff + currWordByteBase[row];
                    long hiBase = rem > 0 ? blockByteOff + nextWordByteBase[row] : 0L;
                    long loMask = loMasks[row];
                    long hiMask = hiMasks[row];
                    for (int lane = 0; lane < lanes; lane++) {
                        int logicalIdx = baseIdx + lane;
                        if (logicalIdx < 0 || logicalIdx >= rowCount) {
                            continue;
                        }
                        long src = Short.toUnsignedLong(buf.get(PTypeIO.LE_SHORT, wordBase + (long) lane * 2));
                        long value;
                        if (rem > 0) {
                            long lo = (src >>> shift) & loMask;
                            long hi = Short.toUnsignedLong(buf.get(PTypeIO.LE_SHORT, hiBase + (long) lane * 2)) & hiMask;
                            value = lo | (hi << curr);
                        } else {
                            value = (src >>> shift) & bitMask;
                        }
                        out.set(PTypeIO.LE_SHORT, (long) logicalIdx * 2, (short) value);
                    }
                }
            }
        }
    }

    private static void unpackLoop32(MemorySegment buf, int bitWidth, int offset, long rowCount, MemorySegment out) {
        final int lanes = 32;
        long totalElems = rowCount + offset;
        int blockCount = (int) ((totalElems + 1023) / 1024);
        long bitMask = (1L << bitWidth) - 1L;

        UnpackSchedule sch = schedule(32, bitWidth);
        int[] shifts = sch.shifts();
        int[] remainingBits = sch.remainingBits();
        int[] currentBits = sch.currentBits();
        long[] loMasks = sch.loMasks();
        long[] hiMasks = sch.hiMasks();
        long[] currWordByteBase = sch.currWordByteBase();
        long[] nextWordByteBase = sch.nextWordByteBase();
        long[] outRowByteOff = sch.outRowByteOff();

        long blockByteOff = 0L;
        long blockByteStride = 128L * bitWidth;
        for (int block = 0; block < blockCount; block++, blockByteOff += blockByteStride) {
            int blockLogicStart = block * 1024 - offset;
            boolean fullBlock = blockLogicStart >= 0 && blockLogicStart + 1023L < rowCount;
            long blockOutByteBase = blockLogicStart * 4L;

            if (fullBlock) {
                for (int row = 0; row < 32; row++) {
                    int shift = shifts[row];
                    int rem = remainingBits[row];
                    int curr = currentBits[row];
                    long outBase = blockOutByteBase + outRowByteOff[row];
                    long wordBase = blockByteOff + currWordByteBase[row];
                    if (rem > 0) {
                        long hiBase = blockByteOff + nextWordByteBase[row];
                        long loMask = loMasks[row];
                        long hiMask = hiMasks[row];
                        long laneOff = 0L;
                        for (int lane = 0; lane < lanes; lane++, laneOff += 4L) {
                            long lo = (Integer.toUnsignedLong(buf.get(PTypeIO.LE_INT, wordBase + laneOff)) >>> shift) & loMask;
                            long hi = Integer.toUnsignedLong(buf.get(PTypeIO.LE_INT, hiBase + laneOff)) & hiMask;
                            out.set(PTypeIO.LE_INT, outBase + laneOff, (int) (lo | (hi << curr)));
                        }
                    } else {
                        long laneOff = 0L;
                        for (int lane = 0; lane < lanes; lane++, laneOff += 4L) {
                            out.set(PTypeIO.LE_INT, outBase + laneOff,
                                    (int) ((Integer.toUnsignedLong(buf.get(PTypeIO.LE_INT, wordBase + laneOff)) >>> shift) & bitMask));
                        }
                    }
                }
            } else {
                for (int row = 0; row < 32; row++) {
                    int shift = shifts[row];
                    int rem = remainingBits[row];
                    int curr = currentBits[row];
                    int o = row / 8;
                    int s = row % 8;
                    int baseIdx = blockLogicStart + FL_ORDER[o] * 16 + s * 128;
                    long wordBase = blockByteOff + currWordByteBase[row];
                    long hiBase = rem > 0 ? blockByteOff + nextWordByteBase[row] : 0L;
                    long loMask = loMasks[row];
                    long hiMask = hiMasks[row];
                    for (int lane = 0; lane < lanes; lane++) {
                        int logicalIdx = baseIdx + lane;
                        if (logicalIdx < 0 || logicalIdx >= rowCount) {
                            continue;
                        }
                        long src = Integer.toUnsignedLong(buf.get(PTypeIO.LE_INT, wordBase + (long) lane * 4));
                        long value;
                        if (rem > 0) {
                            long lo = (src >>> shift) & loMask;
                            long hi = Integer.toUnsignedLong(buf.get(PTypeIO.LE_INT, hiBase + (long) lane * 4)) & hiMask;
                            value = lo | (hi << curr);
                        } else {
                            value = (src >>> shift) & bitMask;
                        }
                        out.set(PTypeIO.LE_INT, (long) logicalIdx * 4, (int) value);
                    }
                }
            }
        }
    }

    private static void unpackLoop64(MemorySegment buf, int bitWidth, int offset, long rowCount, MemorySegment out) {
        final int lanes = 16;
        long totalElems = rowCount + offset;
        int blockCount = (int) ((totalElems + 1023) / 1024);
        long bitMask = bitWidth == 64 ? -1L : (1L << bitWidth) - 1L;

        UnpackSchedule sch = schedule(64, bitWidth);
        int[] shifts = sch.shifts();
        int[] remainingBits = sch.remainingBits();
        int[] currentBits = sch.currentBits();
        long[] loMasks = sch.loMasks();
        long[] hiMasks = sch.hiMasks();
        long[] currWordByteBase = sch.currWordByteBase();
        long[] nextWordByteBase = sch.nextWordByteBase();
        long[] outRowByteOff = sch.outRowByteOff();

        long blockByteOff = 0L;
        long blockByteStride = 128L * bitWidth;
        for (int block = 0; block < blockCount; block++, blockByteOff += blockByteStride) {
            int blockLogicStart = block * 1024 - offset;
            boolean fullBlock = blockLogicStart >= 0 && blockLogicStart + 1023L < rowCount;
            long blockOutByteBase = blockLogicStart * 8L;

            if (fullBlock) {
                for (int row = 0; row < 64; row++) {
                    int shift = shifts[row];
                    int rem = remainingBits[row];
                    int curr = currentBits[row];
                    long outBase = blockOutByteBase + outRowByteOff[row];
                    long wordBase = blockByteOff + currWordByteBase[row];
                    if (rem > 0) {
                        long hiBase = blockByteOff + nextWordByteBase[row];
                        long loMask = loMasks[row];
                        long hiMask = hiMasks[row];
                        long laneOff = 0L;
                        for (int lane = 0; lane < lanes; lane++, laneOff += 8L) {
                            long lo = (buf.get(PTypeIO.LE_LONG, wordBase + laneOff) >>> shift) & loMask;
                            long hi = buf.get(PTypeIO.LE_LONG, hiBase + laneOff) & hiMask;
                            out.set(PTypeIO.LE_LONG, outBase + laneOff, lo | (hi << curr));
                        }
                    } else {
                        long laneOff = 0L;
                        for (int lane = 0; lane < lanes; lane++, laneOff += 8L) {
                            out.set(PTypeIO.LE_LONG, outBase + laneOff,
                                    (buf.get(PTypeIO.LE_LONG, wordBase + laneOff) >>> shift) & bitMask);
                        }
                    }
                }
            } else {
                for (int row = 0; row < 64; row++) {
                    int shift = shifts[row];
                    int rem = remainingBits[row];
                    int curr = currentBits[row];
                    int o = row / 8;
                    int s = row % 8;
                    int baseIdx = blockLogicStart + FL_ORDER[o] * 16 + s * 128;
                    long wordBase = blockByteOff + currWordByteBase[row];
                    long hiBase = rem > 0 ? blockByteOff + nextWordByteBase[row] : 0L;
                    long loMask = loMasks[row];
                    long hiMask = hiMasks[row];
                    for (int lane = 0; lane < lanes; lane++) {
                        int logicalIdx = baseIdx + lane;
                        if (logicalIdx < 0 || logicalIdx >= rowCount) {
                            continue;
                        }
                        long src = buf.get(PTypeIO.LE_LONG, wordBase + (long) lane * 8);
                        long value;
                        if (rem > 0) {
                            long lo = (src >>> shift) & loMask;
                            long hi = buf.get(PTypeIO.LE_LONG, hiBase + (long) lane * 8) & hiMask;
                            value = lo | (hi << curr);
                        } else {
                            value = (src >>> shift) & bitMask;
                        }
                        out.set(PTypeIO.LE_LONG, (long) logicalIdx * 8, value);
                    }
                }
            }
        }
    }

    private static void applyPatches(DecodeContext ctx, PatchesMetadata pm,
            MemorySegment out, int elemBytes) {
        long numPatches = pm.len();
        if (numPatches == 0) {
            return;
        }
        long offset = pm.offset();
        PType idxPtype = ptypeFromProto(pm.indices_ptype());

        MemorySegment idxSeg = ctx.decodeChildSegment(0, new DType.Primitive(idxPtype, false), numPatches);
        MemorySegment valSeg = ctx.decodeChildSegment(1, ctx.dtype(), numPatches);

        int idxBytes = idxPtype.byteSize();
        long n = ctx.rowCount();
        for (long i = 0; i < numPatches; i++) {
            long absIdx = readUnsignedIdx(idxSeg, SegmentBroadcast.elementOffset(idxSeg, i, idxBytes), idxPtype) - offset;
            if (absIdx < 0 || absIdx >= n) {
                throw new VortexException(EncodingId.FASTLANES_BITPACKED,
                        "patch index " + absIdx + " out of range [0," + n + ")");
            }
            MemorySegment.copy(valSeg, SegmentBroadcast.elementOffset(valSeg, i, elemBytes),
                    out, absIdx * elemBytes, elemBytes);
        }
    }

    private static long readUnsignedIdx(MemorySegment seg, long off, PType ptype) {
        return switch (ptype) {
            case U8 -> Byte.toUnsignedLong(seg.get(ValueLayout.JAVA_BYTE, off));
            case U16 -> Short.toUnsignedLong(seg.get(PTypeIO.LE_SHORT, off));
            case U32 -> Integer.toUnsignedLong(seg.get(PTypeIO.LE_INT, off));
            case U64 -> seg.get(PTypeIO.LE_LONG, off);
            default -> throw new VortexException(EncodingId.FASTLANES_BITPACKED,
                    "non-unsigned patch index ptype " + ptype);
        };
    }

    private static PType ptypeFromProto(io.github.dfa1.vortex.proto.PType proto) {
        return PType.fromOrdinal(proto.value());
    }
}
