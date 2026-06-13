package io.github.dfa1.vortex.writer.encode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/// Bin optimization DP — port of {@code pco/src/bin_optimization.rs}.
///
/// <p>Input: equal-count histogram bins (sorted by value). Output: merged bins that
/// minimize total bit cost = Σ bin_meta_cost + (ans_cost + offset_cost) × count.
final class PcoBinOptimizer {

    private PcoBinOptimizer() {
    }

    private static final float SINGLE_BIN_SPEEDUP = 0.1f;
    private static final float TRIVIAL_OFFSET_SPEEDUP = 0.1f;

    /// One optimized ANS bin.
    ///
    /// @param lowerSortKey lower bound in sort-key space (latent XOR Long.MIN_VALUE)
    /// @param upperSortKey upper bound in sort-key space
    /// @param weight       raw element count (quantized to ANS weight separately)
    record Bin(long lowerSortKey, long upperSortKey, int weight) {

        int offsetBits() {
            long range = upperSortKey - lowerSortKey;
            return range == 0 ? 0 : 64 - Long.numberOfLeadingZeros(range);
        }

        long lowerLatent() {
            return lowerSortKey ^ Long.MIN_VALUE;
        }
    }

    static List<Bin> optimize(List<PcoHistBin> histBins, int ansSizeLog, int dtypeSize) {
        if (histBins.isEmpty()) {
            return List.of();
        }
        int n = histBins.size();
        float binMetaCost = ansSizeLog + dtypeSize + bitsToEncodeOffsetBits(dtypeSize);

        long total = 0;
        for (PcoHistBin b : histBins) {
            total += b.count();
        }
        float totalLog2 = log2Approx((float) total);

        long[] cCounts = new long[n + 1];
        long[] lowers = new long[n];
        long[] uppers = new long[n];
        for (int i = 0; i < n; i++) {
            cCounts[i + 1] = cCounts[i] + histBins.get(i).count();
            lowers[i] = histBins.get(i).lowerSortKey();
            uppers[i] = histBins.get(i).upperSortKey();
        }

        float[] bestCosts = new float[n + 1];
        int[] bestJs = new int[n];

        for (int i = 0; i < n; i++) {
            float best = Float.MAX_VALUE;
            int bestJ = 0;
            for (int j = i; j >= 0; j--) {
                long count = cCounts[i + 1] - cCounts[j];
                float cost = bestCosts[j] + binCost(binMetaCost, lowers[j], uppers[i], count, totalLog2);
                if (cost < best) {
                    best = cost;
                    bestJ = j;
                }
            }
            bestCosts[i + 1] = best;
            bestJs[i] = bestJ;
        }

        float singleCost = binCost(binMetaCost, lowers[0], uppers[n - 1], total, totalLog2);
        if (singleCost < bestCosts[n] + SINGLE_BIN_SPEEDUP * total) {
            return List.of(new Bin(lowers[0], uppers[n - 1], (int) total));
        }

        boolean allTrivial = true;
        for (PcoHistBin b : histBins) {
            if (b.lowerSortKey() != b.upperSortKey()) {
                allTrivial = false;
                break;
            }
        }
        if (allTrivial) {
            float trivialCost = 0f;
            for (PcoHistBin b : histBins) {
                trivialCost += binCost(binMetaCost, b.lowerSortKey(), b.upperSortKey(), b.count(), totalLog2);
            }
            if (trivialCost < bestCosts[n] + TRIVIAL_OFFSET_SPEEDUP * total) {
                List<Bin> trivial = new ArrayList<>(n);
                for (PcoHistBin b : histBins) {
                    trivial.add(new Bin(b.lowerSortKey(), b.upperSortKey(), (int) b.count()));
                }
                return trivial;
            }
        }

        List<int[]> partitions = new ArrayList<>();
        int idx = n - 1;
        while (true) {
            int j = bestJs[idx];
            partitions.add(new int[]{j, idx});
            if (j == 0) {
                break;
            }
            idx = j - 1;
        }
        Collections.reverse(partitions);

        List<Bin> result = new ArrayList<>(partitions.size());
        for (int[] part : partitions) {
            long count = cCounts[part[1] + 1] - cCounts[part[0]];
            result.add(new Bin(lowers[part[0]], uppers[part[1]], (int) count));
        }
        return result;
    }

    private static float binCost(float metaCost, long lower, long upper, long count, float totalLog2) {
        float ansLoss = totalLog2 - log2Approx((float) count);
        long range = upper - lower;
        float offsetCost = range == 0 ? 0f : 64 - Long.numberOfLeadingZeros(range);
        return metaCost + (ansLoss + offsetCost) * count;
    }

    // Port of Rust's log2_approx (pco/src/bin_optimization.rs).
    static float log2Approx(float x) {
        final float z = 0.674f;
        final int signifMask = 0x7FFFFF;
        final int zSignif = Float.floatToRawIntBits(z) & signifMask;
        final float b = 2.0f / z;
        final float c = -b / (6.0f * z);
        final float a = -b - c;

        int rawBits = Float.floatToRawIntBits(x);
        int exp = rawBits >>> 23;
        int signif = rawBits & signifMask;

        int highBit = signif > zSignif ? 1 : 0;
        int logInt = exp + highBit - 127;
        float normalized = Float.intBitsToFloat(((0x7F ^ highBit) << 23) | signif);
        return logInt + a + normalized * (b + c * normalized);
    }

    private static int bitsToEncodeOffsetBits(int dtypeSize) {
        return switch (dtypeSize) {
            case 64 -> 7;
            case 32 -> 6;
            case 16 -> 5;
            default -> throw new IllegalArgumentException("invalid dtypeSize: " + dtypeSize);
        };
    }
}
