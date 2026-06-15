package io.github.dfa1.vortex.writer.encode;

import java.util.HashMap;
import java.util.Map;
import java.util.OptionalLong;
import java.util.SplittableRandom;

/// IntMult base detector — port of {@code pco/src/mode/int_mult.rs} choose_base.
///
/// Samples the input, computes triple-GCDs over disjoint triples, finds the most
/// statistically prominent GCD (z-score > 3 vs. the null hypothesis of uniform mod GCD),
/// then verifies the candidate saves &gt; 0.5 bits/element vs. Classic mode.
///
/// If a base is found, the encoder splits each latent as
/// {@code mult = latent / base; adj = latent % base} and encodes both as separate
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

    private PcoIntMultDetector() {
    }

    /// Choose the best IntMult base for unsigned latents, or empty if Classic wins.
    ///
    /// @param latents  unsigned-ordered latent values
    /// @param dtypeSize physical width in bits (16/32/64) — restricts GCD search
    /// @return the chosen base if IntMult is favourable, otherwise empty
    static OptionalLong choose(long[] latents, int dtypeSize) {
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

        OptionalLong candidate = mostProminentGcd(tripleGcds, tripleCount, nTriples);
        if (candidate.isEmpty()) {
            return OptionalLong.empty();
        }
        long base = candidate.getAsLong();

        // Verify bits-saved per num against Classic.
        double bitsSavedPerAdj = estBitsSavedPerAdj(sample, base, dtypeSize);
        double bitsSaved = estBitsSavedPerNum(sample, base, bitsSavedPerAdj);
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

    private static OptionalLong mostProminentGcd(long[] gcds, int count, int totalTriples) {
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
        return bestGcd > 1L ? OptionalLong.of(bestGcd) : OptionalLong.empty();
    }

    // Statistical filter: returns score (bits saved per triple) or 0 if rejected.
    // Simplified port — sufficient for "is this a real IntMult signal?".
    private static double filterScoreTripleGcd(long gcd, int triplesWithGcd, int totalTriples) {
        if (totalTriples == 0) {
            return 0.0;
        }
        double tw = triplesWithGcd;
        double tt = totalTriples;
        double prob = tw / tt;
        double natural = 1.0 / (ZETA_OF_2 * gcd * gcd);
        double stdev = Math.sqrt(natural * (1.0 - natural) / tt);
        if (stdev == 0.0) {
            return 0.0;
        }
        double z = (prob - natural) / stdev;
        if (z < 3.0) {
            return 0.0;
        }
        double twLcb = tw - LCB_RATIO * Math.sqrt(tw);
        if (twLcb <= 0.0) {
            return 0.0;
        }
        // bits saved per triple ~ log2(gcd) - H, conservative estimate.
        return Math.log(gcd) / Math.log(2.0) - 1.0; // conservative entropy estimate
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

    // For each sample element, bits saved by encoding adj rather than the full value.
    private static double estBitsSavedPerAdj(long[] sample, long base, int dtypeSize) {
        // Each adj is in [0, base) so costs log2(base) bits worst case.
        // Saving vs. Classic ≈ dtypeSize - log2(base).
        double log2Base = Math.log(base) / Math.log(2.0);
        return dtypeSize - log2Base;
    }
}
