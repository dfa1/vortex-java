package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.compute.PrimitiveArrays;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.EncodingId;

import java.lang.reflect.Array;

/// Read-only stats over a primitive array, computed in a single scan and shared across
/// all encoders that requested a given stat via [StatsOptions]. Replaces the per-encoder
/// sample-encoding probe that biases on leading rows.
///
/// All values are stored as raw 64-bit patterns (`doubleToRawLongBits` for floats,
/// zero-extended unsigned ints for U8/U16/U32). Use [#mostFrequentBits()] etc. and
/// reinterpret per ptype as needed.
///
/// @param valueCount        number of non-null elements scanned
/// @param distinctCount     distinct value count, or `-1` if not requested
/// @param mostFrequentBits  raw bits of the most-frequent value, or `0` if not requested
/// @param topFrequency      occurrence count of the most-frequent value, or `0` if not requested
/// @param distinctCapped    `true` when the scan stopped early because the distinct count passed
///                          `valueCount / 2 + 1`. [#distinctCount()] is then a lower bound and
///                          [#mostFrequentBits()] / [#topFrequency()] are partial — see
///                          [#distinctCapped()] for why every consumer can still decide
/// @param averageRunLength  `valueCount` divided by the number of runs of equal values, as Rust's
///                          `average_run_length` (integer division; floats compare as values, so
///                          `-0.0 == 0.0` and every NaN starts a run). Always computed, as in
///                          Rust: it is a single branch-free pass, never capped
public record ArrayStats(
        long valueCount,
        long distinctCount,
        long mostFrequentBits,
        long topFrequency,
        boolean distinctCapped,
        long averageRunLength
) {

    /// Sentinel stats for empty arrays.
    public static final ArrayStats EMPTY = new ArrayStats(0, 0, 0, 0, false, 0);

    /// Value ranges narrower than this are always counted densely: Rust's
    /// `DENSE_DISTINCT_ALWAYS_RANGE` (covers every 8-bit array).
    private static final long DENSE_ALWAYS_RANGE = 1 << 8;

    /// Value ranges narrower than this, and than the array, are counted densely: Rust's
    /// `DENSE_DISTINCT_MAX_RANGE`.
    private static final long DENSE_MAX_RANGE = 1 << 16;

    /// Compute stats over `data` according to `options`.
    ///
    /// @param ptype   the primitive type of `data`
    /// @param data    the input array (one of: `byte[]`, `short[]`, `int[]`,
    ///                `long[]`, `float[]`, `double[]`)
    /// @param options which stats to compute; merged options from all eligible encoders
    /// @return immutable [ArrayStats]
    public static ArrayStats compute(PType ptype, Object data, StatsOptions options) {
        int n = Array.getLength(data);
        if (n == 0) {
            return EMPTY;
        }
        long averageRunLength = n / runs(ptype, data, n);
        if (!options.countDistinct() && !options.trackMostFrequent()) {
            // Nothing to accumulate — the scan below would read every element and discard it.
            return new ArrayStats(n, -1, 0, 0, false, averageRunLength);
        }

        // Stop once the distinct count passes half the rows: past that point every consumer's
        // verdict is already determined, so the remaining probes cannot change any decision.
        // Dict skips (distinct * 2 >= n), Constant skips (distinct != 1), and Sparse skips by
        // pigeonhole — if a value occurred n/2 times, the other n/2 rows could hold at most
        // n/2 + 1 distinct values in total, so passing that bound proves no value reaches the
        // frequency Sparse needs. RunEnd is the one consumer whose rule (distinct >= n) is not
        // settled, so it defers to the sample-encoded path instead.
        //
        // Worth ~50% of the hashed rows on high-cardinality columns (measured on all-distinct and
        // random-2^40 corpora) and nothing at all on low-cardinality ones, which exit the scan
        // having never reached the cap.
        long cap = n / 2L + 1L;
        if (!ptype.isFloating()) {
            // Widened once (zero-copy for I64/U64) so one loop serves every integer width.
            long[] values = PrimitiveArrays.toLongs(data, ptype, EncodingId.VORTEX_PRIMITIVE);
            return integerStats(values, n, cap, options.countDistinct(), averageRunLength);
        }

        // Sized for the low-cardinality case and grown from there, NOT for the cap. Pre-sizing
        // to the cap (what the reference does, `array.len() / 2`) removes every resize but
        // allocates a table proportional to the chunk on every call: measured here it raised
        // write garbage by 64-96% with no throughput gain, so the growth factor carries the
        // high-cardinality case instead — see LongIntMap#grow.
        LongIntMap counts = new LongIntMap(Math.min(n, 2048));
        boolean capped = switch (ptype) {
            case F16 -> scanF16((short[]) data, n, counts, cap);
            case F32 -> scanF32((float[]) data, n, counts, cap);
            default -> scanF64((double[]) data, n, counts, cap);
        };
        return fromCounts(n, counts, capped, options.countDistinct(), averageRunLength);
    }

    /// Integer stats as Rust's `typed_int_stats` (vortex-compressor #10037, #10041) computes
    /// them: the min/max decides between a dense counter and a hash map, and the hash map is
    /// touched once per run of equal values rather than once per row.
    ///
    /// Both shortcuts produce exactly the counts a per-row hash would, so every encoder's verdict
    /// is unchanged; only the cost moves. Before them this was the hottest frame of a cascading
    /// write — on an ALP-encoded price column (ints 5000..15000) every row paid a hash probe for
    /// a range a 10k-slot array covers.
    ///
    /// @param a      the values, widened to `long` (unsigned widths zero-extended)
    /// @param n      element count, at least 1
    /// @param cap    distinct count past which the hashed scan may stop
    /// @param countDistinct whether [#distinctCount()] is reported
    /// @param averageRunLength the already computed [#averageRunLength()]
    /// @return the stats
    private static ArrayStats integerStats(long[] a, int n, long cap, boolean countDistinct, long averageRunLength) {
        long min = a[0];
        long max = a[0];
        for (int i = 0; i < n; i++) {
            min = Math.min(min, a[i]);
            max = Math.max(max, a[i]);
        }
        // Signed difference: wraps negative exactly when the true range exceeds Long.MAX_VALUE.
        // U64 values above 2^63 sit at negative signed positions, but the signed interval still
        // bounds every value, so dense indexing stays correct; only Rust's choice of path (which
        // ranges in unsigned space) can differ, and the counts are identical either way.
        long span = max - min;
        if (span >= 0 && (span < DENSE_ALWAYS_RANGE
                || span < DENSE_MAX_RANGE && span < n)) {
            return denseStats(a, n, min, (int) span + 1, countDistinct, averageRunLength);
        }

        LongIntMap counts = new LongIntMap(Math.min(n, 2048));
        long prev = a[0];
        int pending = 0;
        for (int i = 0; i < n; i++) {
            long v = a[i];
            if (v != prev) {
                counts.increment(prev, pending);
                if (counts.size() > cap) {
                    return fromCounts(n, counts, true, countDistinct, averageRunLength);
                }
                prev = v;
                pending = 0;
            }
            pending++;
        }
        counts.increment(prev, pending);
        return fromCounts(n, counts, counts.size() > cap, countDistinct, averageRunLength);
    }

    /// Counts by `value - min` into an array: no hashing, and never capped.
    private static ArrayStats denseStats(long[] a, int n, long min, int range, boolean countDistinct,
                                         long averageRunLength) {
        int[] counts = new int[range];
        for (int i = 0; i < n; i++) {
            counts[(int) (a[i] - min)]++;
        }
        int distinct = 0;
        int topIndex = 0;
        for (int i = 0; i < range; i++) {
            int c = counts[i];
            if (c != 0) {
                distinct++;
            }
            // Strictly greater: ties keep the smaller value, as LongIntMap#maxEntry does, so the
            // named value does not depend on which counter ran.
            if (c > counts[topIndex]) {
                topIndex = i;
            }
        }
        return new ArrayStats(n, countDistinct ? distinct : -1L, min + topIndex, counts[topIndex], false,
                averageRunLength);
    }

    private static ArrayStats fromCounts(int n, LongIntMap counts, boolean capped, boolean countDistinct,
                                         long averageRunLength) {
        LongIntMap.Entry top = counts.maxEntry();
        long topFreqBits = top == null ? 0L : top.key();
        int topFreq = top == null ? 0 : top.value();
        long distinct = countDistinct ? counts.size() : -1L;
        return new ArrayStats(n, distinct, topFreqBits, topFreq, capped, averageRunLength);
    }

    /// Runs of equal values: 1 plus every change between neighbors. One loop per carrier with a
    /// branch-free body, so C2 vectorizes it (CLAUDE.md hot-loop rule).
    private static long runs(PType ptype, Object data, int n) {
        long changes = 0;
        switch (ptype) {
            case I8, U8 -> {
                byte[] a = (byte[]) data;
                for (int i = 1; i < n; i++) {
                    changes += a[i] != a[i - 1] ? 1 : 0;
                }
            }
            case I16, U16, F16 -> {
                short[] a = (short[]) data;
                for (int i = 1; i < n; i++) {
                    changes += a[i] != a[i - 1] ? 1 : 0;
                }
            }
            case I32, U32 -> {
                int[] a = (int[]) data;
                for (int i = 1; i < n; i++) {
                    changes += a[i] != a[i - 1] ? 1 : 0;
                }
            }
            case I64, U64 -> {
                long[] a = (long[]) data;
                for (int i = 1; i < n; i++) {
                    changes += a[i] != a[i - 1] ? 1 : 0;
                }
            }
            case F32 -> {
                float[] a = (float[]) data;
                for (int i = 1; i < n; i++) {
                    changes += a[i] != a[i - 1] ? 1 : 0;
                }
            }
            case F64 -> {
                double[] a = (double[]) data;
                for (int i = 1; i < n; i++) {
                    changes += a[i] != a[i - 1] ? 1 : 0;
                }
            }
        }
        return changes + 1;
    }

    private static boolean scanF16(short[] a, int n, LongIntMap counts, long cap) {
        for (int i = 0; i < n; i++) {
            counts.increment(Short.toUnsignedLong(a[i]));
            if (counts.size() > cap) {
                return true;
            }
        }
        return false;
    }

    private static boolean scanF32(float[] a, int n, LongIntMap counts, long cap) {
        for (int i = 0; i < n; i++) {
            counts.increment(Float.floatToRawIntBits(a[i]));
            if (counts.size() > cap) {
                return true;
            }
        }
        return false;
    }

    private static boolean scanF64(double[] a, int n, LongIntMap counts, long cap) {
        for (int i = 0; i < n; i++) {
            counts.increment(Double.doubleToRawLongBits(a[i]));
            if (counts.size() > cap) {
                return true;
            }
        }
        return false;
    }


    /// @return whether [#distinctCount()] was computed during this scan
    public boolean hasDistinctCount() {
        return distinctCount >= 0;
    }

    /// @return whether [#mostFrequentBits()] + [#topFrequency()] were computed
    public boolean hasMostFrequent() {
        return topFrequency > 0;
    }

    /// Validates that distinct count was requested and throws if missing.
    ///
    /// @param requester encoding id used in the error message
    /// @return the distinct value count
    public long requireDistinctCount(EncodingId requester) {
        if (!hasDistinctCount()) {
            throw new VortexException(requester, "ArrayStats.distinctCount not computed");
        }
        return distinctCount;
    }
}
