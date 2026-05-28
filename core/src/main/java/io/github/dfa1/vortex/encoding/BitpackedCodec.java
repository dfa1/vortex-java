package io.github.dfa1.vortex.encoding;

import com.google.protobuf.InvalidProtocolBufferException;
import dev.vortex.proto.EncodingProtos;
import dev.vortex.proto.ScalarProtos;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/// Codec for {@code fastlanes.bitpacked} — spec-compliant FastLanes bit-packing.
///
/// <p>Metadata: protobuf {@code BitPackedMetadata} — {@code bit_width u32} (tag 1),
/// {@code offset u32} (tag 2, element offset within the first 1024-element block).
///
/// <p>Buffer layout: {@code ceil((len + offset) / 1024)} blocks, each block {@code 128 * bit_width}
/// bytes. Within each block the values are transposed using the FastLanes FL_ORDER permutation so
/// that adjacent bit-planes are contiguous (enables SIMD-friendly decompression).
public final class BitpackedCodec implements Codec {

    // FL_ORDER permutation from the FastLanes paper / spiraldb/fastlanes-rs.
    private static final int[] FL_ORDER = {0, 4, 2, 6, 1, 5, 3, 7};

    @Override
    public String encodingId() {
        return "fastlanes.bitpacked";
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

    // ── Encode ────────────────────────────────────────────────────────────────

    @Override
    public EncodeResult encode(DType dtype, Object data) {
        PType  ptype    = ((DType.Primitive) dtype).ptype();
        long[] longs    = toLongs(data, ptype);
        int    n        = longs.length;
        int    typeBits = ptype.byteSize() * 8;
        long   typeMask = typeMask(typeBits);
        boolean unsign  = isUnsigned(ptype);

        long signedMin  = 0L;
        long signedMax  = 0L;
        long maxUnsigned = 0L;
        int  bitWidth   = 0;

        if (n > 0) {
            signedMin = longs[0];
            signedMax = longs[0];
            for (int i = 0; i < n; i++) {
                long v = longs[i];
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

        ByteBuffer packed = packFastLanes(longs, n, bitWidth, typeBits);

        byte[] metaBytes = EncodingProtos.BitPackedMetadata.newBuilder()
            .setBitWidth(bitWidth)
            .setOffset(0)
            .build()
            .toByteArray();

        byte[] statsMin = n > 0 ? statsBytes(ptype, signedMin) : null;
        byte[] statsMax = n > 0 ? statsBytes(ptype, signedMax) : null;

        EncodeNode root = new EncodeNode(encodingId(), ByteBuffer.wrap(metaBytes),
            new EncodeNode[0], new int[]{0});
        return new EncodeResult(root, List.of(packed), statsMin, statsMax);
    }

    // ── Decode ────────────────────────────────────────────────────────────────

    @Override
    public Array decode(DecodeContext ctx) {
        ByteBuffer rawMeta = ctx.metadata();
        if (rawMeta == null) {
            throw new IllegalStateException("fastlanes.bitpacked: missing metadata");
        }

        EncodingProtos.BitPackedMetadata meta;
        try {
            byte[] bytes = new byte[rawMeta.remaining()];
            rawMeta.duplicate().get(bytes);
            meta = EncodingProtos.BitPackedMetadata.parseFrom(bytes);
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalStateException("fastlanes.bitpacked: invalid metadata", e);
        }

        int  bitWidth = meta.getBitWidth();
        int  offset   = meta.getOffset();
        PType ptype   = ((DType.Primitive) ctx.dtype()).ptype();
        int  typeBits = ptype.byteSize() * 8;
        long rowCount = ctx.rowCount();

        MemorySegment packed = ctx.buffer(0);
        long[] longs = fastlanesUnpack(packed, bitWidth, offset, typeBits, rowCount);

        return new Array(ctx.dtype(), rowCount,
            new MemorySegment[]{fromLongs(longs, ptype)}, new Array[0], ArrayStats.empty());
    }

    // ── FastLanes pack ────────────────────────────────────────────────────────

    private static ByteBuffer packFastLanes(long[] values, int n, int bitWidth, int typeBits) {
        if (bitWidth == 0 || n == 0) {
            return ByteBuffer.wrap(new byte[0]);
        }
        int    LANES      = 1024 / typeBits;
        int    wordBytes  = typeBits / 8;
        int    blockCount = (n + 1023) / 1024;
        long   typeMask   = typeMask(typeBits);
        byte[] buf        = new byte[blockCount * 128 * bitWidth];

        for (int block = 0; block < blockCount; block++) {
            int blockByteOff = block * 128 * bitWidth;
            int blockStart   = block * 1024;

            for (int row = 0; row < typeBits; row++) {
                int currWord  = (row * bitWidth) / typeBits;
                int nextWord  = ((row + 1) * bitWidth) / typeBits;
                int shift     = (row * bitWidth) % typeBits;
                int remainingBits = (nextWord > currWord) ? ((row + 1) * bitWidth) % typeBits : 0;
                int currentBits   = bitWidth - remainingBits;

                for (int lane = 0; lane < LANES; lane++) {
                    int o = row / 8;
                    int s = row % 8;
                    int logicalIdx = blockStart + FL_ORDER[o] * 16 + s * 128 + lane;
                    long value = (logicalIdx < n) ? (values[logicalIdx] & typeMask) : 0L;

                    int wordOff = blockByteOff + (LANES * currWord + lane) * wordBytes;
                    long existing = readWordFromBuf(buf, wordOff, typeBits);
                    existing |= (value << shift) & typeMask;
                    writeWordToBuf(buf, wordOff, existing, typeBits);

                    if (remainingBits > 0) {
                        int hiWordOff = blockByteOff + (LANES * nextWord + lane) * wordBytes;
                        long existingHi = readWordFromBuf(buf, hiWordOff, typeBits);
                        existingHi |= (value >>> currentBits) & typeMask;
                        writeWordToBuf(buf, hiWordOff, existingHi, typeBits);
                    }
                }
            }
        }
        return ByteBuffer.wrap(buf);
    }

    // ── FastLanes unpack ──────────────────────────────────────────────────────

    private static long[] fastlanesUnpack(
        MemorySegment buf, int bitWidth, int offset, int typeBits, long rowCount) {
        long[] output = new long[(int) rowCount];
        if (bitWidth == 0) {
            return output;
        }

        int  LANES      = 1024 / typeBits;
        int  wordBytes  = typeBits / 8;
        long totalElems = rowCount + offset;
        int  blockCount = (int) ((totalElems + 1023) / 1024);
        long bitMask    = bitWidth == 64 ? -1L : (1L << bitWidth) - 1L;

        long blockByteOff    = 0L;
        long blockByteStride = 128L * bitWidth;
        for (int block = 0; block < blockCount; block++, blockByteOff += blockByteStride) {
            int blockLogicStart = block * 1024 - offset;

            for (int row = 0; row < typeBits; row++) {
                int currWord      = (row * bitWidth) / typeBits;
                int nextWord      = ((row + 1) * bitWidth) / typeBits;
                int shift         = (row * bitWidth) % typeBits;
                int remainingBits = (nextWord > currWord) ? ((row + 1) * bitWidth) % typeBits : 0;
                int currentBits   = bitWidth - remainingBits;

                // Hoist per-row invariants above the lane loop.
                int  o        = row / 8;
                int  s        = row % 8;
                int  baseIdx  = blockLogicStart + FL_ORDER[o] * 16 + s * 128;
                long wordBase = blockByteOff + (long) (LANES * currWord) * wordBytes;
                long hiBase   = (remainingBits > 0)
                    ? blockByteOff + (long) (LANES * nextWord) * wordBytes : 0L;
                long loMask   = (remainingBits > 0) ? (1L << currentBits) - 1L : 0L;
                long hiMask   = (remainingBits > 0) ? (1L << remainingBits) - 1L : 0L;

                for (int lane = 0; lane < LANES; lane++) {
                    int logicalIdx = baseIdx + lane;

                    if (logicalIdx < 0 || logicalIdx >= rowCount) {
                        continue;
                    }

                    long wordOff = wordBase + (long) lane * wordBytes;
                    long src     = readWordFromSeg(buf, wordOff, typeBits);

                    long value;
                    if (remainingBits > 0) {
                        long lo = (src >>> shift) & loMask;
                        long hi = readWordFromSeg(buf, hiBase + (long) lane * wordBytes, typeBits) & hiMask;
                        value = lo | (hi << currentBits);
                    } else {
                        value = (src >>> shift) & bitMask;
                    }

                    output[logicalIdx] = value;
                }
            }
        }
        return output;
    }

    private static void fastlanesUnpackToSeg(
        MemorySegment buf, int bitWidth, int offset, int typeBits, long rowCount,
        MemorySegment output) {
        if (bitWidth == 0) {
            return;
        }

        int  LANES      = 1024 / typeBits;
        int  wordBytes  = typeBits / 8;
        long totalElems = rowCount + offset;
        int  blockCount = (int) ((totalElems + 1023) / 1024);
        long bitMask    = bitWidth == 64 ? -1L : (1L << bitWidth) - 1L;

        long blockByteOff    = 0L;
        long blockByteStride = 128L * bitWidth;
        for (int block = 0; block < blockCount; block++, blockByteOff += blockByteStride) {
            int blockLogicStart = block * 1024 - offset;

            for (int row = 0; row < typeBits; row++) {
                int currWord      = (row * bitWidth) / typeBits;
                int nextWord      = ((row + 1) * bitWidth) / typeBits;
                int shift         = (row * bitWidth) % typeBits;
                int remainingBits = (nextWord > currWord) ? ((row + 1) * bitWidth) % typeBits : 0;
                int currentBits   = bitWidth - remainingBits;

                // Hoist per-row invariants above the lane loop.
                int  o        = row / 8;
                int  s        = row % 8;
                int  baseIdx  = blockLogicStart + FL_ORDER[o] * 16 + s * 128;
                long wordBase = blockByteOff + (long) (LANES * currWord) * wordBytes;
                long hiBase   = (remainingBits > 0)
                    ? blockByteOff + (long) (LANES * nextWord) * wordBytes : 0L;
                long loMask   = (remainingBits > 0) ? (1L << currentBits) - 1L : 0L;
                long hiMask   = (remainingBits > 0) ? (1L << remainingBits) - 1L : 0L;

                for (int lane = 0; lane < LANES; lane++) {
                    int logicalIdx = baseIdx + lane;

                    if (logicalIdx < 0 || logicalIdx >= rowCount) {
                        continue;
                    }

                    long wordOff = wordBase + (long) lane * wordBytes;
                    long src     = readWordFromSeg(buf, wordOff, typeBits);

                    long value;
                    if (remainingBits > 0) {
                        long lo = (src >>> shift) & loMask;
                        long hi = readWordFromSeg(buf, hiBase + (long) lane * wordBytes, typeBits) & hiMask;
                        value = lo | (hi << currentBits);
                    } else {
                        value = (src >>> shift) & bitMask;
                    }

                    writeWordToSeg(output, (long) logicalIdx * wordBytes, value, typeBits);
                }
            }
        }
    }

    // ── Buffer helpers ────────────────────────────────────────────────────────

    private static long readWordFromBuf(byte[] buf, int off, int typeBits) {
        return switch (typeBits) {
            case 8  -> buf[off] & 0xFFL;
            case 16 -> (buf[off] & 0xFFL) | ((buf[off + 1] & 0xFFL) << 8);
            case 32 -> Integer.toUnsignedLong(
                (buf[off] & 0xFF) | ((buf[off + 1] & 0xFF) << 8)
                | ((buf[off + 2] & 0xFF) << 16) | ((buf[off + 3] & 0xFF) << 24));
            case 64 -> {
                long lo = Integer.toUnsignedLong(
                    (buf[off] & 0xFF) | ((buf[off + 1] & 0xFF) << 8)
                    | ((buf[off + 2] & 0xFF) << 16) | ((buf[off + 3] & 0xFF) << 24));
                long hi = Integer.toUnsignedLong(
                    (buf[off + 4] & 0xFF) | ((buf[off + 5] & 0xFF) << 8)
                    | ((buf[off + 6] & 0xFF) << 16) | ((buf[off + 7] & 0xFF) << 24));
                yield lo | (hi << 32);
            }
            default -> throw new IllegalArgumentException("unsupported typeBits: " + typeBits);
        };
    }

    private static void writeWordToBuf(byte[] buf, int off, long value, int typeBits) {
        switch (typeBits) {
            case 8  -> buf[off] = (byte) value;
            case 16 -> {
                buf[off]     = (byte) value;
                buf[off + 1] = (byte) (value >>> 8);
            }
            case 32 -> {
                buf[off]     = (byte) value;
                buf[off + 1] = (byte) (value >>> 8);
                buf[off + 2] = (byte) (value >>> 16);
                buf[off + 3] = (byte) (value >>> 24);
            }
            case 64 -> {
                buf[off]     = (byte) value;
                buf[off + 1] = (byte) (value >>> 8);
                buf[off + 2] = (byte) (value >>> 16);
                buf[off + 3] = (byte) (value >>> 24);
                buf[off + 4] = (byte) (value >>> 32);
                buf[off + 5] = (byte) (value >>> 40);
                buf[off + 6] = (byte) (value >>> 48);
                buf[off + 7] = (byte) (value >>> 56);
            }
            default -> throw new IllegalArgumentException("unsupported typeBits: " + typeBits);
        }
    }

    private static long readWordFromSeg(MemorySegment seg, long off, int typeBits) {
        return switch (typeBits) {
            case 8  -> Byte.toUnsignedLong(seg.get(ValueLayout.JAVA_BYTE, off));
            case 16 -> Short.toUnsignedLong(
                seg.get(ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), off));
            case 32 -> Integer.toUnsignedLong(
                seg.get(ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), off));
            case 64 -> seg.get(
                ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), off);
            default -> throw new IllegalArgumentException("unsupported typeBits: " + typeBits);
        };
    }

    private static void writeWordToSeg(MemorySegment seg, long off, long value, int typeBits) {
        switch (typeBits) {
            case 8  -> seg.set(ValueLayout.JAVA_BYTE, off, (byte) value);
            case 16 -> seg.set(ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), off, (short) value);
            case 32 -> seg.set(ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), off, (int) value);
            case 64 -> seg.set(ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), off, value);
            default -> throw new IllegalArgumentException("unsupported typeBits: " + typeBits);
        }
    }

    // ── Type conversion ───────────────────────────────────────────────────────

    private static MemorySegment fromLongs(long[] longs, PType ptype) {
        int    n     = longs.length;
        int    eSize = ptype.byteSize();
        byte[] bytes = new byte[n * eSize];
        // Switch once outside loop: JIT sees a tight, type-specific loop it can vectorize.
        switch (ptype) {
            case I8, U8 -> {
                for (int i = 0; i < n; i++) { bytes[i] = (byte) longs[i]; }
            }
            case I16, U16 -> {
                ByteBuffer bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
                for (long v : longs) { bb.putShort((short) v); }
            }
            case I32, U32 -> {
                ByteBuffer bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
                for (long v : longs) { bb.putInt((int) v); }
            }
            case I64, U64 -> {
                // Raw byte copy: correct on LE JVMs (x86); JVM stores long[] in native order.
                MemorySegment.copy(MemorySegment.ofArray(longs), 0L,
                    MemorySegment.ofArray(bytes), 0L, (long) n * 8);
            }
            default -> throw new UnsupportedOperationException("unsupported ptype: " + ptype);
        }
        return MemorySegment.ofArray(bytes);
    }

    private static long[] toLongs(Object data, PType ptype) {
        return switch (ptype) {
            case I8 -> {
                byte[] arr = (byte[]) data;
                long[] r = new long[arr.length];
                for (int i = 0; i < arr.length; i++) { r[i] = arr[i]; }
                yield r;
            }
            case U8 -> {
                byte[] arr = (byte[]) data;
                long[] r = new long[arr.length];
                for (int i = 0; i < arr.length; i++) { r[i] = Byte.toUnsignedLong(arr[i]); }
                yield r;
            }
            case I16 -> {
                short[] arr = (short[]) data;
                long[] r = new long[arr.length];
                for (int i = 0; i < arr.length; i++) { r[i] = arr[i]; }
                yield r;
            }
            case U16 -> {
                short[] arr = (short[]) data;
                long[] r = new long[arr.length];
                for (int i = 0; i < arr.length; i++) { r[i] = Short.toUnsignedLong(arr[i]); }
                yield r;
            }
            case I32 -> {
                int[] arr = (int[]) data;
                long[] r = new long[arr.length];
                for (int i = 0; i < arr.length; i++) { r[i] = arr[i]; }
                yield r;
            }
            case U32 -> {
                int[] arr = (int[]) data;
                long[] r = new long[arr.length];
                for (int i = 0; i < arr.length; i++) { r[i] = Integer.toUnsignedLong(arr[i]); }
                yield r;
            }
            case I64, U64 -> (long[]) data;
            default -> throw new UnsupportedOperationException("unsupported ptype: " + ptype);
        };
    }

    // ── Misc helpers ──────────────────────────────────────────────────────────

    private static long typeMask(int typeBits) {
        return typeBits == 64 ? -1L : (1L << typeBits) - 1L;
    }

    private static byte[] statsBytes(PType ptype, long value) {
        if (isUnsigned(ptype)) {
            return ScalarProtos.ScalarValue.newBuilder().setUint64Value(value).build().toByteArray();
        }
        return ScalarProtos.ScalarValue.newBuilder().setInt64Value(value).build().toByteArray();
    }

    private static boolean isUnsigned(PType ptype) {
        return switch (ptype) {
            case U8, U16, U32, U64 -> true;
            default -> false;
        };
    }
}
