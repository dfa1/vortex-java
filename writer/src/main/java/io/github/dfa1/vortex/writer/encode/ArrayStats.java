package io.github.dfa1.vortex.writer.encode;

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
/// @param min               the smallest value of an integer array in its own order (unsigned for
///                          U64, so read it with [#minIsNegative(PType)]); `0` for floats. Always
///                          computed for integers, as Rust's `IntegerStats` always has min/max
/// @param max               the largest value, likewise
public record ArrayStats(
        long valueCount,
        long distinctCount,
        long mostFrequentBits,
        long topFrequency,
        boolean distinctCapped,
        long averageRunLength,
        long min,
        long max
) {

    /// Sentinel stats for empty arrays.
    public static final ArrayStats EMPTY = new ArrayStats(0, 0, 0, 0, false, 0, 0, 0);

    /// Value ranges narrower than this are always counted densely: Rust's
    /// `DENSE_DISTINCT_ALWAYS_RANGE` (covers every 8-bit array).
    private static final long DENSE_ALWAYS_RANGE = 1 << 8;

    /// Value ranges narrower than this, and than the array, are counted densely: Rust's
    /// `DENSE_DISTINCT_MAX_RANGE`.
    private static final long DENSE_MAX_RANGE = 1 << 16;

    /// Integer values are widened and checked for transitions this many at a time, as Rust's
    /// `typed_int_stats` chunks them.
    private static final int CHUNK = 64;

    /// Integer values are widened this many at a time: a whole number of [#CHUNK]s, large enough
    /// that the per-call switch and loop setup vanish, small enough to stay in L1.
    private static final int BUFFER = 1024;

    /// Below this average run length a dense count skips run detection: there is too little to
    /// merge to pay for the check.
    private static final long SHORT_RUNS = 4;

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
        boolean accumulate = options.countDistinct() || options.trackMostFrequent();

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
            // Widened a chunk at a time into one small buffer, so one loop serves every integer
            // width without copying the whole array to a long[] (8 bytes per row of garbage on
            // every int[] column, #476).
            long[] buffer = new long[BUFFER];
            long[] minMax = minMax(ptype, data);
            long min = minMax[0];
            long max = minMax[1];
            ArrayStats counted = accumulate
                    ? integerStats(ptype, data, n, buffer, cap, options.countDistinct(), averageRunLength, min, max)
                    : new ArrayStats(n, -1, 0, 0, false, averageRunLength, 0, 0);
            if (ptype == PType.U64) {
                // Narrower unsigned widths are zero-extended, so signed order is already theirs.
                long[] values = (long[]) data;
                long unsignedMin = -1L;
                long unsignedMax = 0L;
                for (int i = 0; i < n; i++) {
                    unsignedMin = Long.compareUnsigned(values[i], unsignedMin) < 0 ? values[i] : unsignedMin;
                    unsignedMax = Long.compareUnsigned(values[i], unsignedMax) > 0 ? values[i] : unsignedMax;
                }
                return counted.withMinMax(unsignedMin, unsignedMax);
            }
            return counted.withMinMax(min, max);
        }
        if (!accumulate) {
            // Nothing to accumulate — the scan below would read every element and discard it.
            return new ArrayStats(n, -1, 0, 0, false, averageRunLength, 0, 0);
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
    /// them: the min/max decides between a dense counter and a hash map, either counter is
    /// touched once per run of equal values rather than once per row, and a chunk of 64 values
    /// without a transition (checked branch-free) is counted in one step.
    ///
    /// The shortcuts produce exactly the counts a per-row count would, so every encoder's verdict
    /// is unchanged; only the cost moves. Before them this was the hottest frame of a cascading
    /// write — on an ALP-encoded price column (ints 5000..15000) every row paid a hash probe for
    /// a range a 10k-slot array covers, and a dense `counts[v]++` per row on a runny date column
    /// serialized on the store to the same slot.
    ///
    /// @param ptype  the integer type of `data`
    /// @param data   the values
    /// @param n      element count, at least 1
    /// @param buffer scratch buffer of [#BUFFER] values
    /// @param cap    distinct count past which the hashed scan may stop
    /// @param countDistinct whether [#distinctCount()] is reported
    /// @param averageRunLength the already computed [#averageRunLength()]
    /// @param min    the signed minimum of `data`, widened
    /// @param max    the signed maximum of `data`, widened
    /// @return the stats, min/max not yet set
    private static ArrayStats integerStats(PType ptype, Object data, int n, long[] buffer, long cap,
                                           boolean countDistinct, long averageRunLength, long min, long max) {
        // Signed difference: wraps negative exactly when the true range exceeds Long.MAX_VALUE.
        // U64 values above 2^63 sit at negative signed positions, but the signed interval still
        // bounds every value, so dense indexing stays correct; only Rust's choice of path (which
        // ranges in unsigned space) can differ, and the counts are identical either way.
        long span = max - min;
        int[] dense = span >= 0 && (span < DENSE_ALWAYS_RANGE || span < DENSE_MAX_RANGE && span < n)
                ? new int[(int) span + 1]
                : null;
        LongIntMap hashed = dense == null ? new LongIntMap(Math.min(n, 2048)) : null;

        if (dense != null && averageRunLength < SHORT_RUNS) {
            // Runs too short to merge: a branch-free increment per row, without the transition
            // check, which on run-free columns measured 2x slower than plain counting.
            for (int from = 0; from < n; from += BUFFER) {
                int len = widen(ptype, data, from, n, buffer);
                for (int i = 0; i < len; i++) {
                    dense[(int) (buffer[i] - min)]++;
                }
            }
            return fromDense(n, dense, min, countDistinct, averageRunLength);
        }
        widen(ptype, data, 0, n, buffer);
        long prev = buffer[0];
        int pending = 0;
        for (int from = 0; from < n; from += BUFFER) {
            int len = widen(ptype, data, from, n, buffer);
            for (int start = 0; start < len; start += CHUNK) {
                int end = Math.min(start + CHUNK, len);
                if (end - start == CHUNK) {
                    int transitions = buffer[start] != prev ? 1 : 0;
                    for (int i = start + 1; i < end; i++) {
                        transitions += buffer[i] != buffer[i - 1] ? 1 : 0;
                    }
                    if (transitions == 0) {
                        pending += CHUNK;
                        continue;
                    }
                }
                for (int i = start; i < end; i++) {
                    long v = buffer[i];
                    if (v != prev) {
                        if (dense != null) {
                            dense[(int) (prev - min)] += pending;
                        } else {
                            hashed.increment(prev, pending);
                            if (hashed.size() > cap) {
                                return fromCounts(n, hashed, true, countDistinct, averageRunLength);
                            }
                        }
                        prev = v;
                        pending = 0;
                    }
                    pending++;
                }
            }
        }
        if (dense != null) {
            dense[(int) (prev - min)] += pending;
            return fromDense(n, dense, min, countDistinct, averageRunLength);
        }
        hashed.increment(prev, pending);
        return fromCounts(n, hashed, hashed.size() > cap, countDistinct, averageRunLength);
    }

    /// Stats from counts indexed by `value - min`: never capped.
    private static ArrayStats fromDense(int n, int[] counts, long min, boolean countDistinct,
                                        long averageRunLength) {
        int distinct = 0;
        int topIndex = 0;
        for (int i = 0; i < counts.length; i++) {
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
                averageRunLength, 0, 0);
    }

    /// Signed min and max of the widened values, computed on the array's own carrier: NEON has
    /// no 64-bit integer min/max, so C2 vectorizes these loops for `int`, `short` and `byte`
    /// but not over a widened `long` buffer, where this pass measured ~8% of a write (#476).
    /// Unsigned widths are masked in the loop, which keeps them in their carrier's lanes.
    ///
    /// @return `{min, max}`
    private static long[] minMax(PType ptype, Object data) {
        return switch (ptype) {
            case I8 -> {
                byte[] a = (byte[]) data;
                int min = Integer.MAX_VALUE;
                int max = Integer.MIN_VALUE;
                for (byte v : a) {
                    min = Math.min(min, v);
                    max = Math.max(max, v);
                }
                yield new long[]{min, max};
            }
            case U8 -> {
                byte[] a = (byte[]) data;
                int min = Integer.MAX_VALUE;
                int max = Integer.MIN_VALUE;
                for (byte v : a) {
                    min = Math.min(min, v & 0xFF);
                    max = Math.max(max, v & 0xFF);
                }
                yield new long[]{min, max};
            }
            case I16 -> {
                short[] a = (short[]) data;
                int min = Integer.MAX_VALUE;
                int max = Integer.MIN_VALUE;
                for (short v : a) {
                    min = Math.min(min, v);
                    max = Math.max(max, v);
                }
                yield new long[]{min, max};
            }
            case U16 -> {
                short[] a = (short[]) data;
                int min = Integer.MAX_VALUE;
                int max = Integer.MIN_VALUE;
                for (short v : a) {
                    min = Math.min(min, v & 0xFFFF);
                    max = Math.max(max, v & 0xFFFF);
                }
                yield new long[]{min, max};
            }
            case I32 -> {
                int[] a = (int[]) data;
                int min = Integer.MAX_VALUE;
                int max = Integer.MIN_VALUE;
                for (int v : a) {
                    min = Math.min(min, v);
                    max = Math.max(max, v);
                }
                yield new long[]{min, max};
            }
            case U32 -> {
                // Flipping the sign bit maps unsigned order onto signed order, so the loop stays
                // a plain int min/max.
                int[] a = (int[]) data;
                int min = Integer.MAX_VALUE;
                int max = Integer.MIN_VALUE;
                for (int v : a) {
                    min = Math.min(min, v ^ Integer.MIN_VALUE);
                    max = Math.max(max, v ^ Integer.MIN_VALUE);
                }
                yield new long[]{Integer.toUnsignedLong(min ^ Integer.MIN_VALUE),
                        Integer.toUnsignedLong(max ^ Integer.MIN_VALUE)};
            }
            case I64, U64 -> {
                long[] a = (long[]) data;
                long min = Long.MAX_VALUE;
                long max = Long.MIN_VALUE;
                for (long v : a) {
                    min = Math.min(min, v);
                    max = Math.max(max, v);
                }
                yield new long[]{min, max};
            }
            default -> throw new VortexException(EncodingId.VORTEX_PRIMITIVE, "not an integer ptype: " + ptype);
        };
    }

    /// Copies up to `chunk.length` values of `data` from `from` into `chunk`, sign-extending the signed
    /// widths and zero-extending the unsigned ones; one switch per chunk, not per value.
    ///
    /// @return how many values were copied
    private static int widen(PType ptype, Object data, int from, int n, long[] chunk) {
        int len = Math.min(chunk.length, n - from);
        switch (ptype) {
            case I8 -> {
                byte[] a = (byte[]) data;
                for (int i = 0; i < len; i++) {
                    chunk[i] = a[from + i];
                }
            }
            case U8 -> {
                byte[] a = (byte[]) data;
                for (int i = 0; i < len; i++) {
                    chunk[i] = a[from + i] & 0xFFL;
                }
            }
            case I16 -> {
                short[] a = (short[]) data;
                for (int i = 0; i < len; i++) {
                    chunk[i] = a[from + i];
                }
            }
            case U16 -> {
                short[] a = (short[]) data;
                for (int i = 0; i < len; i++) {
                    chunk[i] = a[from + i] & 0xFFFFL;
                }
            }
            case I32 -> {
                int[] a = (int[]) data;
                for (int i = 0; i < len; i++) {
                    chunk[i] = a[from + i];
                }
            }
            case U32 -> {
                int[] a = (int[]) data;
                for (int i = 0; i < len; i++) {
                    chunk[i] = a[from + i] & 0xFFFF_FFFFL;
                }
            }
            case I64, U64 -> System.arraycopy((long[]) data, from, chunk, 0, len);
            default -> throw new VortexException(EncodingId.VORTEX_PRIMITIVE, "not an integer ptype: " + ptype);
        }
        return len;
    }

    private static ArrayStats fromCounts(int n, LongIntMap counts, boolean capped, boolean countDistinct,
                                         long averageRunLength) {
        LongIntMap.Entry top = counts.maxEntry();
        long topFreqBits = top == null ? 0L : top.key();
        int topFreq = top == null ? 0 : top.value();
        long distinct = countDistinct ? counts.size() : -1L;
        return new ArrayStats(n, distinct, topFreqBits, topFreq, capped, averageRunLength, 0, 0);
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


    private ArrayStats withMinMax(long newMin, long newMax) {
        return new ArrayStats(valueCount, distinctCount, mostFrequentBits, topFrequency, distinctCapped,
                averageRunLength, newMin, newMax);
    }

    /// Rust's `ErasedStats::min_is_negative`: never for an unsigned type.
    ///
    /// @param ptype the integer type these stats were computed for
    /// @return whether the minimum is below zero
    public boolean minIsNegative(PType ptype) {
        return !ptype.isUnsigned() && min < 0;
    }

    /// Rust's `ErasedStats::max_minus_min`, as an unsigned 64-bit value: exact for every integer
    /// type, since the true difference of two 64-bit values is below 2^64.
    ///
    /// @return `max - min`, to be read unsigned
    public long maxMinusMin() {
        return max - min;
    }

    /// Rust's `ErasedStats::max_ilog2`: floor(log2) of the maximum reinterpreted as unsigned of
    /// its own width, as bit-packing measures widths.
    ///
    /// @param ptype the integer type these stats were computed for
    /// @return the log, or `-1` when the maximum is zero (Rust's `None`)
    public int maxIlog2(PType ptype) {
        long unsigned = max & (ptype.bits() == 64 ? -1L : (1L << ptype.bits()) - 1);
        return 63 - Long.numberOfLeadingZeros(unsigned);
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
