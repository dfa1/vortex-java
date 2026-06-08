package io.github.dfa1.vortex.encoding;

import com.google.protobuf.InvalidProtocolBufferException;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.array.ByteArray;
import io.github.dfa1.vortex.core.array.IntArray;
import io.github.dfa1.vortex.core.array.LongArray;
import io.github.dfa1.vortex.core.array.ShortArray;
import io.github.dfa1.vortex.proto.DTypeProtos;
import io.github.dfa1.vortex.proto.EncodingProtos;
import io.github.dfa1.vortex.proto.ScalarProtos;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.util.List;

/// Encoding for {@code fastlanes.bitpacked} — spec-compliant FastLanes bit-packing.
///
/// <p>Metadata: protobuf {@code BitPackedMetadata} — {@code bit_width u32} (tag 1),
/// {@code offset u32} (tag 2, element offset within the first 1024-element block).
///
/// <p>Buffer layout: {@code ceil((len + offset) / 1024)} blocks, each block {@code 128 * bit_width}
/// bytes. Within each block the values are transposed using the FastLanes FL_ORDER permutation so
/// that adjacent bit-planes are contiguous (enables SIMD-friendly decompression).
public final class BitpackedEncoding implements Encoding {

    // FL_ORDER permutation from the FastLanes paper / spiraldb/fastlanes-rs.
    static final int[] FL_ORDER = {0, 4, 2, 6, 1, 5, 3, 7};

    /// Creates a new {@code BitpackedEncoding} instance; use via {@link EncodingRegistry}.
    public BitpackedEncoding() {
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
    public EncodeResult encode(DType dtype, Object data, EncodeContext ctx) {
        return Encoder.encode(dtype, data, ctx);
    }

    @Override
    public Array decode(DecodeContext ctx) {
        return Decoder.decode(ctx);
    }

    private static final class Encoder {

        static EncodeResult encode(DType dtype, Object data, EncodeContext ctx) {
            PType ptype = ((DType.Primitive) dtype).ptype();
            long[] longs = toLongs(data, ptype);
            int n = longs.length;
            int typeBits = ptype.byteSize() * 8;
            long typeMask = typeMask(typeBits);
            boolean unsign = isUnsigned(ptype);

            long signedMin = 0L;
            long signedMax = 0L;
            long maxUnsigned = 0L;
            int bitWidth = 0;

            if (n > 0) {
                signedMin = longs[0];
                signedMax = longs[0];
                for (long v : longs) {
                    if (unsign ? Long.compareUnsigned(v, signedMin) < 0 : v < signedMin) {
                        signedMin = v;
                    }
                    if (unsign ? Long.compareUnsigned(v, signedMax) > 0 : v > signedMax) {
                        signedMax = v;
                    }
                    long uv = v & typeMask;
                    if (Long.compareUnsigned(uv, maxUnsigned) > 0) {
                        maxUnsigned = uv;
                    }
                }
                bitWidth = maxUnsigned == 0L ? 0 : (Long.SIZE - Long.numberOfLeadingZeros(maxUnsigned));
            }

            MemorySegment packed = packFastLanes(longs, n, bitWidth, typeBits, ctx.arena());

            byte[] metaBytes = EncodingProtos.BitPackedMetadata.newBuilder()
                                       .setBitWidth(bitWidth)
                                       .setOffset(0)
                                       .build()
                                       .toByteArray();

            byte[] statsMin = n > 0 ? statsBytes(ptype, signedMin) : null;
            byte[] statsMax = n > 0 ? statsBytes(ptype, signedMax) : null;

            EncodeNode root = new EncodeNode(EncodingId.FASTLANES_BITPACKED, ByteBuffer.wrap(metaBytes),
                    new EncodeNode[0], new int[]{0});
            return new EncodeResult(root, List.of(packed), statsMin, statsMax);
        }

        private static MemorySegment packFastLanes(long[] values, int n, int bitWidth, int typeBits, Arena arena) {
            if (bitWidth == 0 || n == 0) {
                return MemorySegment.ofArray(new byte[0]);
            }
            int lanes = 1024 / typeBits;
            int wordBytes = typeBits / 8;
            int blockCount = (n + 1023) / 1024;
            long typeMask = typeMask(typeBits);
            MemorySegment seg = arena.allocate((long) blockCount * 128 * bitWidth);

            for (int block = 0; block < blockCount; block++) {
                int blockByteOff = block * 128 * bitWidth;
                int blockStart = block * 1024;

                for (int row = 0; row < typeBits; row++) {
                    int currWord = (row * bitWidth) / typeBits;
                    int nextWord = ((row + 1) * bitWidth) / typeBits;
                    int shift = (row * bitWidth) % typeBits;
                    int remainingBits = (nextWord > currWord) ? ((row + 1) * bitWidth) % typeBits : 0;
                    int currentBits = bitWidth - remainingBits;

                    for (int lane = 0; lane < lanes; lane++) {
                        int o = row / 8;
                        int s = row % 8;
                        int logicalIdx = blockStart + FL_ORDER[o] * 16 + s * 128 + lane;
                        long value = (logicalIdx < n) ? (values[logicalIdx] & typeMask) : 0L;

                        int wordOff = blockByteOff + (lanes * currWord + lane) * wordBytes;
                        long existing = readWordFromSeg(seg, wordOff, typeBits);
                        existing |= (value << shift) & typeMask;
                        writeWordToSeg(seg, wordOff, existing, typeBits);

                        if (remainingBits > 0) {
                            int hiWordOff = blockByteOff + (lanes * nextWord + lane) * wordBytes;
                            long existingHi = readWordFromSeg(seg, hiWordOff, typeBits);
                            existingHi |= (value >>> currentBits) & typeMask;
                            writeWordToSeg(seg, hiWordOff, existingHi, typeBits);
                        }
                    }
                }
            }
            return seg;
        }

        private static long[] toLongs(Object data, PType ptype) {
            return switch (ptype) {
                case I8 -> {
                    byte[] arr = (byte[]) data;
                    long[] r = new long[arr.length];
                    for (int i = 0; i < arr.length; i++) {
                        r[i] = arr[i];
                    }
                    yield r;
                }
                case U8 -> {
                    byte[] arr = (byte[]) data;
                    long[] r = new long[arr.length];
                    for (int i = 0; i < arr.length; i++) {
                        r[i] = Byte.toUnsignedLong(arr[i]);
                    }
                    yield r;
                }
                case I16 -> {
                    short[] arr = (short[]) data;
                    long[] r = new long[arr.length];
                    for (int i = 0; i < arr.length; i++) {
                        r[i] = arr[i];
                    }
                    yield r;
                }
                case U16 -> {
                    short[] arr = (short[]) data;
                    long[] r = new long[arr.length];
                    for (int i = 0; i < arr.length; i++) {
                        r[i] = Short.toUnsignedLong(arr[i]);
                    }
                    yield r;
                }
                case I32 -> {
                    int[] arr = (int[]) data;
                    long[] r = new long[arr.length];
                    for (int i = 0; i < arr.length; i++) {
                        r[i] = arr[i];
                    }
                    yield r;
                }
                case U32 -> {
                    int[] arr = (int[]) data;
                    long[] r = new long[arr.length];
                    for (int i = 0; i < arr.length; i++) {
                        r[i] = Integer.toUnsignedLong(arr[i]);
                    }
                    yield r;
                }
                case I64, U64 -> (long[]) data;
                default -> throw new VortexException(EncodingId.FASTLANES_BITPACKED, "unsupported ptype: " + ptype);
            };
        }

        private static long typeMask(int typeBits) {
            return typeBits == 64 ? -1L : (1L << typeBits) - 1L;
        }

        private static boolean isUnsigned(PType ptype) {
            return switch (ptype) {
                case U8, U16, U32, U64 -> true;
                default -> false;
            };
        }

        private static byte[] statsBytes(PType ptype, long value) {
            if (isUnsigned(ptype)) {
                return ScalarProtos.ScalarValue.newBuilder().setUint64Value(value).build().toByteArray();
            }
            return ScalarProtos.ScalarValue.newBuilder().setInt64Value(value).build().toByteArray();
        }

        private static long readWordFromSeg(MemorySegment seg, int off, int typeBits) {
            return switch (typeBits) {
                case 8 -> Byte.toUnsignedLong(seg.get(ValueLayout.JAVA_BYTE, off));
                case 16 -> Short.toUnsignedLong(seg.get(PTypeIO.LE_SHORT, off));
                case 32 -> Integer.toUnsignedLong(seg.get(PTypeIO.LE_INT, off));
                case 64 -> seg.get(PTypeIO.LE_LONG, off);
                default ->
                        throw new VortexException(EncodingId.FASTLANES_BITPACKED, "unsupported typeBits: " + typeBits);
            };
        }

        private static void writeWordToSeg(MemorySegment seg, int off, long value, int typeBits) {
            switch (typeBits) {
                case 8 -> seg.set(ValueLayout.JAVA_BYTE, off, (byte) value);
                case 16 -> seg.set(PTypeIO.LE_SHORT, off, (short) value);
                case 32 -> seg.set(PTypeIO.LE_INT, off, (int) value);
                case 64 -> seg.set(PTypeIO.LE_LONG, off, value);
                default ->
                        throw new VortexException(EncodingId.FASTLANES_BITPACKED, "unsupported typeBits: " + typeBits);
            }
        }
    }

    private static final class Decoder {

        static Array decode(DecodeContext ctx) {
            ByteBuffer rawMeta = ctx.metadata();
            if (rawMeta == null) {
                throw new VortexException(EncodingId.FASTLANES_BITPACKED, "missing metadata");
            }

            EncodingProtos.BitPackedMetadata meta;
            try {
                meta = EncodingProtos.BitPackedMetadata.parseFrom(rawMeta.duplicate());
            } catch (InvalidProtocolBufferException e) {
                throw new VortexException(EncodingId.FASTLANES_BITPACKED, "invalid metadata", e);
            }

            int bitWidth = meta.getBitWidth();
            int offset = meta.getOffset();
            PType ptype = ((DType.Primitive) ctx.dtype()).ptype();
            int typeBits = ptype.byteSize() * 8;
            long rowCount = ctx.rowCount();

            MemorySegment packed = ctx.buffer(0);
            MemorySegment output = ctx.arena().allocate(rowCount * ptype.byteSize());
            fastlanesUnpackToSeg(packed, bitWidth, offset, typeBits, rowCount, output);

            if (meta.hasPatches()) {
                applyPatches(ctx, meta.getPatches(), output, ptype.byteSize());
            }

            return switch (ptype) {
                case I64, U64 -> new LongArray(ctx.dtype(), rowCount, output);
                case I32, U32 -> new IntArray(ctx.dtype(), rowCount, output);
                case I16, U16 -> new ShortArray(ctx.dtype(), rowCount, output);
                case I8, U8 -> new ByteArray(ctx.dtype(), rowCount, output);
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

        private static void unpackLoop8(MemorySegment buf, int bitWidth, int offset, long rowCount, MemorySegment out) {
            final int lanes = 128;
            long totalElems = rowCount + offset;
            int blockCount = (int) ((totalElems + 1023) / 1024);
            long bitMask = (1L << bitWidth) - 1L;

            // Hoist per-row bookkeeping (8 rows × bitWidth-dependent). See unpackLoop64 for the
            // same shape — comments there explain why this is a measurable win.
            int[] shifts = new int[8];
            int[] remainingBits = new int[8];
            int[] currentBits = new int[8];
            long[] loMasks = new long[8];
            long[] hiMasks = new long[8];
            long[] currWordByteBase = new long[8];
            long[] nextWordByteBase = new long[8];
            long[] outRowByteOff = new long[8];
            for (int row = 0; row < 8; row++) {
                int currWord = (row * bitWidth) / 8;
                int nextWord = ((row + 1) * bitWidth) / 8;
                shifts[row] = (row * bitWidth) % 8;
                int rem = (nextWord > currWord) ? ((row + 1) * bitWidth) % 8 : 0;
                remainingBits[row] = rem;
                int curr = bitWidth - rem;
                currentBits[row] = curr;
                loMasks[row] = rem > 0 ? (1L << curr) - 1L : 0L;
                hiMasks[row] = rem > 0 ? (1L << rem) - 1L : 0L;
                currWordByteBase[row] = (long) lanes * currWord;
                nextWordByteBase[row] = rem > 0 ? (long) lanes * nextWord : 0L;
                int o = row / 8;
                int s = row % 8;
                outRowByteOff[row] = FL_ORDER[o] * 16 + s * 128;
            }

            long blockByteOff = 0L;
            long blockByteStride = 128L * bitWidth;
            for (int block = 0; block < blockCount; block++, blockByteOff += blockByteStride) {
                int blockLogicStart = block * 1024 - offset;
                boolean fullBlock = blockLogicStart >= 0 && (long) blockLogicStart + 1023L < rowCount;

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

            int[] shifts = new int[16];
            int[] remainingBits = new int[16];
            int[] currentBits = new int[16];
            long[] loMasks = new long[16];
            long[] hiMasks = new long[16];
            long[] currWordByteBase = new long[16];
            long[] nextWordByteBase = new long[16];
            long[] outRowByteOff = new long[16];
            for (int row = 0; row < 16; row++) {
                int currWord = (row * bitWidth) / 16;
                int nextWord = ((row + 1) * bitWidth) / 16;
                shifts[row] = (row * bitWidth) % 16;
                int rem = (nextWord > currWord) ? ((row + 1) * bitWidth) % 16 : 0;
                remainingBits[row] = rem;
                int curr = bitWidth - rem;
                currentBits[row] = curr;
                loMasks[row] = rem > 0 ? (1L << curr) - 1L : 0L;
                hiMasks[row] = rem > 0 ? (1L << rem) - 1L : 0L;
                currWordByteBase[row] = (long) lanes * currWord * 2L;
                nextWordByteBase[row] = rem > 0 ? (long) lanes * nextWord * 2L : 0L;
                int o = row / 8;
                int s = row % 8;
                outRowByteOff[row] = (long) (FL_ORDER[o] * 16 + s * 128) * 2L;
            }

            long blockByteOff = 0L;
            long blockByteStride = 128L * bitWidth;
            for (int block = 0; block < blockCount; block++, blockByteOff += blockByteStride) {
                int blockLogicStart = block * 1024 - offset;
                boolean fullBlock = blockLogicStart >= 0 && (long) blockLogicStart + 1023L < rowCount;
                long blockOutByteBase = (long) blockLogicStart * 2L;

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

            int[] shifts = new int[32];
            int[] remainingBits = new int[32];
            int[] currentBits = new int[32];
            long[] loMasks = new long[32];
            long[] hiMasks = new long[32];
            long[] currWordByteBase = new long[32];
            long[] nextWordByteBase = new long[32];
            long[] outRowByteOff = new long[32];
            for (int row = 0; row < 32; row++) {
                int currWord = (row * bitWidth) / 32;
                int nextWord = ((row + 1) * bitWidth) / 32;
                shifts[row] = (row * bitWidth) % 32;
                int rem = (nextWord > currWord) ? ((row + 1) * bitWidth) % 32 : 0;
                remainingBits[row] = rem;
                int curr = bitWidth - rem;
                currentBits[row] = curr;
                loMasks[row] = rem > 0 ? (1L << curr) - 1L : 0L;
                hiMasks[row] = rem > 0 ? (1L << rem) - 1L : 0L;
                currWordByteBase[row] = (long) lanes * currWord * 4L;
                nextWordByteBase[row] = rem > 0 ? (long) lanes * nextWord * 4L : 0L;
                int o = row / 8;
                int s = row % 8;
                outRowByteOff[row] = (long) (FL_ORDER[o] * 16 + s * 128) * 4L;
            }

            long blockByteOff = 0L;
            long blockByteStride = 128L * bitWidth;
            for (int block = 0; block < blockCount; block++, blockByteOff += blockByteStride) {
                int blockLogicStart = block * 1024 - offset;
                boolean fullBlock = blockLogicStart >= 0 && (long) blockLogicStart + 1023L < rowCount;
                long blockOutByteBase = (long) blockLogicStart * 4L;

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

            // Per-row bookkeeping depends only on `row` and `bitWidth`; both are constant for
            // the duration of this call. Pre-compute once instead of recomputing 7 derived
            // ints per row per block. For a 10M-row I64 column with bitWidth ~20 that's
            // 65K rows × 7 ops eliminated from the inner loop — material on a kernel that
            // shows up as the largest visible Java frame in JFR.
            int[] shifts = new int[64];
            int[] remainingBits = new int[64];
            int[] currentBits = new int[64];
            long[] loMasks = new long[64];
            long[] hiMasks = new long[64];
            long[] currWordByteBase = new long[64];   // lanes * currWord * 8
            long[] nextWordByteBase = new long[64];   // lanes * nextWord * 8 (0 when remainingBits == 0)
            // Output offset within a block, * 8 (bytes). Per-row, independent of block.
            long[] outRowByteOff = new long[64];
            for (int row = 0; row < 64; row++) {
                int currWord = (row * bitWidth) / 64;
                int nextWord = ((row + 1) * bitWidth) / 64;
                shifts[row] = (row * bitWidth) % 64;
                int rem = (nextWord > currWord) ? ((row + 1) * bitWidth) % 64 : 0;
                remainingBits[row] = rem;
                int curr = bitWidth - rem;
                currentBits[row] = curr;
                loMasks[row] = rem > 0 ? (1L << curr) - 1L : 0L;
                hiMasks[row] = rem > 0 ? (1L << rem) - 1L : 0L;
                currWordByteBase[row] = (long) lanes * currWord * 8L;
                nextWordByteBase[row] = rem > 0 ? (long) lanes * nextWord * 8L : 0L;
                int o = row / 8;
                int s = row % 8;
                outRowByteOff[row] = (long) (FL_ORDER[o] * 16 + s * 128) * 8L;
            }

            long blockByteOff = 0L;
            long blockByteStride = 128L * bitWidth;
            for (int block = 0; block < blockCount; block++, blockByteOff += blockByteStride) {
                int blockLogicStart = block * 1024 - offset;
                boolean fullBlock = blockLogicStart >= 0 && (long) blockLogicStart + 1023L < rowCount;
                long blockOutByteBase = (long) blockLogicStart * 8L;

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

        private static void applyPatches(DecodeContext ctx, EncodingProtos.PatchesMetadata pm,
                MemorySegment out, int elemBytes) {
            long numPatches = pm.getLen();
            if (numPatches == 0) {
                return;
            }
            long offset = pm.getOffset();
            PType idxPtype = ptypeFromProto(pm.getIndicesPtype());

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

        private static PType ptypeFromProto(DTypeProtos.PType proto) {
            return PType.fromOrdinal(proto.getNumber());
        }
    }
}
