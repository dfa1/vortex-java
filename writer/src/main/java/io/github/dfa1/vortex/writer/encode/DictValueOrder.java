package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.model.PType;

import java.util.Arrays;

/// The order Rust's integer and float dictionary schemes give a dictionary's values (#651).
///
/// Rust takes them in the iteration order of the `hashbrown` map (`FxBuildHasher`) its stats
/// collect the distinct values into (`vortex-compressor` `stats/integer.rs`, `stats/float.rs`), and
/// the codes follow. A first-seen order holds the same values under other codes, so the codes'
/// bit widths, and the scheme the cascade picks for them, differ from Rust's. This replays that
/// map: `rustc-hash` 2.1's 64-bit `FxHasher` and `hashbrown` 0.17's probing, growth and iteration.
final class DictValueOrder {

    private static final long FX_K = 0xf1357aea2e62a9c5L;
    private static final int FX_ROTATE = 26;
    /// `hashbrown`'s `Group::WIDTH`: 16 with SSE2 on x86, 8 with NEON or the generic group
    /// elsewhere. Rust's order depends on it once 16 buckets in a row are full, so ours follows
    /// the host's, as a vortex-jni write on the same machine does.
    private static final int GROUP_WIDTH = groupWidth(System.getProperty("os.arch", ""));

    private DictValueOrder() {
    }

    /// For each first-seen code, its code in Rust's order.
    ///
    /// @param ptype     the column's primitive type
    /// @param raw       each distinct value's raw bits, by first-seen code (sign-extended for
    ///                  integers narrower than 64 bits)
    /// @param dictSize  the number of distinct values
    /// @param len       the array's length, nulls included
    /// @param fullGrows whether a valid row came after the last new value: Rust's float set
    ///                  reserves on every insert, so a duplicate on a full table grows it
    /// @return `rank[firstSeenCode]` = Rust's code
    static int[] ranks(PType ptype, long[] raw, int dictSize, int len, boolean fullGrows) {
        if (dictSize == 0) {
            return new int[0];
        }
        long widthMask = ptype.bits() == 64 ? -1L : (1L << ptype.bits()) - 1;
        long[] hashes = new long[dictSize];
        for (int c = 0; c < dictSize; c++) {
            hashes[c] = Long.rotateLeft((raw[c] & widthMask) * FX_K, FX_ROTATE);
        }
        Table table;
        if (ptype.isFloating()) {
            // HashSet<NativeValue<T>>: one entry is the float itself
            table = new Table(hashes, len / 2, ptype.byteSize());
            for (int c = 0; c < dictSize; c++) {
                table.insert(c);
            }
            if (fullGrows && table.growthLeft == 0) {
                table.grow();
            }
        } else if (isDense(ptype, raw, dictSize, len)) {
            // counted by offset from the min, then collected in ascending order into a new map
            Integer[] ascending = new Integer[dictSize];
            for (int c = 0; c < dictSize; c++) {
                ascending[c] = c;
            }
            Arrays.sort(ascending, (a, b) -> compare(ptype, raw[a], raw[b]));
            table = new Table(hashes, 0, entrySize(ptype));
            for (int c : ascending) {
                table.insert(c);
            }
        } else {
            table = new Table(hashes, len / 2, entrySize(ptype));
            for (int c = 0; c < dictSize; c++) {
                table.insert(c);
            }
        }
        return table.ranks();
    }

    /// Rust's `DistinctCounter::new`: a dense counter when the valid values span at most 256
    /// values, or at most 65,536 and no more than the array's length.
    private static boolean isDense(PType ptype, long[] raw, int dictSize, int len) {
        long min = raw[0];
        long max = raw[0];
        for (int c = 1; c < dictSize; c++) {
            if (compare(ptype, raw[c], min) < 0) {
                min = raw[c];
            }
            if (compare(ptype, raw[c], max) > 0) {
                max = raw[c];
            }
        }
        // the wrapping difference is exact: the true one is below 2^64
        long span = max - min;
        return Long.compareUnsigned(span, 255) <= 0
                || (Long.compareUnsigned(span, 65_535) <= 0 && span + 1 <= len);
    }

    private static int compare(PType ptype, long a, long b) {
        if (ptype.isSigned()) {
            return Long.compare(a, b);
        }
        long mask = ptype.bits() == 64 ? -1L : (1L << ptype.bits()) - 1;
        return Long.compareUnsigned(a & mask, b & mask);
    }

    /// `size_of::<(NativeValue<T>, u32)>()`, the integer map's entry.
    private static int entrySize(PType ptype) {
        return ptype.byteSize() == 8 ? 16 : 8;
    }

    static int groupWidth(String arch) {
        return switch (arch) {
            case "amd64", "x86_64", "x86", "i386", "i686" -> 16;
            default -> 8;
        };
    }

    /// The occupancy of a `hashbrown` raw table: a bit per bucket and the bucket of each code.
    /// Nothing is ever removed, so a bucket is full or empty, never deleted. Rust sizes the map
    /// from the array's length, not its distinct count, so a bit per bucket keeps a long array's
    /// table small.
    private static final class Table {

        private final long[] hashes;
        private final int entrySize;
        private final long[] bucketOf;
        // codes in insertion order, which the dense path does not take in code order
        private final int[] inserted;
        private long[] full;
        private long buckets;
        private int items;
        private long growthLeft;

        Table(long[] hashes, long capacity, int entrySize) {
            this.hashes = hashes;
            this.entrySize = entrySize;
            this.bucketOf = new long[hashes.length];
            this.inserted = new int[hashes.length];
            this.buckets = capacity == 0 ? 1 : capacityToBuckets(capacity);
            this.full = new long[(int) ((buckets + 63) >>> 6)];
            this.items = 0;
            this.growthLeft = capacityOf(buckets - 1);
        }

        /// `RawTable::insert` of a new key: reserve one, growing a full table, then the first
        /// empty bucket of the probe sequence.
        void insert(int code) {
            if (growthLeft == 0) {
                grow();
            }
            place(code);
            inserted[items++] = code;
            growthLeft--;
        }

        /// `reserve_rehash` without deletes: resize to the next capacity, re-inserting in
        /// iteration order.
        void grow() {
            int[] order = iterationOrder();
            buckets = capacityToBuckets(Math.max(items + 1L, capacityOf(buckets - 1) + 1));
            full = new long[(int) ((buckets + 63) >>> 6)];
            for (int code : order) {
                place(code);
            }
            growthLeft = capacityOf(buckets - 1) - items;
        }

        /// Iteration order is bucket order.
        int[] ranks() {
            int[] rank = new int[items];
            int[] order = iterationOrder();
            for (int i = 0; i < order.length; i++) {
                rank[order[i]] = i;
            }
            return rank;
        }

        private int[] iterationOrder() {
            // ponytail: sorts by bucket, O(d log d) per grow; a scan of the bitmap would be linear in buckets instead
            long[] keyed = new long[items];
            for (int i = 0; i < items; i++) {
                keyed[i] = bucketOf[inserted[i]] << 32 | inserted[i];
            }
            Arrays.sort(keyed);
            int[] order = new int[items];
            for (int i = 0; i < items; i++) {
                order[i] = (int) keyed[i];
            }
            return order;
        }

        private void place(int code) {
            long index = findInsert(hashes[code]);
            full[(int) (index >>> 6)] |= 1L << index;
            bucketOf[code] = index;
        }

        /// `capacity_to_buckets`.
        private long capacityToBuckets(long capacity) {
            if (capacity < 15) {
                long minCapacity = 3;
                if (GROUP_WIDTH == 16 && entrySize <= 1) {
                    minCapacity = 14;
                } else if ((GROUP_WIDTH == 16 && entrySize <= 3) || (GROUP_WIDTH == 8 && entrySize <= 1)) {
                    minCapacity = 7;
                }
                long cap = Math.max(minCapacity, capacity);
                if (cap < 4) {
                    return 4;
                }
                return cap < 8 ? 8 : 16;
            }
            return Long.highestOneBit(capacity * 8 / 7 - 1) << 1;
        }

        /// `bucket_mask_to_capacity`.
        private static long capacityOf(long bucketMask) {
            return bucketMask < 8 ? bucketMask : (bucketMask + 1) / 8 * 7;
        }

        /// `find_insert_index`: groups of [#GROUP_WIDTH] control bytes from `hash & mask`, in
        /// triangular steps. A table smaller than a group reads `EMPTY` past its end, then its
        /// mirrored bytes; when that lands on a full bucket, the first empty one wins
        /// (`fix_insert_index`).
        private long findInsert(long hash) {
            long mask = buckets - 1;
            long pos = hash & mask;
            long stride = 0;
            while (true) {
                for (int bit = 0; bit < GROUP_WIDTH; bit++) {
                    if (isEmptyCtrl(pos + bit)) {
                        long index = (pos + bit) & mask;
                        if (!isFull(index)) {
                            return index;
                        }
                        return firstEmpty();
                    }
                }
                stride += GROUP_WIDTH;
                pos = (pos + stride) & mask;
            }
        }

        /// The control byte at `ctrl`, which runs past the buckets by one group: a mirror of the
        /// first buckets, or `EMPTY` up to a whole group when the table is smaller than one.
        private boolean isEmptyCtrl(long ctrl) {
            if (ctrl < buckets) {
                return !isFull(ctrl);
            }
            if (buckets >= GROUP_WIDTH) {
                return !isFull(ctrl - buckets);
            }
            return ctrl < GROUP_WIDTH || !isFull(ctrl - GROUP_WIDTH);
        }

        private boolean isFull(long index) {
            return (full[(int) (index >>> 6)] & (1L << index)) != 0;
        }

        private long firstEmpty() {
            long i = 0;
            while (isFull(i)) {
                i++;
            }
            return i;
        }
    }
}

