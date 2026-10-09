package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.compute.FastLanes;
import io.github.dfa1.vortex.core.compute.PrimitiveArrays;
import io.github.dfa1.vortex.core.io.VortexFormat;
import io.github.dfa1.vortex.core.io.PTypeIO;
import io.github.dfa1.vortex.core.proto.ProtoBitPackedMetadata;
import io.github.dfa1.vortex.core.proto.ProtoPatchesMetadata;
import io.github.dfa1.vortex.core.proto.ProtoScalarValue;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.List;

/// Write-only encoder for `fastlanes.bitpacked`.
public final class BitpackedEncodingEncoder implements EncodingEncoder {
    private static final int[] FL_ORDER = {0, 4, 2, 6, 1, 5, 3, 7};

    @Override
    public EncodingId encodingId() {
        return EncodingId.FASTLANES_BITPACKED;
    }

    /// Rust's `BitPackingScheme` verdict (`vortex-btrblocks` `schemes/integer/bitpacking.rs`):
    /// skip negative arrays, which bit-pack only at full width, else defer to the sample.
    @Override
    public Estimate expectedRatio(DType dtype, ArrayAndStats data, EncodeContext ctx) {
        PType ptype = ((DType.Primitive) dtype).ptype();
        return data.stats().minIsNegative(ptype) ? Estimate.SKIP : Estimate.COMPLETE;
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

        if (n > 0) {
            // Min/max and the histogram in one pass: the loop is memory-bound, so reading the
            // values once beats a separately vectorized min/max pass (measured, #475). Unsigned
            // order is signed order with the sign bit flipped, so one branch-free body serves both
            // signednesses. A width of 0 needs no branch either: numberOfLeadingZeros(0) is 64.
            long flip = unsign ? Long.MIN_VALUE : 0L;
            long min = Long.MAX_VALUE;
            long max = Long.MIN_VALUE;
            for (long v : longs) {
                min = Math.min(min, v ^ flip);
                max = Math.max(max, v ^ flip);
                bitWidthFreq[Long.SIZE - Long.numberOfLeadingZeros(v & typeMask)]++;
            }
            signedMin = min ^ flip;
            signedMax = max ^ flip;
        }
        boolean hasNegative = !unsign && signedMin < 0;

        // Match Rust's bitpack_encode: refuse signed arrays with negatives. The cascade is
        // expected to FoR-shift them to non-negative first. Fall back to full type width here
        // so callers without FoR still produce a valid (un-compressed) result.
        if (hasNegative) {
            MemorySegment widePacked = packFastLanes(longs, n, typeBits, typeBits, ctx.arena());
            byte[] wideMeta = new ProtoBitPackedMetadata(typeBits, 0, null).encode();
            byte[] wideMin = statsBytes(ptype, signedMin);
            byte[] wideMax = statsBytes(ptype, signedMax);
            EncodeNode wideRoot = new EncodeNode(EncodingId.FASTLANES_BITPACKED,
                    MemorySegment.ofArray(wideMeta), new EncodeNode[0], new int[]{0});
            return new EncodeResult(wideRoot, List.of(EncodedBuffer.of(widePacked, ptype)), wideMin, wideMax);
        }

        int bitWidth = bestBitWidth(bitWidthFreq, ptype.byteSize() + 4, n);

        MemorySegment packed = packFastLanes(longs, n, bitWidth, typeBits, ctx.arena());

        byte[] statsMin = n > 0 ? statsBytes(ptype, signedMin) : null;
        byte[] statsMax = n > 0 ? statsBytes(ptype, signedMax) : null;

        if (bitWidth >= typeBits) {
            // Packed width covers full type range — no overflow possible, no patches needed.
            byte[] metaBytes = new ProtoBitPackedMetadata(bitWidth, 0, null).encode();
            EncodeNode root = new EncodeNode(EncodingId.FASTLANES_BITPACKED, MemorySegment.ofArray(metaBytes),
                    new EncodeNode[0], new int[]{0});
            return new EncodeResult(root, List.of(EncodedBuffer.of(packed, ptype)), statsMin, statsMax);
        }

        // The histogram already counts the values wider than bitWidth: the patches.
        int numPatches = 0;
        for (int width = bitWidth + 1; width < bitWidthFreq.length; width++) {
            numPatches += bitWidthFreq[width];
        }

        if (numPatches == 0) {
            byte[] metaBytes = new ProtoBitPackedMetadata(bitWidth, 0, null).encode();
            EncodeNode root = new EncodeNode(EncodingId.FASTLANES_BITPACKED, MemorySegment.ofArray(metaBytes),
                    new EncodeNode[0], new int[]{0});
            return new EncodeResult(root, List.of(EncodedBuffer.of(packed, ptype)), statsMin, statsMax);
        }

        int[] patchIdx = new int[numPatches];
        long[] patchVal = new long[numPatches];
        long packCap = 1L << bitWidth;
        for (int i = 0, p = 0; p < numPatches; i++) {
            if (Long.compareUnsigned(longs[i] & typeMask, packCap) >= 0) {
                patchIdx[p] = i;
                patchVal[p] = longs[i];
                p++;
            }
        }
        PType idxPtype = PType.narrowestUnsigned(n);
        MemorySegment idxBuf = buildPatchIdxBuf(patchIdx, idxPtype, ctx.arena());
        MemorySegment valBuf = buildPatchValBuf(patchVal, ptype, ctx.arena());

        ProtoPatchesMetadata patches = new ProtoPatchesMetadata(
                numPatches, 0L,
                io.github.dfa1.vortex.core.proto.ProtoPType.fromValue(idxPtype.ordinal()),
                null, null, null);
        byte[] metaBytes = new ProtoBitPackedMetadata(bitWidth, 0, patches).encode();

        EncodeNode idxNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, 1);
        EncodeNode valNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, 2);
        EncodeNode root = new EncodeNode(EncodingId.FASTLANES_BITPACKED, MemorySegment.ofArray(metaBytes),
                new EncodeNode[]{idxNode, valNode}, new int[]{0});
        return new EncodeResult(root, List.of(EncodedBuffer.of(packed, ptype), EncodedBuffer.of(idxBuf, idxPtype),
                EncodedBuffer.of(valBuf, ptype)), statsMin, statsMax);
    }

    /// Picks the bit-width that minimizes `packed_bytes + exceptions_bytes`.
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

    private static MemorySegment buildPatchIdxBuf(int[] idx, PType idxPtype, Arena arena) {
        int numPatches = idx.length;
        int elemBytes = idxPtype.byteSize();
        MemorySegment seg = arena.allocate(Math.max(1L, (long) numPatches * elemBytes), elemBytes);
        for (int i = 0; i < numPatches; i++) {
            PTypeIO.set(seg, (long) i * elemBytes, idxPtype, idx[i]);
        }
        return seg;
    }

    private static MemorySegment buildPatchValBuf(long[] val, PType ptype, Arena arena) {
        int numPatches = val.length;
        int elemBytes = ptype.byteSize();
        MemorySegment seg = arena.allocate(Math.max(1L, (long) numPatches * elemBytes), elemBytes);
        for (int i = 0; i < numPatches; i++) {
            PTypeIO.set(seg, (long) i * elemBytes, ptype, val[i]);
        }
        return seg;
    }

    private static MemorySegment packFastLanes(long[] values, int n, int bitWidth, int typeBits, Arena arena) {
        if (bitWidth == 0 || n == 0) {
            return MemorySegment.ofArray(new byte[0]);
        }
        int lanes = 1024 / typeBits;
        int blockCount = (n + 1023) / 1024;
        long typeMask = FastLanes.lowMask(typeBits);
        // Mask values to the chosen bit width so over-cap entries (handled separately as
        // patches) don't spill into the next row's region in the packed layout.
        long widthMask = bitWidth >= 64 ? -1L : (1L << bitWidth) - 1L;
        int wordsPerBlock = bitWidth * lanes;
        MemorySegment seg = arena.allocate((long) blockCount * 128 * bitWidth);
        // One block's packed words, built in registers and stored once each: the segment is never
        // read back, and the per-width store switch runs once per block, not once per word.
        long[] words = new long[wordsPerBlock];
        int[] rowOffset = new int[typeBits];
        for (int row = 0; row < typeBits; row++) {
            rowOffset[row] = FL_ORDER[row / 8] * 16 + (row % 8) * 128;
        }

        for (int block = 0; block < blockCount; block++) {
            int blockStart = block * 1024;
            boolean fullBlock = blockStart + 1024 <= n;
            for (int lane = 0; lane < lanes; lane++) {
                // FastLanes packs each lane's typeBits rows bit-contiguously into bitWidth words:
                // a word is complete once its bits are filled, and a row straddling the word
                // boundary carries its high bits into the next word.
                long acc = 0L;
                int shift = 0;
                int word = 0;
                for (int row = 0; row < typeBits; row++) {
                    int idx = blockStart + rowOffset[row] + lane;
                    long value = (fullBlock || idx < n) ? values[idx] & widthMask : 0L;
                    acc |= value << shift;
                    int filled = shift + bitWidth;
                    if (filled >= typeBits) {
                        words[word * lanes + lane] = acc & typeMask;
                        word++;
                        int carry = filled - typeBits;
                        acc = carry > 0 ? value >>> (bitWidth - carry) : 0L;
                        shift = carry;
                    } else {
                        shift = filled;
                    }
                }
            }
            storeWords(seg, (long) block * 128 * bitWidth, words, typeBits);
        }
        return seg;
    }

    // Stores one block's words at their type width, little-endian.
    private static void storeWords(MemorySegment seg, long off, long[] words, int typeBits) {
        switch (typeBits) {
            case 8 -> {
                for (int i = 0; i < words.length; i++) {
                    seg.set(ValueLayout.JAVA_BYTE, off + i, (byte) words[i]);
                }
            }
            case 16 -> {
                for (int i = 0; i < words.length; i++) {
                    seg.set(VortexFormat.LE_SHORT, off + 2L * i, (short) words[i]);
                }
            }
            case 32 -> {
                for (int i = 0; i < words.length; i++) {
                    seg.set(VortexFormat.LE_INT, off + 4L * i, (int) words[i]);
                }
            }
            case 64 -> MemorySegment.copy(words, 0, seg, VortexFormat.LE_LONG, off, words.length);
            default ->
                    throw new VortexException(EncodingId.FASTLANES_BITPACKED, "unsupported typeBits: " + typeBits);
        }
    }

    private static byte[] statsBytes(PType ptype, long value) {
        if (ptype.isUnsigned()) {
            return ProtoScalarValue.ofUint64Value(value).encode();
        }
        return ProtoScalarValue.ofInt64Value(value).encode();
    }
}
