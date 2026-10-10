package io.github.dfa1.vortex.writer.encode;

import java.util.HashMap;
import java.util.Map;
import java.util.OptionalLong;
import java.util.SplittableRandom;
import java.util.function.DoubleUnaryOperator;

/// IntMult base detector — port of `pco/src/mode/int_mult.rs` choose_base.
///
/// Samples the input, computes triple-GCDs over disjoint triples, finds the most
/// statistically prominent GCD (z-score > 3 vs. the null hypothesis of uniform mod GCD),
/// then verifies the candidate saves &gt; 0.5 bits/element vs. Classic mode.
///
/// If a base is found, the encoder splits each latent as
/// `mult = latent / base; adj = latent % base` and encodes both as separate
/// ANS streams under mode=1.
final class PcoIntMultDetector {

    private static final int MIN_SAMPLE = 10;
    private static final int SAMPLE_RATIO = 40;
    private static final int SAMPLING_PERSISTENCE = 4;
    private static final long SEED = 0L;
    private static final double ZETA_OF_2 = Math.PI * Math.PI / 6.0;
    private static final double LCB_RATIO = 1.0;
    private static final double MULT_REQUIRED_BITS_SAVED_PER_NUM = 0.5;
    private static final int CLASSIC_MEMORIZABLE_BINS = 1 << 8;
    private static final double X_TOLERANCE = 1E-4;

    private PcoIntMultDetector() {
    }

    /// Choose the best IntMult base for unsigned latents, or empty if Classic wins.
    ///
    /// @param latents  unsigned-ordered latent values
    /// @return the chosen base if IntMult is favorable, otherwise empty
    static OptionalLong choose(long[] latents) {
        long[] sample = sample(latents);
        if (sample == null) {
            return OptionalLong.empty();
        }

        // Triple GCDs (port of pco's triple_gcds).
        int nTriples = sample.length / 3;
        long[] tripleGcds = new long[nTriples];
        int tripleCount = 0;
        for (int t = 0; t < nTriples; t++) {
            long g = tripleGcd(sample[3 * t], sample[3 * t + 1], sample[3 * t + 2]);
            if (g > 1L) {
                tripleGcds[tripleCount++] = g;
            }
        }
        if (tripleCount == 0) {
            return OptionalLong.empty();
        }

        double[] bitsSavedPerAdj = new double[1];
        long base = mostProminentGcd(tripleGcds, tripleCount, nTriples, bitsSavedPerAdj);
        if (base == 0L) {
            return OptionalLong.empty();
        }

        double bitsSaved = estBitsSavedPerNum(sample, base, bitsSavedPerAdj[0]);
        if (bitsSaved > MULT_REQUIRED_BITS_SAVED_PER_NUM) {
            return OptionalLong.of(base);
        }
        return OptionalLong.empty();
    }

    private static long[] sample(long[] latents) {
        int n = latents.length;
        if (n < MIN_SAMPLE) {
            return null;
        }
        int target = MIN_SAMPLE + (n - MIN_SAMPLE) / SAMPLE_RATIO;
        SplittableRandom rng = new SplittableRandom(SEED);
        boolean[] visited = new boolean[n];
        long[] out = new long[target];
        int collected = 0;
        int iters = 0;
        int maxIters = SAMPLING_PERSISTENCE * target;
        while (collected < target && iters < maxIters) {
            int idx = rng.nextInt(n);
            if (!visited[idx]) {
                visited[idx] = true;
                out[collected++] = latents[idx];
            }
            iters++;
        }
        if (collected < MIN_SAMPLE) {
            return null;
        }
        if (collected < target) {
            long[] trimmed = new long[collected];
            System.arraycopy(out, 0, trimmed, 0, collected);
            return trimmed;
        }
        return out;
    }

    // Unsigned GCD using subtract-and-mod.
    private static long unsignedGcd(long x, long y) {
        if (x == 0L) {
            return y;
        }
        while (y != 0L) {
            long r = Long.remainderUnsigned(x, y);
            x = y;
            y = r;
        }
        return x;
    }

    // Sort three latents (unsigned), return gcd(b - a, c - a).
    private static long tripleGcd(long a, long b, long c) {
        if (Long.compareUnsigned(a, b) > 0) {
            long t = a;
            a = b;
            b = t;
        }
        if (Long.compareUnsigned(b, c) > 0) {
            long t = b;
            b = c;
            c = t;
        }
        if (Long.compareUnsigned(a, b) > 0) {
            long t = a;
            a = b;
            b = t;
        }
        return unsignedGcd(b - a, c - a);
    }

    // The best-scoring GCD, or 0 if none passes the filter; its score goes to bestScoreOut[0].
    private static long mostProminentGcd(long[] gcds, int count, int totalTriples, double[] bestScoreOut) {
        Map<Long, Integer> counts = new HashMap<>();
        for (int i = 0; i < count; i++) {
            counts.merge(gcds[i], 1, Integer::sum);
        }
        long bestGcd = 0L;
        double bestScore = 0.0;
        for (Map.Entry<Long, Integer> e : counts.entrySet()) {
            long gcd = e.getKey();
            int triplesWithGcd = e.getValue();
            double score = filterScoreTripleGcd(gcd, triplesWithGcd, totalTriples);
            if (score > bestScore) {
                bestScore = score;
                bestGcd = gcd;
            }
        }
        bestScoreOut[0] = bestScore;
        return bestGcd;
    }

    // Port of filter_score_triple_gcd: worst-case bits saved per number by this modulus, or 0 if
    // rejected. The worst-case-entropy bound is what rejects a GCD seen in only a couple of triples
    // of random data; without it random columns tried IntMult on most chunks.
    private static double filterScoreTripleGcd(long gcd, int triplesWithGcd, int totalTriples) {
        double g = unsignedToDouble(gcd);
        double tw = triplesWithGcd;
        double tt = totalTriples;
        double prob = tw / tt;
        // null hypothesis: uniform modulo gcd, so P(exactly this gcd) = 1 / (zeta(2) * gcd^2)
        double natural = 1.0 / (ZETA_OF_2 * g * g);
        double stdev = Math.sqrt(natural * (1.0 - natural) / tt);
        double z = (prob - natural) / stdev;
        if (z < 3.0) {
            return 0.0;
        }
        double twLcb = tw - LCB_RATIO * Math.sqrt(tw);
        if (twLcb <= 0.0) {
            return 0.0;
        }
        double congruenceLcb = Math.min(ZETA_OF_2 * twLcb / tt, 1.0);
        double gcdM1 = g - 1.0;
        double gcdM1InvSq = 1.0 / (gcdM1 * gcdM1);
        DoubleUnaryOperator f = p -> p * p * p + Math.pow(1.0 - p, 3) * gcdM1InvSq - congruenceLcb;
        double lb = 1.0 / g;
        double ub = Math.cbrt(congruenceLcb) + Math.ulp(1.0);
        double concentratedP = solveRootByFalsePosition(f, lb, ub);
        if (Double.isNaN(concentratedP)) {
            return 0.0;
        }
        double bitsSaved = log2(g) - worstCaseCategoricalEntropy(concentratedP, gcdM1);
        return bitsSaved < MULT_REQUIRED_BITS_SAVED_PER_NUM ? 0.0 : bitsSaved;
    }

    // Port of solve_root_by_false_position; NaN where Rust returns None.
    private static double solveRootByFalsePosition(DoubleUnaryOperator f, double lb, double ub) {
        double flb = f.applyAsDouble(lb);
        double fub = f.applyAsDouble(ub);
        if (flb > 0.0 || fub < 0.0) {
            return Double.NaN;
        }
        while (ub - lb > X_TOLERANCE && fub - flb > 0.0) {
            double lbProp = 0.001 + 0.998 * fub / (fub - flb);
            double mid = lbProp * lb + (1.0 - lbProp) * ub;
            double fmid = f.applyAsDouble(mid);
            if (fmid < 0.0) {
                lb = mid;
                flb = fmid;
            } else {
                ub = mid;
                fub = fmid;
            }
        }
        return (lb + ub) / 2.0;
    }

    private static double singleCategoryEntropy(double p) {
        return p == 0.0 || p == 1.0 ? 0.0 : -p * log2(p);
    }

    private static double worstCaseCategoricalEntropy(double concentratedP, double nCategoriesM1) {
        return singleCategoryEntropy(concentratedP)
            + nCategoriesM1 * singleCategoryEntropy((1.0 - concentratedP) / nCategoriesM1);
    }

    private static double log2(double x) {
        return Math.log(x) / Math.log(2.0);
    }

    private static double unsignedToDouble(long x) {
        return x >= 0 ? x : (double) (x >>> 1) * 2.0;
    }

    // Mirror Rust est_bits_saved_per_num: bits we'd save vs. Classic by
    // using IntMult with this base. Conservative.
    private static double estBitsSavedPerNum(long[] sample, long base, double bitsSavedPerAdj) {
        // Count distinct mults to filter infrequent (cutoff = max(1, n / 256)).
        Map<Long, double[]> agg = new HashMap<>();
        for (long x : sample) {
            long mult = Long.divideUnsigned(x, base);
            agg.computeIfAbsent(mult, k -> new double[2]);
            double[] e = agg.get(mult);
            e[0] += 1;
            e[1] += bitsSavedPerAdj;
        }
        int cutoff = Math.max(1, sample.length / CLASSIC_MEMORIZABLE_BINS);
        double sum = 0.0;
        for (double[] e : agg.values()) {
            if (e[0] <= cutoff) {
                sum += e[1];
            }
        }
        return sum / sample.length;
    }

}
