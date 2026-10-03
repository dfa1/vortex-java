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
public record ArrayStats(
        long valueCount,
        long distinctCount,
        long mostFrequentBits,
        long topFrequency,
        boolean distinctCapped
) {

    /// Sentinel stats for empty arrays.
    public static final ArrayStats EMPTY = new ArrayStats(0, 0, 0, 0, false);

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
        if (options == StatsOptions.NONE) {
            return new ArrayStats(n, -1, 0, 0, false);
        }
        if (!options.countDistinct() && !options.trackMostFrequent()) {
            // Nothing to accumulate — the scan below would read every element and discard it.
            return new ArrayStats(n, -1, 0, 0, false);
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
        // Sized for the low-cardinality case and grown from there, NOT for the cap. Pre-sizing
        // to the cap (what the reference does, `array.len() / 2`) removes every resize but
        // allocates a table proportional to the chunk on every call: measured here it raised
        // write garbage by 64-96% with no throughput gain, so the growth factor carries the
        // high-cardinality case instead — see LongIntMap#grow.
        LongIntMap counts = new LongIntMap(Math.min(n, 2048));

        // The ptype switch is hoisted out of the scan: reading it per element made the loop body
        // non-uniform and put `readBits` alone at 9% of write CPU (CLAUDE.md hot-loop rule). Each
        // carrier therefore gets one loop of its own, extracted below so this method dispatches
        // rather than inlining nine of them. One method PER SIGNEDNESS, never a shared body with
        // an `unsigned` flag: that flag is a branch inside the per-element loop, which measured
        // 3-8% slower — the same non-uniform body the hoisting exists to avoid.
        // Most-frequent tracking is a single pass over the map at the end (LongIntMap#maxEntry),
        // not a compare per element, so each body is a single call.
        boolean capped = scanInto(ptype, data, n, counts, cap);

        LongIntMap.Entry top = counts.maxEntry();
        long topFreqBits = top == null ? 0L : top.key();
        int topFreq = top == null ? 0 : top.value();
        long distinct = options.countDistinct() ? counts.size() : -1L;
        return new ArrayStats(n, distinct, topFreqBits, topFreq, capped);
    }

    /// Feeds every element of `data` into `counts`, stopping early once the distinct count
    /// passes `cap`.
    ///
    /// @param ptype  the primitive type of `data`
    /// @param data   the input array
    /// @param n      element count
    /// @param counts the distinct/most-frequent accumulator
    /// @param cap    distinct count past which the scan may stop
    /// @return `true` if the scan stopped early at the cap
    private static boolean scanInto(PType ptype, Object data, int n, LongIntMap counts, long cap) {
        return switch (ptype) {
            case I8 -> scanI8((byte[]) data, n, counts, cap);
            case U8 -> scanU8((byte[]) data, n, counts, cap);
            case I16 -> scanI16((short[]) data, n, counts, cap);
            case U16, F16 -> scanU16((short[]) data, n, counts, cap);
            case I32 -> scanI32((int[]) data, n, counts, cap);
            case U32 -> scanU32((int[]) data, n, counts, cap);
            case I64, U64 -> scanI64((long[]) data, n, counts, cap);
            case F32 -> scanF32((float[]) data, n, counts, cap);
            case F64 -> scanF64((double[]) data, n, counts, cap);
        };
    }

    private static boolean scanI8(byte[] a, int n, LongIntMap counts, long cap) {
        for (int i = 0; i < n; i++) {
            counts.increment(a[i]);
            if (counts.size() > cap) {
                return true;
            }
        }
        return false;
    }

    private static boolean scanU8(byte[] a, int n, LongIntMap counts, long cap) {
        for (int i = 0; i < n; i++) {
            counts.increment(Byte.toUnsignedLong(a[i]));
            if (counts.size() > cap) {
                return true;
            }
        }
        return false;
    }

    private static boolean scanI16(short[] a, int n, LongIntMap counts, long cap) {
        for (int i = 0; i < n; i++) {
            counts.increment(a[i]);
            if (counts.size() > cap) {
                return true;
            }
        }
        return false;
    }

    private static boolean scanU16(short[] a, int n, LongIntMap counts, long cap) {
        for (int i = 0; i < n; i++) {
            counts.increment(Short.toUnsignedLong(a[i]));
            if (counts.size() > cap) {
                return true;
            }
        }
        return false;
    }

    private static boolean scanI32(int[] a, int n, LongIntMap counts, long cap) {
        for (int i = 0; i < n; i++) {
            counts.increment(a[i]);
            if (counts.size() > cap) {
                return true;
            }
        }
        return false;
    }

    private static boolean scanU32(int[] a, int n, LongIntMap counts, long cap) {
        for (int i = 0; i < n; i++) {
            counts.increment(Integer.toUnsignedLong(a[i]));
            if (counts.size() > cap) {
                return true;
            }
        }
        return false;
    }

    private static boolean scanI64(long[] a, int n, LongIntMap counts, long cap) {
        for (int i = 0; i < n; i++) {
            counts.increment(a[i]);
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
