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
public record ArrayStats(
        long valueCount,
        long distinctCount,
        long mostFrequentBits,
        long topFrequency
) {

    /// Sentinel stats for empty arrays.
    public static final ArrayStats EMPTY = new ArrayStats(0, 0, 0, 0);

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
            return new ArrayStats(n, -1, 0, 0);
        }
        LongCounts counts = options.countDistinct() || options.trackMostFrequent()
                                    ? new LongCounts(Math.min(n, 1 << 16))
                                    : null;
        long topFreqBits = 0;
        int topFreq = 0;
        for (int i = 0; i < n; i++) {
            long bits = readBits(ptype, data, i);
            if (counts != null) {
                int newCount = counts.increment(bits);
                if (newCount > topFreq) {
                    topFreq = newCount;
                    topFreqBits = bits;
                }
            }
        }
        long distinct = options.countDistinct() ? counts.size() : -1L;
        return new ArrayStats(n, distinct, topFreqBits, topFreq);
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

        LongCounts(int expectedDistinct) {
            int capacity = nextPowerOfTwo(Math.max(16, expectedDistinct * 2));
            keys = new long[capacity];
            counts = new int[capacity];
            mask = capacity - 1;
        }

        /// Increments `key`'s count (inserting a fresh entry first if unseen) and returns the
        /// updated count.
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
            return ++counts[slot];
        }

        int size() {
            return size;
        }

        private void grow() {
            long[] oldKeys = keys;
            int[] oldCounts = counts;
            keys = new long[oldKeys.length * 2];
            counts = new int[oldCounts.length * 2];
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

    private static long readBits(PType ptype, Object data, int i) {
        return switch (ptype) {
            case I8 -> ((byte[]) data)[i];
            case U8 -> Byte.toUnsignedLong(((byte[]) data)[i]);
            case I16 -> ((short[]) data)[i];
            case U16, F16 -> Short.toUnsignedLong(((short[]) data)[i]);
            case I32 -> ((int[]) data)[i];
            case U32 -> Integer.toUnsignedLong(((int[]) data)[i]);
            case I64, U64 -> ((long[]) data)[i];
            case F32 -> Float.floatToRawIntBits(((float[]) data)[i]);
            case F64 -> Double.doubleToRawLongBits(((double[]) data)[i]);
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
