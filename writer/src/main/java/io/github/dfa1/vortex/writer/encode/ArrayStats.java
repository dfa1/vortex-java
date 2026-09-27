package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.EncodingId;

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
        int n = arrayLength(ptype, data);
        if (n == 0) {
            return EMPTY;
        }
        if (options == StatsOptions.NONE) {
            return new ArrayStats(n, -1, 0, 0, false);
        }
        // Sized for the low-cardinality case and grown from there, NOT for `n`: pre-sizing to
        // min(n, 1<<16) allocated a 1 MB long[] plus a 512 kB int[] on every call even for a
        // 500-distinct column, and that pair was the single largest allocation source in a
        // cascade competition. The growth factor, not this floor, is what keeps a
        // high-cardinality column from rehashing its way up — see LongCounts#grow.
        if (!options.countDistinct() && !options.trackMostFrequent()) {
            // Nothing to accumulate — the scan below would read every element and discard it.
            return new ArrayStats(n, -1, 0, 0, false);
        }
        LongCounts counts = new LongCounts(Math.min(n, 2048));

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

        // The ptype switch is hoisted out of the scan: reading it per element made the loop body
        // non-uniform and put `readBits` alone at 9% of write CPU (CLAUDE.md hot-loop rule). Each
        // carrier therefore gets one loop of its own, extracted below so this method dispatches
        // rather than inlining nine of them. One method PER SIGNEDNESS, never a shared body with
        // an `unsigned` flag: that flag is a branch inside the per-element loop, which measured
        // 3-8% slower — the same non-uniform body the hoisting exists to avoid.
        // Most-frequent tracking lives in LongCounts#increment so each body is a single call.
        boolean capped = scanInto(ptype, data, n, counts, cap);

        long topFreqBits = counts.topBits();
        int topFreq = counts.topCount();
        long distinct = options.countDistinct() ? counts.size() : -1L;
        return new ArrayStats(n, distinct, topFreqBits, topFreq, capped);
    }

    /// Open-addressing `long -> count` map used by [#compute] to track distinct values and their
    /// occurrence counts without boxing every scanned bit pattern into a `Long` key (a
    /// `HashMap<Long, int[]>`'s per-entry `Node` object plus the boxed key was the largest single
    /// allocation source profiled in a stats-heavy cascade competition — every candidate primitive
    /// column pays this on every chunk). Occupancy is tracked by `counts[slot] != 0` rather than a
    /// separate flags array: a slot is only ever written once occupied, so a stored 0 always means
    /// empty, even for a genuinely-zero data value. Capacity is always a power of two so probing
    /// masks instead of taking a modulo (CLAUDE.md hot-loop rule).
    private static final class LongCounts {

        private static final long HASH_MULTIPLIER = 0x9E3779B97F4A7C15L;

        private long[] keys;
        private int[] counts;
        private int mask;
        private int size;
        private long topBits;
        private int topCount;

        LongCounts(int expectedDistinct) {
            int capacity = nextPowerOfTwo(Math.max(16, expectedDistinct * 2));
            keys = new long[capacity];
            counts = new int[capacity];
            mask = capacity - 1;
        }

        /// Increments `key`'s count (inserting a fresh entry first if unseen), updates the
        /// running most-frequent value, and returns the updated count.
        int increment(long key) {
            if (size * 2 >= keys.length) {
                grow();
            }
            int slot = slotFor(key, mask);
            while (counts[slot] != 0 && keys[slot] != key) {
                slot = (slot + 1) & mask;
            }
            if (counts[slot] == 0) {
                keys[slot] = key;
                size++;
            }
            int updated = ++counts[slot];
            if (updated > topCount) {
                topCount = updated;
                topBits = key;
            }
            return updated;
        }

        /// The bit pattern of the most frequently seen value.
        long topBits() {
            return topBits;
        }

        /// How many times the most frequently seen value occurred.
        int topCount() {
            return topCount;
        }

        int size() {
            return size;
        }

        /// Quadruples rather than doubles. Starting small keeps the common low-cardinality
        /// column cheap, but doubling made a high-cardinality one rehash six times on the way up
        /// and `grow()` alone measured 10.5% of a cascading write. Growing by 4x halves the
        /// rehashes without charging low-cardinality columns for capacity they never use —
        /// raising the starting floor instead won on high-cardinality corpora but cost 3.9% on a
        /// low-cardinality one, which is the common case.
        private void grow() {
            long[] oldKeys = keys;
            int[] oldCounts = counts;
            keys = new long[oldKeys.length * 4];
            counts = new int[oldCounts.length * 4];
            mask = keys.length - 1;
            for (int i = 0; i < oldKeys.length; i++) {
                if (oldCounts[i] != 0) {
                    int slot = slotFor(oldKeys[i], mask);
                    while (counts[slot] != 0) {
                        slot = (slot + 1) & mask;
                    }
                    keys[slot] = oldKeys[i];
                    counts[slot] = oldCounts[i];
                }
            }
        }

        private static int slotFor(long key, int mask) {
            long mixed = key * HASH_MULTIPLIER;
            return (int) (mixed >>> 32) & mask;
        }

        private static int nextPowerOfTwo(int x) {
            return x <= 1 ? 1 : Integer.highestOneBit(x - 1) << 1;
        }
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
    private static boolean scanInto(PType ptype, Object data, int n, LongCounts counts, long cap) {
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

    private static boolean scanI8(byte[] a, int n, LongCounts counts, long cap) {
        for (int i = 0; i < n; i++) {
            counts.increment(a[i]);
            if (counts.size() > cap) {
                return true;
            }
        }
        return false;
    }

    private static boolean scanU8(byte[] a, int n, LongCounts counts, long cap) {
        for (int i = 0; i < n; i++) {
            counts.increment(Byte.toUnsignedLong(a[i]));
            if (counts.size() > cap) {
                return true;
            }
        }
        return false;
    }

    private static boolean scanI16(short[] a, int n, LongCounts counts, long cap) {
        for (int i = 0; i < n; i++) {
            counts.increment(a[i]);
            if (counts.size() > cap) {
                return true;
            }
        }
        return false;
    }

    private static boolean scanU16(short[] a, int n, LongCounts counts, long cap) {
        for (int i = 0; i < n; i++) {
            counts.increment(Short.toUnsignedLong(a[i]));
            if (counts.size() > cap) {
                return true;
            }
        }
        return false;
    }

    private static boolean scanI32(int[] a, int n, LongCounts counts, long cap) {
        for (int i = 0; i < n; i++) {
            counts.increment(a[i]);
            if (counts.size() > cap) {
                return true;
            }
        }
        return false;
    }

    private static boolean scanU32(int[] a, int n, LongCounts counts, long cap) {
        for (int i = 0; i < n; i++) {
            counts.increment(Integer.toUnsignedLong(a[i]));
            if (counts.size() > cap) {
                return true;
            }
        }
        return false;
    }

    private static boolean scanI64(long[] a, int n, LongCounts counts, long cap) {
        for (int i = 0; i < n; i++) {
            counts.increment(a[i]);
            if (counts.size() > cap) {
                return true;
            }
        }
        return false;
    }

    private static boolean scanF32(float[] a, int n, LongCounts counts, long cap) {
        for (int i = 0; i < n; i++) {
            counts.increment(Float.floatToRawIntBits(a[i]));
            if (counts.size() > cap) {
                return true;
            }
        }
        return false;
    }

    private static boolean scanF64(double[] a, int n, LongCounts counts, long cap) {
        for (int i = 0; i < n; i++) {
            counts.increment(Double.doubleToRawLongBits(a[i]));
            if (counts.size() > cap) {
                return true;
            }
        }
        return false;
    }

    private static int arrayLength(PType ptype, Object data) {
        return switch (ptype) {
            case I8, U8 -> ((byte[]) data).length;
            case I16, U16, F16 -> ((short[]) data).length;
            case I32, U32 -> ((int[]) data).length;
            case I64, U64 -> ((long[]) data).length;
            case F32 -> ((float[]) data).length;
            case F64 -> ((double[]) data).length;
        };
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
