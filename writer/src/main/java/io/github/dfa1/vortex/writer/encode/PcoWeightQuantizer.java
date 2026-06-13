package io.github.dfa1.vortex.writer.encode;

/// ANS weight quantization — port of {@code pco/src/ans/encoding.rs} quantize_weights*.
///
/// Converts raw bin counts (proportional to frequency) into ANS weights summing
/// to exactly {@code 2^sizeLog}, then reduces the table if all weights share a
/// common power-of-2 factor.
final class PcoWeightQuantizer {

    private PcoWeightQuantizer() {
    }

    /// Result of weight quantization.
    ///
    /// @param sizeLog log₂ of the ANS table size; sum of weights == {@code 1 << sizeLog}
    /// @param weights quantized ANS weight per bin
    record Result(int sizeLog, int[] weights) {
    }

    static Result quantize(int[] counts, int totalCount, int maxSizeLog) {
        if (counts.length == 1) {
            return new Result(0, new int[]{1});
        }

        int minSizeLog = 32 - Integer.numberOfLeadingZeros(counts.length - 1);
        int sizeLog = Math.max(minSizeLog, maxSizeLog);
        int[] weights = quantizeTo(counts, totalCount, sizeLog);

        int minTrailing = Integer.MAX_VALUE;
        for (int w : weights) {
            minTrailing = Math.min(minTrailing, Integer.numberOfTrailingZeros(w));
        }
        int pow2 = minTrailing == Integer.MAX_VALUE ? 0 : minTrailing;
        sizeLog -= pow2;
        for (int i = 0; i < weights.length; i++) {
            weights[i] >>= pow2;
        }
        return new Result(sizeLog, weights);
    }

    private static int[] quantizeTo(int[] counts, int totalCount, int sizeLog) {
        if (sizeLog == 0) {
            return new int[]{1};
        }
        int requiredSum = 1 << sizeLog;
        float multiplier = (float) requiredSum / totalCount;

        float[] desiredSurplus = new float[counts.length];
        float totalDesiredSurplus = 0f;
        for (int i = 0; i < counts.length; i++) {
            desiredSurplus[i] = Math.max(0f, counts[i] * multiplier - 1f);
            totalDesiredSurplus += desiredSurplus[i];
        }

        int requiredSurplus = requiredSum - counts.length;
        float surplusMult = totalDesiredSurplus == 0f ? 0f : (float) requiredSurplus / totalDesiredSurplus;

        float[] floatWeights = new float[counts.length];
        for (int i = 0; i < counts.length; i++) {
            floatWeights[i] = 1f + desiredSurplus[i] * surplusMult;
        }

        int[] weights = new int[counts.length];
        int weightSum = 0;
        for (int i = 0; i < counts.length; i++) {
            weights[i] = Math.round(floatWeights[i]);
            weightSum += weights[i];
        }

        int i = 0;
        while (weightSum > requiredSum) {
            if (weights[i] > 1 && weights[i] > floatWeights[i]) {
                weights[i]--;
                weightSum--;
            }
            i++;
        }
        i = 0;
        while (weightSum < requiredSum) {
            if (weights[i] < floatWeights[i]) {
                weights[i]++;
                weightSum++;
            }
            i++;
        }
        return weights;
    }
}
