package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.encoding.PTypeIO;

import java.lang.foreign.MemorySegment;
import java.util.function.DoubleBinaryOperator;
import java.util.function.DoubleConsumer;

/// Lazy [DoubleArray] for the {@code ALP(FoR(Bitpacked))} chain.
///
/// Holds the raw bitpacked buffer and the chain parameters
/// ({@code bitWidth}, {@code offset}, {@code ref}, {@code scale}) instead of
/// allocating the intermediate FoR / ALP buffers. {@link #sumWhereGt(double)}
/// unpacks each bitpacked value, applies {@code +ref} and the threshold
/// compare inline, and decodes ({@code (double)val * scale}) only the
/// matching rows.
///
/// For the random-access {@link #getDouble(long)}, full-fold {@link #fold},
/// and {@link #forEachDouble} paths the buffer is materialised lazily on
/// first call and cached — these paths cost the same as the unfused
/// AlpDoubleArray once the cache is warm.
public final class FusedAlpForBitpackedDoubleArray implements DoubleArray {

    private static final int[] FL_ORDER = {0, 4, 2, 6, 1, 5, 3, 7};

    private final DType dtype;
    private final long length;
    private final MemorySegment packed;
    private final int bitWidth;
    private final int offset;
    private final long ref;
    private final double scale;

    private MemorySegment materialised;

    /// Constructs a fused chain array.
    ///
    /// @param dtype    F64 primitive dtype
    /// @param length   number of logical rows
    /// @param packed   raw bitpacked buffer
    /// @param bitWidth bit-width per encoded element
    /// @param offset   bitpacked offset (rows skipped at start of first block)
    /// @param ref      FoR reference value to add to each unpacked element
    /// @param scale    ALP scale ({@code 10^f * 10^-e})
    public FusedAlpForBitpackedDoubleArray(DType dtype, long length, MemorySegment packed,
            int bitWidth, int offset, long ref, double scale) {
        this.dtype = dtype;
        this.length = length;
        this.packed = packed;
        this.bitWidth = bitWidth;
        this.offset = offset;
        this.ref = ref;
        this.scale = scale;
    }

    @Override
    public DType dtype() {
        return dtype;
    }

    @Override
    public long length() {
        return length;
    }

    @Override
    public double getDouble(long i) {
        return materialise().getAtIndex(PTypeIO.LE_DOUBLE, i);
    }

    @Override
    public void forEachDouble(DoubleConsumer c) {
        MemorySegment buf = materialise();
        long n = length;
        for (long i = 0; i < n; i++) {
            c.accept(buf.getAtIndex(PTypeIO.LE_DOUBLE, i));
        }
    }

    @Override
    public double fold(double identity, DoubleBinaryOperator op) {
        MemorySegment buf = materialise();
        long n = length;
        double result = identity;
        for (long i = 0; i < n; i++) {
            result = op.applyAsDouble(result, buf.getAtIndex(PTypeIO.LE_DOUBLE, i));
        }
        return result;
    }

    /// Sum of all values strictly greater than {@code threshold}, computed
    /// without materialising the intermediate bitpacked / FoR / ALP buffers.
    ///
    /// Encodes the threshold into the bitpacked integer domain
    /// ({@code encLo = floor(threshold / scale) - ref}) and inline-compares
    /// each unpacked value during the bitpacked walk. Decode runs only for
    /// matches.
    ///
    /// @param threshold strict lower bound on the matching values
    /// @return sum of decoded values where {@code value > threshold}
    public double sumWhereGt(double threshold) {
        // value > threshold
        //   ⇔ (bp_val + ref) * scale > threshold
        //   ⇔ bp_val > floor(threshold / scale) - ref
        long encLo = (long) Math.floor(threshold / scale) - ref;
        return unpackSumGt(packed, bitWidth, offset, length, ref, scale, encLo, threshold);
    }

    private MemorySegment materialise() {
        if (materialised != null) {
            return materialised;
        }
        MemorySegment buf = java.lang.foreign.Arena.ofAuto().allocate(length * 8, 8);
        unpackToDouble(packed, bitWidth, offset, length, ref, scale, buf);
        materialised = buf;
        return materialised;
    }

    private static void unpackToDouble(MemorySegment buf, int bitWidth, int offset, long rowCount,
            long ref, double scale, MemorySegment out) {
        final int lanes = 16;
        long totalElems = rowCount + offset;
        int blockCount = (int) ((totalElems + 1023) / 1024);
        long bitMask = bitWidth == 64 ? -1L : (1L << bitWidth) - 1L;

        int[] shifts = new int[64];
        int[] remainingBits = new int[64];
        int[] currentBits = new int[64];
        long[] loMasks = new long[64];
        long[] hiMasks = new long[64];
        long[] currWordByteBase = new long[64];
        long[] nextWordByteBase = new long[64];
        long[] outRowByteOff = new long[64];
        precomputeTables(bitWidth, lanes, shifts, remainingBits, currentBits, loMasks, hiMasks,
                currWordByteBase, nextWordByteBase, outRowByteOff);

        long blockByteOff = 0L;
        long blockByteStride = 128L * bitWidth;
        for (int block = 0; block < blockCount; block++, blockByteOff += blockByteStride) {
            int blockLogicStart = block * 1024 - offset;
            boolean fullBlock = blockLogicStart >= 0 && (long) blockLogicStart + 1023L < rowCount;
            for (int row = 0; row < 64; row++) {
                int shift = shifts[row];
                int rem = remainingBits[row];
                int curr = currentBits[row];
                long wordBase = blockByteOff + currWordByteBase[row];
                long hiBase = rem > 0 ? blockByteOff + nextWordByteBase[row] : 0L;
                long loMask = loMasks[row];
                long hiMask = hiMasks[row];
                int o = row / 8;
                int s = row % 8;
                int baseLogical = blockLogicStart + FL_ORDER[o] * 16 + s * 128;
                for (int lane = 0; lane < lanes; lane++) {
                    int logicalIdx = baseLogical + lane;
                    if (!fullBlock && (logicalIdx < 0 || logicalIdx >= rowCount)) {
                        continue;
                    }
                    long value;
                    long src = buf.get(PTypeIO.LE_LONG, wordBase + (long) lane * 8);
                    if (rem > 0) {
                        long lo = (src >>> shift) & loMask;
                        long hi = buf.get(PTypeIO.LE_LONG, hiBase + (long) lane * 8) & hiMask;
                        value = lo | (hi << curr);
                    } else {
                        value = (src >>> shift) & bitMask;
                    }
                    out.setAtIndex(PTypeIO.LE_DOUBLE, logicalIdx, (double) (value + ref) * scale);
                }
            }
        }
    }

    private static double unpackSumGt(MemorySegment buf, int bitWidth, int offset, long rowCount,
            long ref, double scale, long encLo, double threshold) {
        final int lanes = 16;
        long totalElems = rowCount + offset;
        int blockCount = (int) ((totalElems + 1023) / 1024);
        long bitMask = bitWidth == 64 ? -1L : (1L << bitWidth) - 1L;

        int[] shifts = new int[64];
        int[] remainingBits = new int[64];
        int[] currentBits = new int[64];
        long[] loMasks = new long[64];
        long[] hiMasks = new long[64];
        long[] currWordByteBase = new long[64];
        long[] nextWordByteBase = new long[64];
        long[] outRowByteOff = new long[64];
        precomputeTables(bitWidth, lanes, shifts, remainingBits, currentBits, loMasks, hiMasks,
                currWordByteBase, nextWordByteBase, outRowByteOff);

        double result = 0.0;
        long blockByteOff = 0L;
        long blockByteStride = 128L * bitWidth;
        for (int block = 0; block < blockCount; block++, blockByteOff += blockByteStride) {
            int blockLogicStart = block * 1024 - offset;
            boolean fullBlock = blockLogicStart >= 0 && (long) blockLogicStart + 1023L < rowCount;
            for (int row = 0; row < 64; row++) {
                int shift = shifts[row];
                int rem = remainingBits[row];
                int curr = currentBits[row];
                long wordBase = blockByteOff + currWordByteBase[row];
                long hiBase = rem > 0 ? blockByteOff + nextWordByteBase[row] : 0L;
                long loMask = loMasks[row];
                long hiMask = hiMasks[row];
                int o = row / 8;
                int s = row % 8;
                int baseLogical = blockLogicStart + FL_ORDER[o] * 16 + s * 128;
                for (int lane = 0; lane < lanes; lane++) {
                    int logicalIdx = baseLogical + lane;
                    if (!fullBlock && (logicalIdx < 0 || logicalIdx >= rowCount)) {
                        continue;
                    }
                    long value;
                    long src = buf.get(PTypeIO.LE_LONG, wordBase + (long) lane * 8);
                    if (rem > 0) {
                        long lo = (src >>> shift) & loMask;
                        long hi = buf.get(PTypeIO.LE_LONG, hiBase + (long) lane * 8) & hiMask;
                        value = lo | (hi << curr);
                    } else {
                        value = (src >>> shift) & bitMask;
                    }
                    if (value > encLo) {
                        double v = (double) (value + ref) * scale;
                        if (v > threshold) {
                            result += v;
                        }
                    }
                }
            }
        }
        return result;
    }

    private static void precomputeTables(int bitWidth, int lanes,
            int[] shifts, int[] remainingBits, int[] currentBits,
            long[] loMasks, long[] hiMasks,
            long[] currWordByteBase, long[] nextWordByteBase, long[] outRowByteOff) {
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
    }
}
