package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.encoding.EncodingId;
import io.github.dfa1.vortex.encoding.FastLanes;
import io.github.dfa1.vortex.encoding.PrimitiveArrays;
import io.github.dfa1.vortex.encoding.PTypeIO;
import io.github.dfa1.vortex.proto.BitPackedMetadata;
import io.github.dfa1.vortex.proto.PatchesMetadata;
import io.github.dfa1.vortex.proto.ScalarValue;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/// Write-only encoder for `fastlanes.bitpacked`.
public final class BitpackedEncodingEncoder implements EncodingEncoder {
    private static final int[] FL_ORDER = {0, 4, 2, 6, 1, 5, 3, 7};

    /// Public no-arg constructor required by [java.util.ServiceLoader].
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
        long[] longs = PrimitiveArrays.toLongs(data, ptype, EncodingId.FASTLANES_BITPACKED);
        int n = longs.length;
        int typeBits = ptype.bits();
        long typeMask = FastLanes.lowMask(typeBits);
        boolean unsign = ptype.isUnsigned();

        long signedMin = 0L;
        long signedMax = 0L;
        int[] bitWidthFreq = new int[typeBits + 1];
        boolean hasNegative = false;

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
                if (!unsign && v < 0) {
                    hasNegative = true;
                }
                long uv = v & typeMask;
                int width = uv == 0L ? 0 : Long.SIZE - Long.numberOfLeadingZeros(uv);
                bitWidthFreq[width]++;
            }
        }

        // Match Rust's bitpack_encode: refuse signed arrays with negatives. The cascade is
        // expected to FoR-shift them to non-negative first. Fall back to full type width here
        // so callers without FoR still produce a valid (un-compressed) result.
        if (hasNegative) {
            int wideBitWidth = typeBits;
            MemorySegment widePacked = packFastLanes(longs, n, wideBitWidth, typeBits, ctx.arena());
            byte[] wideMeta = new BitPackedMetadata(wideBitWidth, 0, null).encode();
            byte[] wideMin = statsBytes(ptype, signedMin);
            byte[] wideMax = statsBytes(ptype, signedMax);
            EncodeNode wideRoot = new EncodeNode(EncodingId.FASTLANES_BITPACKED,
                    ByteBuffer.wrap(wideMeta), new EncodeNode[0], new int[]{0});
            return new EncodeResult(wideRoot, List.of(widePacked), wideMin, wideMax);
        }

        int bitWidth = bestBitWidth(bitWidthFreq, ptype.byteSize() + 4, n);

        MemorySegment packed = packFastLanes(longs, n, bitWidth, typeBits, ctx.arena());

        byte[] statsMin = n > 0 ? statsBytes(ptype, signedMin) : null;
        byte[] statsMax = n > 0 ? statsBytes(ptype, signedMax) : null;

        if (bitWidth >= typeBits) {
            // Packed width covers full type range — no overflow possible, no patches needed.
            byte[] metaBytes = new BitPackedMetadata(bitWidth, 0, null).encode();
            EncodeNode root = new EncodeNode(EncodingId.FASTLANES_BITPACKED, ByteBuffer.wrap(metaBytes),
                    new EncodeNode[0], new int[]{0});
            return new EncodeResult(root, List.of(packed), statsMin, statsMax);
        }

        long packCap = 1L << bitWidth;
        var patchIdx = new ArrayList<Integer>();
        var patchVal = new ArrayList<Long>();
        for (int i = 0; i < n; i++) {
            long uv = longs[i] & typeMask;
            if (Long.compareUnsigned(uv, packCap) >= 0) {
                patchIdx.add(i);
                patchVal.add(longs[i]);
            }
        }

        if (patchIdx.isEmpty()) {
            byte[] metaBytes = new BitPackedMetadata(bitWidth, 0, null).encode();
            EncodeNode root = new EncodeNode(EncodingId.FASTLANES_BITPACKED, ByteBuffer.wrap(metaBytes),
                    new EncodeNode[0], new int[]{0});
            return new EncodeResult(root, List.of(packed), statsMin, statsMax);
        }

        int numPatches = patchIdx.size();
        PType idxPtype = chooseIdxPtype(n);
        MemorySegment idxBuf = buildPatchIdxBuf(patchIdx, idxPtype, ctx.arena());
        MemorySegment valBuf = buildPatchValBuf(patchVal, ptype, ctx.arena());

        PatchesMetadata patches = new PatchesMetadata(
                numPatches, 0L,
                io.github.dfa1.vortex.proto.PType.fromValue(idxPtype.ordinal()),
                null, null, null);
        byte[] metaBytes = new BitPackedMetadata(bitWidth, 0, patches).encode();

        EncodeNode idxNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, 1);
        EncodeNode valNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, 2);
        EncodeNode root = new EncodeNode(EncodingId.FASTLANES_BITPACKED, ByteBuffer.wrap(metaBytes),
                new EncodeNode[]{idxNode, valNode}, new int[]{0});
        return new EncodeResult(root, List.of(packed, idxBuf, valBuf), statsMin, statsMax);
    }

    /// Picks the bit-width that minimises `packed_bytes + exceptions_bytes`.
    /// Mirrors `vortex-fastlanes::bitpack_compress::best_bit_width`.
    private static int bestBitWidth(int[] bitWidthFreq, int bytesPerException, int n) {
        if (n == 0) {
            return 0;
        }
        long bestCost = (long) n * bytesPerException;
        int bestWidth = 0;
        long numPacked = 0;
        for (int width = 0; width < bitWidthFreq.length; width++) {
            long packedCost = ((long) width * n + 7L) / 8L;
            numPacked += bitWidthFreq[width];
            long exceptionsCost = (n - numPacked) * bytesPerException;
            long cost = packedCost + exceptionsCost;
            if (cost < bestCost) {
                bestCost = cost;
                bestWidth = width;
            }
        }
        return bestWidth;
    }

    private static PType chooseIdxPtype(int n) {
        if (n <= 0xFF) {
            return PType.U8;
        }
        if (n <= 0xFFFF) {
            return PType.U16;
        }
        return PType.U32;
    }

    private static MemorySegment buildPatchIdxBuf(List<Integer> idx, PType idxPtype, Arena arena) {
        int numPatches = idx.size();
        int elemBytes = idxPtype.byteSize();
        MemorySegment seg = arena.allocate(Math.max(1L, (long) numPatches * elemBytes), elemBytes);
        for (int i = 0; i < numPatches; i++) {
            PTypeIO.set(seg, (long) i * elemBytes, idxPtype, idx.get(i));
        }
        return seg;
    }

    private static MemorySegment buildPatchValBuf(List<Long> val, PType ptype, Arena arena) {
        int numPatches = val.size();
        int elemBytes = ptype.byteSize();
        MemorySegment seg = arena.allocate(Math.max(1L, (long) numPatches * elemBytes), elemBytes);
        for (int i = 0; i < numPatches; i++) {
            PTypeIO.set(seg, (long) i * elemBytes, ptype, val.get(i));
        }
        return seg;
    }

    private static MemorySegment packFastLanes(long[] values, int n, int bitWidth, int typeBits, Arena arena) {
        if (bitWidth == 0 || n == 0) {
            return MemorySegment.ofArray(new byte[0]);
        }
        int lanes = 1024 / typeBits;
        int wordBytes = typeBits / 8;
        int blockCount = (n + 1023) / 1024;
        long typeMask = FastLanes.lowMask(typeBits);
        // Mask values to the chosen bit width so over-cap entries (handled separately as
        // patches) don't spill into the next row's region in the packed layout.
        long widthMask = bitWidth >= 64 ? -1L : (1L << bitWidth) - 1L;
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
                    long value = (logicalIdx < n) ? (values[logicalIdx] & widthMask) : 0L;

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



    private static byte[] statsBytes(PType ptype, long value) {
        if (ptype.isUnsigned()) {
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
