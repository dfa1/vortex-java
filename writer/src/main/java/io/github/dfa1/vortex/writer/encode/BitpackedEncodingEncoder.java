package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.encoding.EncodeContext;
import io.github.dfa1.vortex.encoding.EncodeNode;
import io.github.dfa1.vortex.encoding.EncodeResult;
import io.github.dfa1.vortex.encoding.EncodingEncoder;
import io.github.dfa1.vortex.encoding.EncodingId;
import io.github.dfa1.vortex.encoding.PTypeIO;
import io.github.dfa1.vortex.proto.BitPackedMetadata;
import io.github.dfa1.vortex.proto.ScalarValue;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.util.List;

/// Write-only encoder for {@code fastlanes.bitpacked}.
public final class BitpackedEncodingEncoder implements EncodingEncoder {
    private static final int[] FL_ORDER = {0, 4, 2, 6, 1, 5, 3, 7};

    /// Public no-arg constructor required by {@link java.util.ServiceLoader}.
    public BitpackedEncodingEncoder() {
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

        byte[] metaBytes = new BitPackedMetadata(bitWidth, 0, null).encode();

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
            return ScalarValue.ofUint64Value(value).encode();
        }
        return ScalarValue.ofInt64Value(value).encode();
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
