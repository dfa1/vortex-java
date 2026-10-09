package io.github.dfa1.vortex.writer.encode;

import java.util.Arrays;

/// Open-addressing map from a 64-bit key to an `int` value, for the write path's per-row scans.
///
/// The encode path kept growing private copies of this — a distinct-value counter in
/// [ArrayStats], a value-to-code index in `DictEncodingEncoder`, another in
/// `io.github.dfa1.vortex.writer.DictColumnState`, and a capped distinct set in the same class.
/// They existed because probing a `HashMap<Object, Integer>` boxes a key per row, which profiled
/// as the hottest frame of a write more than once. This is that structure, once.
///
/// Shape and the reasons for it:
/// - Keys are raw 64-bit patterns, so callers compare floats by `doubleToLongBits` and never box.
/// - Capacity is a power of two, so probing masks rather than taking a modulo (CLAUDE.md
///   hot-loop rule: a modulo per element blocks auto-vectorization and costs 20–40 cycles).
/// - The slot is the top bits of `key * phi`, as Fibonacci hashing takes them. Bits 32 and up
///   of the product (what this used before) see only the key's low 50 bits, so keys that differ
///   mostly above them — doubles, whose exponent and leading mantissa sit there — piled into
///   long probe chains: 17 ns per row on a price column, 5 ns after (#476).
/// - Growth is 4x, not 2x. Starting small keeps a low-cardinality column cheap, but doubling made
///   a high-cardinality one rehash six times on the way up and `grow` alone measured 10.5% of a
///   cascading write.
/// - Values are stored biased by one, so a slot holding `0` means empty and `0` is a perfectly
///   ordinary value to store. Absence is reported as `-1`, so the only value callers cannot
///   store is a negative one — which none of them do, since they store codes and counts.
///
/// Not thread-safe, and deliberately minimal: no removal, no iteration, no boxing.
public final class LongIntMap {

    /// Fibonacci hashing multiplier — the odd 64-bit constant closest to 2^64 / phi.
    private static final long HASH_MULTIPLIER = 0x9E3779B97F4A7C15L;

    private long[] keys;
    private int[] values;
    private int mask;
    private int shift;
    private int size;
    private int growAt;

    /// Creates a map sized for roughly `expectedEntries` before its first resize.
    ///
    /// @param expectedEntries entries the caller expects; the table is sized to twice this,
    ///                        rounded up to a power of two, with a floor of 16 slots
    public LongIntMap(int expectedEntries) {
        int capacity = nextPowerOfTwo(Math.max(16, expectedEntries * 2));
        keys = new long[capacity];
        values = new int[capacity];
        mask = capacity - 1;
        shift = Long.numberOfLeadingZeros(mask);
        growAt = capacity / 2;
    }

    /// The value stored for `key`, or `-1` when the key has never been stored.
    ///
    /// Slots hold `value + 1`, so the empty marker `0` decodes to `-1` by the same subtraction
    /// that decodes a real value — which is why the fast path needs no separate absent branch,
    /// and why storing `0` is fine. Keeping it branch-light also keeps it small, which matters:
    /// see [#probe].
    ///
    /// @param key the raw bit pattern to look up
    /// @return the stored value, or `-1` if absent
    public int get(long key) {
        int slot = slotOf(key);
        return keys[slot] == key ? values[slot] - 1 : probe(key);
    }

    /// Collision path for [#getOrDefault], kept out of line deliberately.
    ///
    /// The whole probe loop measured 56 bytes, over C2's `MaxInlineSize` of 35, so it inlined
    /// only where the compiler judged the site hot — and `PrintInlining` showed it losing that
    /// judgement inside `DictColumnState.ingestDictChunk`'s per-row loop in some compilations.
    /// Splitting the first probe out leaves a fast path small enough to inline unconditionally;
    /// with the table at most half full, that path resolves the large majority of lookups.
    ///
    /// Recomputes the slot rather than taking it, so the fast path's call site pushes one
    /// fewer argument — which is what brings [#get] under `MaxInlineSize`. This is the collision
    /// path, so the extra multiply is free.
    ///
    /// @param key the raw bit pattern
    /// @return the stored value, or `-1` if absent
    private int probe(long key) {
        int slot = slotOf(key);
        while (values[slot] != 0) {
            if (keys[slot] == key) {
                return values[slot] - 1;
            }
            slot = (slot + 1) & mask;
        }
        return -1;
    }

    /// Stores `value` for `key`, replacing any previous value.
    ///
    /// @param key   the raw bit pattern
    /// @param value the value to store; must not be negative
    public void put(long key, int value) {
        int slot = slotOf(key);
        while (values[slot] != 0 && keys[slot] != key) {
            slot = (slot + 1) & mask;
        }
        boolean fresh = values[slot] == 0;
        keys[slot] = key;
        values[slot] = value + 1;
        if (fresh) {
            size++;
            growIfNeeded();
        }
    }

    /// Adds `key` to the map if absent, as a membership set.
    ///
    /// @param key the raw bit pattern
    /// @return `true` if `key` was not already present
    public boolean add(long key) {
        int before = size;
        int slot = slotOf(key);
        while (values[slot] != 0 && keys[slot] != key) {
            slot = (slot + 1) & mask;
        }
        if (values[slot] == 0) {
            keys[slot] = key;
            values[slot] = 1;
            size++;
            growIfNeeded();
        }
        return size != before;
    }

    /// Increments `key`'s count, inserting it with a count of 1 when unseen.
    ///
    /// One probe, not a get followed by a put: this runs once per row of every column the
    /// cascade computes stats for and is the hottest frame of a write.
    ///
    /// @param key the raw bit pattern to count
    /// @return the updated count for `key`
    public int increment(long key) {
        return increment(key, 1);
    }

    /// Adds `count` to `key`'s count, inserting it when unseen: one probe for a whole run of
    /// equal values (see `ArrayStats`).
    ///
    /// @param key   the raw bit pattern to count
    /// @param count occurrences to add; must be positive
    /// @return the updated count for `key`
    public int increment(long key, int count) {
        int slot = slotOf(key);
        while (values[slot] != 0 && keys[slot] != key) {
            slot = (slot + 1) & mask;
        }
        int raw = values[slot];
        int updated = (raw == 0 ? 0 : raw - 1) + count;
        values[slot] = updated + 1;
        if (raw == 0) {
            // Only a fresh slot needs its key written. Storing it on every call added a memory
            // write per row to the hottest loop of a write and measured ~5% slower. The key is
            // set before growIfNeeded, so a resize here never sees a half-written entry.
            keys[slot] = key;
            size++;
            growIfNeeded();
        }
        return updated;
    }

    /// The entry with the highest value, or `null` when the map is empty.
    ///
    /// Found by one pass over the table rather than tracked on every [#increment], which keeps
    /// the per-row loop to a single probe. The table holds at most twice the distinct count, so
    /// this pass is bounded by cardinality, not by row count. Ties break on the smaller key so
    /// the answer does not depend on the table's capacity.
    ///
    /// @return the highest-valued entry, or `null` if nothing has been stored
    public Entry maxEntry() {
        long bestKey = 0;
        int bestRaw = 0;
        for (int i = 0; i < values.length; i++) {
            int raw = values[i];
            // Ties break on the smaller key, never on slot order: slot order depends on the
            // table's capacity, so without this the winner among equally-frequent values changes
            // when the map happens to be sized differently — and the compressor's choice of
            // encoding would stop being reproducible for the same input.
            if (raw > bestRaw || (raw == bestRaw && raw != 0 && keys[i] < bestKey)) {
                bestRaw = raw;
                bestKey = keys[i];
            }
        }
        return bestRaw == 0 ? null : new Entry(bestKey, bestRaw - 1);
    }

    /// One key-value pair.
    ///
    /// @param key   the raw bit pattern
    /// @param value the stored value
    public record Entry(long key, int value) {
    }

    /// @return the number of distinct keys stored
    public int size() {
        return size;
    }

    private void growIfNeeded() {
        if (size > growAt) {
            grow();
        }
    }

    private void grow() {
        long[] oldKeys = keys;
        int[] oldValues = values;
        int capacity = oldKeys.length * 4;
        keys = new long[capacity];
        values = new int[capacity];
        mask = capacity - 1;
        shift = Long.numberOfLeadingZeros(mask);
        growAt = capacity / 2;
        for (int i = 0; i < oldKeys.length; i++) {
            if (oldValues[i] != 0) {
                int slot = slotOf(oldKeys[i]);
                while (values[slot] != 0) {
                    slot = (slot + 1) & mask;
                }
                keys[slot] = oldKeys[i];
                values[slot] = oldValues[i];
            }
        }
    }

    /// An instance method, not a static taking `mask`: every call site would otherwise push a
    /// `getfield` for the mask, and that single load is what kept [#get] over C2's
    /// `MaxInlineSize` of 35.
    ///
    /// @param key the raw bit pattern
    /// @return the starting slot for `key`
    private int slotOf(long key) {
        return (int) ((key * HASH_MULTIPLIER) >>> shift);
    }

    private static int nextPowerOfTwo(int x) {
        return x <= 1 ? 1 : Integer.highestOneBit(x - 1) << 1;
    }

    @Override
    public String toString() {
        return "LongIntMap[size=" + size + ", capacity=" + keys.length + "]";
    }

    /// Package-private for tests: the table's current slot count.
    ///
    /// @return the capacity in slots
    public int capacity() {
        return keys.length;
    }

    /// Package-private for tests: resets the map to empty without reallocating.
    public void clear() {
        Arrays.fill(values, 0);
        size = 0;
    }
}
