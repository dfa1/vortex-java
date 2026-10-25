package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.compute.FastLanes;
import io.github.dfa1.vortex.core.compute.PrimitiveArrays;
import io.github.dfa1.vortex.core.io.VortexFormat;
import io.github.dfa1.vortex.core.io.PTypeIO;
import io.github.dfa1.vortex.core.simd.SimdOperations;
import io.github.dfa1.vortex.core.simd.SimdOperationsSupport;
import io.github.dfa1.vortex.core.proto.ProtoBitPackedMetadata;
import io.github.dfa1.vortex.core.proto.ProtoPatchesMetadata;
import io.github.dfa1.vortex.core.proto.ProtoScalarValue;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Arrays;
import java.util.List;

/// Write-only encoder for `fastlanes.bitpacked`.
public final class BitpackedEncodingEncoder implements EncodingEncoder {

    /// Creates the encoder.
    public BitpackedEncodingEncoder() {
    }

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
        return p.ptype().isInteger();
    }

    /// A nullable array keeps its validity as a trailing `vortex.bool` child, after any patches,
    /// as Rust's `BitPackedArray` does.
    @Override
    public boolean acceptsNullable(DType dtype) {
        return accepts(dtype);
    }

    @Override
    public EncodeResult encode(DType dtype, Object data, EncodeContext ctx) {
        if (data instanceof NullableData nullable) {
            return encodeNullable(dtype, nullable, ctx);
        }
        PType ptype = ((DType.Primitive) dtype).ptype();
        long[] longs = PrimitiveArrays.toLongs(data, ptype, EncodingId.FASTLANES_BITPACKED);
        return encodeLongs(ptype, longs, ctx);
    }

    /// Rust's `BitPackingScheme::compress` run directly, as FoR runs it on its residuals: packed
    /// at one global width, or kept a `vortex.primitive` when the best width is the native one
    /// (`compress_global` returns `None`).
    ///
    /// @param dtype the non-negative integer dtype
    /// @param data  the values, dense or a [NullableData]
    /// @param ctx   the encode context
    /// @return the bit-packed or canonical array
    static EncodeResult encodeLeaf(DType dtype, Object data, EncodeContext ctx) {
        PType ptype = ((DType.Primitive) dtype).ptype();
        boolean[] validity = null;
        Object values = data;
        if (data instanceof NullableData nullable) {
            validity = nullable.validity();
            values = nullable.values();
        }
        long[] longs = PrimitiveArrays.toLongs(values, ptype, EncodingId.FASTLANES_BITPACKED);
        long typeMask = FastLanes.lowMask(ptype.bits());
        int[] bitWidthFreq = new int[ptype.bits() + 1];
        for (int i = 0; i < longs.length; i++) {
            int width = validity == null || validity[i] ? Long.SIZE - Long.numberOfLeadingZeros(longs[i] & typeMask) : 0;
            bitWidthFreq[width]++;
        }
        if (bestBitWidth(bitWidthFreq, ptype.byteSize() + 4, longs.length) == ptype.bits()) {
            return new PrimitiveEncodingEncoder().encode(dtype, data, ctx);
        }
        return new BitpackedEncodingEncoder().encode(dtype, data, ctx);
    }

    /// Rust's `bitpack_encode_unchecked`: every value packed at `bitWidth`, without patches or
    /// stats, and a nullable array's validity as the last child. The caller guarantees the values
    /// fit, as ALP-RD's dictionary codes and right parts do by construction.
    ///
    /// @param dtype    the unsigned integer dtype of the values
    /// @param data     the values, dense or a [NullableData]
    /// @param bitWidth the width every value is packed at
    /// @param ctx      the encode context
    /// @return the bit-packed array
    static EncodeResult encodeAtWidth(DType dtype, Object data, int bitWidth, EncodeContext ctx) {
        PType ptype = ((DType.Primitive) dtype).ptype();
        boolean[] validity = null;
        Object values = data;
        if (data instanceof NullableData nullable) {
            validity = nullable.validity();
            values = nullable.values();
        }
        long[] longs = PrimitiveArrays.toLongs(values, ptype, EncodingId.FASTLANES_BITPACKED);
        MemorySegment packed = packFastLanes(longs, longs.length, bitWidth, ptype.bits(), ctx.arena());
        byte[] metaBytes = new ProtoBitPackedMetadata(bitWidth, 0, null).encode();
        EncodeNode root = new EncodeNode(EncodingId.FASTLANES_BITPACKED, MemorySegment.ofArray(metaBytes),
                new EncodeNode[0], new int[]{0});
        EncodeResult result = new EncodeResult(root, List.of(EncodedBuffer.of(packed, ptype)), null, null);
        return validity == null ? result : result.withValidity(validity, ctx);
    }

    /// Rust's `bitpack_encode` on a nullable array: the bit-width histogram counts a null at width
    /// 0 and only valid values become patches (`bit_width_histogram`, `gather_patches`). A null slot
    /// is zeroed first, which gives both, and packs as Rust packs the zero an Arrow null holds.
    private static EncodeResult encodeNullable(DType dtype, NullableData data, EncodeContext ctx) {
        PType ptype = ((DType.Primitive) dtype).ptype();
        long[] longs = PrimitiveArrays.toLongs(data.values(), ptype, EncodingId.FASTLANES_BITPACKED).clone();
        boolean[] validity = data.validity();
        for (int i = 0; i < longs.length; i++) {
            if (!validity[i]) {
                longs[i] = 0;
            }
        }
        MinMax stats = ZoneMapStats.of(dtype.withNullable(false), data);
        EncodeResult packed = encodeLongs(ptype, longs, ctx);
        return new EncodeResult(packed.rootNode(), packed.encodedBuffers(), PrimitiveEncodingEncoder.minOf(stats),
                PrimitiveEncodingEncoder.maxOf(stats)).withValidity(validity, ctx);
    }

    private static EncodeResult encodeLongs(PType ptype, long[] longs, EncodeContext ctx) {
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
            ProtoScalarValue wideMin = statsBytes(ptype, signedMin);
            ProtoScalarValue wideMax = statsBytes(ptype, signedMax);
            EncodeNode wideRoot = new EncodeNode(EncodingId.FASTLANES_BITPACKED,
                    MemorySegment.ofArray(wideMeta), new EncodeNode[0], new int[]{0});
            return new EncodeResult(wideRoot, List.of(EncodedBuffer.of(widePacked, ptype)), wideMin, wideMax);
        }

        int bitWidth = bestBitWidth(bitWidthFreq, ptype.byteSize() + 4, n);

        MemorySegment packed = packFastLanes(longs, n, bitWidth, typeBits, ctx.arena());

        ProtoScalarValue statsMin = n > 0 ? statsBytes(ptype, signedMin) : null;
        ProtoScalarValue statsMax = n > 0 ? statsBytes(ptype, signedMax) : null;

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
        int wordsPerBlock = bitWidth * lanes;
        MemorySegment seg = arena.allocate((long) blockCount * 128 * bitWidth);
        // One block's packed words, stored once each: the segment is never read back, and the
        // per-width store switch runs once per block, not once per word.
        long[] words = new long[wordsPerBlock];
        // The kernel packs full blocks only; a partial tail block is zero-padded into scratch.
        long[] tail = null;
        SimdOperations ops = SimdOperationsSupport.preferred();

        for (int block = 0; block < blockCount; block++) {
            int blockStart = block * 1024;
            if (blockStart + 1024 <= n) {
                ops.packBlock(values, blockStart, bitWidth, typeBits, words);
            } else {
                if (tail == null) {
                    tail = new long[1024];
                }
                int remaining = n - blockStart;
                System.arraycopy(values, blockStart, tail, 0, remaining);
                Arrays.fill(tail, remaining, 1024, 0L);
                ops.packBlock(tail, 0, bitWidth, typeBits, words);
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

    private static ProtoScalarValue statsBytes(PType ptype, long value) {
        if (ptype.isUnsigned()) {
            return ProtoScalarValue.ofUint64Value(value);
        }
        return ProtoScalarValue.ofInt64Value(value);
    }
}
