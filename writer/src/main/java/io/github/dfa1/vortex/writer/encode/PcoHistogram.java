package io.github.dfa1.vortex.writer.encode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/// Equal-count histogram of a chunk's latents — port of `pco/src/histograms.rs`.
///
/// Quickselect rather than a sort: a partition whose values all fall into one bin is applied whole
/// (its min/max) and never ordered further, so the chunk is only partitioned down to bin granularity.
/// Equal values straddling a bin boundary go to the bin holding the middle of their run, as in Rust.
///
/// Keys are latents in sort-key space (`latent ^ Long.MIN_VALUE`), so signed order is latent order.
final class PcoHistogram {

    private final long n;
    private final long nBins;
    private final int nBinsLog;
    private final List<PcoHistBin> dst;
    private int nApplied;
    private int nextAvailBinIdx;
    private boolean hasIncomplete;
    private long incompleteCount;
    private long incompleteLower;
    private long incompleteUpper;

    private PcoHistogram(int n, int nBinsLog) {
        this.n = n;
        this.nBins = 1L << nBinsLog;
        this.nBinsLog = nBinsLog;
        this.dst = new ArrayList<>(1 << nBinsLog);
    }

    /// Builds the histogram, reordering `keys` in place.
    ///
    /// @param keys     sort keys of the latents; partially reordered on return
    /// @param nBinsLog log₂ of the target bin count
    /// @param maxKey   sort key of the latent type's maximum (`L::MAX`)
    /// @return bins in ascending key order
    static List<PcoHistBin> histogram(long[] keys, int nBinsLog, long maxKey) {
        PcoHistogram h = new PcoHistogram(keys.length, nBinsLog);
        int badPivotLimit = 1 + (31 - Integer.numberOfLeadingZeros(keys.length + 1));
        h.recurse(keys, 0, keys.length, Long.MIN_VALUE, false, maxKey, false, badPivotLimit);
        return h.dst;
    }

    private int binIdx(long cCount) {
        return (int) ((cCount << nBinsLog) / n);
    }

    private int cCount(int binIdx) {
        return (int) (((binIdx + 1) * n + nBins - 1) >> nBinsLog);
    }

    private void applyIncomplete(long[] v, int from, int to, long lower, boolean lowerTight, long upper, boolean upperTight) {
        if (from == to) {
            return;
        }
        if (hasIncomplete) {
            incompleteUpper = upperTight ? upper : max(v, from, to);
            incompleteCount += to - from;
        } else {
            incompleteLower = lowerTight ? lower : min(v, from, to);
            incompleteUpper = upperTight ? upper : max(v, from, to);
            incompleteCount = to - from;
            hasIncomplete = true;
        }
        nApplied += to - from;
    }

    private boolean completeBin(int binIdx) {
        if (!hasIncomplete) {
            return false;
        }
        nextAvailBinIdx = binIdx + 1;
        dst.add(new PcoHistBin(incompleteLower, incompleteUpper, incompleteCount));
        hasIncomplete = false;
        return true;
    }

    private void applyConstantRun(long[] v, int from, int to) {
        int start = nApplied;
        int len = to - from;
        int binIdx = binIdx(start + len / 2);
        if (binIdx > nextAvailBinIdx) {
            // the previous bin is free: complete the pending one there, or start this run in it
            int spareBinIdx = binIdx - 1;
            if (!completeBin(spareBinIdx)) {
                binIdx = spareBinIdx;
            }
        }
        applyIncomplete(v, from, to, v[from], true, v[from], true);
        if (start + len >= cCount(binIdx)) {
            completeBin(binIdx);
        }
    }

    private void applySorted(long[] v, int from, int to) {
        while (from < to) {
            int targetBinIdx = binIdx(nApplied);
            int targetI = cCount(targetBinIdx) - nApplied;
            int len = to - from;
            if (targetI >= len) {
                applyIncomplete(v, from, to, v[from], true, v[to - 1], true);
                if (targetI == len) {
                    completeBin(targetBinIdx);
                }
                return;
            }
            int l = from + targetI - 1;
            int r = from + targetI;
            long x = v[l];
            while (l > from && v[l - 1] == x) {
                l--;
            }
            while (r < to && v[r] == x) {
                r++;
            }
            if (l > from) {
                applyIncomplete(v, from, l, v[from], true, v[l - 1], true);
            }
            applyConstantRun(v, l, r);
            from = r;
        }
    }

    private void recurse(long[] v, int from, int to, long lb, boolean lbTight, long ub, boolean ubTight,
                         int badPivotLimit) {
        if (from == to) {
            return;
        }
        int targetBinIdx = binIdx(nApplied);
        int targetCCount = cCount(targetBinIdx);
        int end = nApplied + (to - from);
        if (end <= targetCCount) {
            applyIncomplete(v, from, to, lb, lbTight, ub, ubTight);
            if (end == targetCCount) {
                completeBin(targetBinIdx);
            }
            return;
        }
        if (lb == ub || to - from == 1) {
            applyConstantRun(v, from, to);
            return;
        }

        long tentativePivot = choosePivot(v, from, to);
        long pivot;
        long lhsUb;
        boolean lhsUbTight;
        long rhsLb;
        boolean rhsLbTight;
        if (tentativePivot > lb) {
            pivot = tentativePivot;
            lhsUb = tentativePivot - 1;
            lhsUbTight = false;
            rhsLb = tentativePivot;
            rhsLbTight = true;
        } else {
            pivot = tentativePivot + 1;
            lhsUb = tentativePivot;
            lhsUbTight = true;
            rhsLb = tentativePivot + 1;
            rhsLbTight = false;
        }
        int mid = from + partition(v, from, to, pivot);
        int len = to - from;
        int lhsCount = mid - from;
        if (1 + Math.min(lhsCount, len - lhsCount) < len / 8) {
            badPivotLimit--;
            if (badPivotLimit == 0) {
                Arrays.sort(v, from, mid);
                Arrays.sort(v, mid, to);
                applySorted(v, from, to);
                return;
            }
            breakPatterns(v, from, mid);
            breakPatterns(v, mid, to);
        }
        recurse(v, from, mid, lb, lbTight, lhsUb, lhsUbTight, badPivotLimit);
        recurse(v, mid, to, rhsLb, rhsLbTight, ub, ubTight, badPivotLimit);
    }

    // Port of sort_utils::choose_pivot: median of three, or of three medians-of-three from 50 up.
    private static long choosePivot(long[] v, int from, int to) {
        int len = to - from;
        int a = from + len / 4;
        int b = from + len / 2;
        int c = from + (len * 3) / 4;
        if (len >= 8) {
            if (len >= 50) {
                a = medianOfThree(v, a - 1, a, a + 1);
                b = medianOfThree(v, b - 1, b, b + 1);
                c = medianOfThree(v, c - 1, c, c + 1);
            }
            b = medianOfThree(v, a, b, c);
        }
        return v[b];
    }

    // Rust's sort3 over indices: the index holding the median, ties resolved as Rust's swaps do.
    private static int medianOfThree(long[] v, int a, int b, int c) {
        if (v[b] < v[a]) {
            int t = a;
            a = b;
            b = t;
        }
        if (v[c] < v[b]) {
            int t = b;
            b = c;
            c = t;
        }
        if (v[b] < v[a]) {
            b = a;
        }
        return b;
    }

    // Branch-free Lomuto partition (sort_utils::partition): the count of values below pivot.
    private static int partition(long[] v, int from, int to, long pivot) {
        int left = from;
        for (int pos = from; pos < to; pos++) {
            long value = v[pos];
            v[pos] = v[left];
            v[left] = value;
            left += value < pivot ? 1 : 0;
        }
        return left - from;
    }

    // Port of sort_utils::break_patterns: three xorshift swaps near the middle.
    private static void breakPatterns(long[] v, int from, int to) {
        int len = to - from;
        if (len < 8) {
            return;
        }
        long seed = len;
        int modulus = Integer.highestOneBit(len - 1) << 1;
        int pos = len / 4 * 2;
        for (int i = 0; i < 3; i++) {
            seed ^= seed << 13;
            seed ^= seed >>> 7;
            seed ^= seed << 17;
            int other = (int) (seed & (modulus - 1));
            if (other >= len) {
                other -= len;
            }
            int x = from + pos - 1 + i;
            int y = from + other;
            long t = v[x];
            v[x] = v[y];
            v[y] = t;
        }
    }

    private static long min(long[] v, int from, int to) {
        long m = Long.MAX_VALUE;
        for (int i = from; i < to; i++) {
            m = Math.min(m, v[i]);
        }
        return m;
    }

    private static long max(long[] v, int from, int to) {
        long m = Long.MIN_VALUE;
        for (int i = from; i < to; i++) {
            m = Math.max(m, v[i]);
        }
        return m;
    }
}
