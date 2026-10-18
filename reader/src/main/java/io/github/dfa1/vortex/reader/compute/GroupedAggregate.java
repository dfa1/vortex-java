package io.github.dfa1.vortex.reader.compute;

import io.github.dfa1.vortex.core.compute.LongIntMap;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.reader.Chunk;
import io.github.dfa1.vortex.reader.RowFilter;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.BoolArray;
import io.github.dfa1.vortex.reader.array.DoubleArray;
import io.github.dfa1.vortex.reader.array.FloatArray;
import io.github.dfa1.vortex.reader.array.VarBinDictArray;
import io.github.dfa1.vortex.reader.array.VarBinOffsetArray;
import io.github.dfa1.vortex.reader.array.VarBinSlicedArray;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongFunction;
import java.util.function.LongPredicate;
import java.util.function.LongUnaryOperator;

/// Grouped aggregation that never builds a row or hashes a boxed key per row. Rows accumulate per
/// group id in primitive arrays, and each id becomes its key once, when the per-group results are
/// built. Three group columns qualify:
/// - a dictionary-encoded string column (or a slice of one): the group id is the dictionary code,
///   and each code is turned into its string once;
/// - a boolean column: two ids;
/// - an integer column (not `u64`, whose values past 2^63 have no signed-long key): ids assigned
///   through a primitive [LongIntMap], up to [#MAX_GROUPS] distinct values per chunk.
///
/// The aggregate column must be one the long or double lane can fold. Anything else returns empty
/// so the caller falls back to its row path.
final class GroupedAggregate {

    /// Most groups accumulated per chunk. A dictionary's size comes from an untrusted offsets buffer,
    /// and an integer column may hold a value per row, so more than this declines rather than size
    /// the per-group arrays from either.
    private static final int MAX_GROUPS = 1 << 16;

    private GroupedAggregate() {
    }

    /// Folds `aggColumn` per value of `groupColumn` over the rows `filter` selects.
    ///
    /// @param chunk       the decoded chunk holding every referenced column
    /// @param groupColumn the column to group by
    /// @param filter      the rows to fold, or `null` for every row
    /// @param aggColumn   the numeric column to reduce, or `null` to count rows only
    /// @return the fold per group value (`String`, `Boolean` or `Long`; the `null` key is the null
    ///         group), or empty when the group or aggregate column has a shape this kernel does not fold
    static Optional<Map<Object, FilteredAggregate>> aggregate(Chunk chunk, ColumnName groupColumn,
                                                              RowFilter filter, ColumnName aggColumn) {
        Lane lane = Lane.of(aggColumn == null ? null : chunk.column(aggColumn));
        if (lane == null) {
            return Optional.empty();
        }
        Array group = chunk.column(groupColumn);
        BoolArray validity = NumericColumns.validityOf(group);
        LongPredicate selected = filter == null ? null : FusedFilterAggregate.rowTest(chunk, filter);
        return switch (NumericColumns.unwrap(group)) {
            case VarBinDictArray d -> byCode(d, 0, validity, selected, lane, chunk.rowCount());
            case VarBinSlicedArray(var _, var _, VarBinDictArray d, long offset) ->
                    byCode(d, offset, validity, selected, lane, chunk.rowCount());
            case BoolArray b -> byKey(i -> b.getBoolean(i) ? 1 : 0, validity, selected, lane, chunk.rowCount(),
                    key -> key != 0);
            case Array data when NumericColumns.isLongDomain(data)
                    && data.dtype() instanceof DType.Primitive p && p.ptype() != PType.U64 -> {
                boolean unsigned = p.ptype().isUnsigned();
                yield byKey(i -> NumericColumns.widenLong(data, unsigned, i), validity, selected, lane,
                        chunk.rowCount(), key -> key);
            }
            default -> Optional.empty();
        };
    }

    /// Groups by dictionary code: one slot per entry, plus the null group.
    private static Optional<Map<Object, FilteredAggregate>> byCode(VarBinDictArray dict, long offset,
            BoolArray validity, LongPredicate selected, Lane lane, long n) {
        VarBinOffsetArray values = dict.values();
        if (!(dict.dtype() instanceof DType.Utf8) || values.length() > MAX_GROUPS) {
            return Optional.empty();
        }
        int groups = (int) values.length();
        // one slot per dictionary entry, plus the null group at index `groups`
        Accumulators acc = new Accumulators(groups + 1, lane);
        for (long i = 0; i < n; i++) {
            if (selected != null && !selected.test(i)) {
                continue;
            }
            int g;
            if (validity != null && !validity.getBoolean(i)) {
                g = groups;
            } else {
                long code = dict.code(i + offset);
                if (code < 0 || code >= groups) {
                    throw new VortexException("dict code " + code + " at row " + (i + offset)
                            + " out of range for " + groups + " dictionary entries");
                }
                g = (int) code;
            }
            acc.add(g, i);
        }
        Map<Object, FilteredAggregate> result = new HashMap<>();
        for (int g = 0; g <= groups; g++) {
            if (acc.rows[g] > 0) {
                String key = g == groups ? null : values.getString(g);
                // two entries may hold the same string; a group is a value, not a code
                result.merge(key, acc.result(g), GroupedAggregate::combine);
            }
        }
        return Optional.of(result);
    }

    /// Groups by a 64-bit key read per row: ids are assigned in a first pass through a primitive
    /// map, then the rows fold per id in a second, so no key is boxed per row.
    private static Optional<Map<Object, FilteredAggregate>> byKey(LongUnaryOperator keyOf, BoolArray validity,
            LongPredicate selected, Lane lane, long n, LongFunction<Object> boxKey) {
        int[] ids = new int[Math.toIntExact(n)];
        LongIntMap idOf = new LongIntMap(64);
        long[] keys = new long[16];
        int groups = 0;
        boolean nulls = false;
        for (int i = 0; i < n; i++) {
            if (selected != null && !selected.test(i)) {
                ids[i] = -1;
                continue;
            }
            if (validity != null && !validity.getBoolean(i)) {
                ids[i] = Integer.MAX_VALUE; // the null group, renumbered below
                nulls = true;
                continue;
            }
            long key = keyOf.applyAsLong(i);
            int id = idOf.get(key);
            if (id < 0) {
                if (groups == MAX_GROUPS) {
                    return Optional.empty();
                }
                id = groups++;
                idOf.put(key, id);
                if (id == keys.length) {
                    keys = Arrays.copyOf(keys, keys.length * 2);
                }
                keys[id] = key;
            }
            ids[i] = id;
        }
        // the null group takes the slot after the keyed groups
        Accumulators acc = new Accumulators(groups + 1, lane);
        for (int i = 0; i < n; i++) {
            int id = ids[i];
            if (id >= 0) {
                acc.add(id == Integer.MAX_VALUE ? groups : id, i);
            }
        }
        Map<Object, FilteredAggregate> result = new HashMap<>();
        for (int g = 0; g < groups; g++) {
            result.put(boxKey.apply(keys[g]), acc.result(g));
        }
        if (nulls) {
            result.put(null, acc.result(groups));
        }
        return Optional.of(result);
    }

    /// Folds two partial results of one group, as two dictionary entries with equal strings produce.
    private static FilteredAggregate combine(FilteredAggregate a, FilteredAggregate b) {
        Number sum;
        if (a.sum() == null) {
            sum = null;
        } else if (a.sum() instanceof Double x) {
            sum = x + b.sum().doubleValue();
        } else {
            sum = a.sum().longValue() + b.sum().longValue();
        }
        return new FilteredAggregate(a.selectedRows() + b.selectedRows(),
                a.aggNonNullCount() + b.aggNonNullCount(), sum,
                extreme(a.min(), b.min(), true), extreme(a.max(), b.max(), false));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object extreme(Object a, Object b, boolean min) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        int cmp = ((Comparable) a).compareTo(b);
        return (min ? cmp <= 0 : cmp >= 0) ? a : b;
    }

    /// How the aggregate column's values are read: not at all (a row count), as longs, or as doubles.
    private record Lane(Array data, BoolArray validity, boolean unsigned, boolean floating, boolean isFloat) {

        private static final Lane COUNT_ONLY = new Lane(null, null, false, false, false);

        /// Picks the lane for `column`, or `null` when no lane folds it: a non-numeric column, an
        /// `f16`, or a `u64` whose values past 2^63 have no signed-long home.
        static Lane of(Array column) {
            if (column == null) {
                return COUNT_ONLY;
            }
            Array data = NumericColumns.unwrap(column);
            BoolArray validity = NumericColumns.validityOf(column);
            if (data instanceof DoubleArray || data instanceof FloatArray) {
                return new Lane(data, validity, false, true, data instanceof FloatArray);
            }
            if (NumericColumns.isLongDomain(data)
                    && data.dtype() instanceof DType.Primitive p && p.ptype() != PType.U64) {
                return new Lane(data, validity, p.ptype().isUnsigned(), false, false);
            }
            return null;
        }
    }

    /// Per-group running totals, indexed by dictionary code.
    private static final class Accumulators {
        private final Lane lane;
        private final long[] rows;
        private final long[] nonNull;
        private final long[] longSum;
        private final long[] longMin;
        private final long[] longMax;
        private final double[] doubleSum;
        private final double[] doubleMin;
        private final double[] doubleMax;

        Accumulators(int size, Lane lane) {
            this.lane = lane;
            this.rows = new long[size];
            this.nonNull = new long[size];
            boolean longs = lane.data() != null && !lane.floating();
            boolean doubles = lane.data() != null && lane.floating();
            this.longSum = longs ? new long[size] : null;
            this.longMin = longs ? new long[size] : null;
            this.longMax = longs ? new long[size] : null;
            this.doubleSum = doubles ? new double[size] : null;
            this.doubleMin = doubles ? new double[size] : null;
            this.doubleMax = doubles ? new double[size] : null;
        }

        void add(int g, long i) {
            rows[g]++;
            Array data = lane.data();
            if (data == null || (lane.validity() != null && !lane.validity().getBoolean(i))) {
                return;
            }
            boolean first = nonNull[g]++ == 0;
            if (lane.floating()) {
                double v = lane.isFloat() ? ((FloatArray) data).getFloat(i) : ((DoubleArray) data).getDouble(i);
                doubleSum[g] += v;
                // Double.compare orders like Rust (-0.0 < 0.0, NaN the maximum), as the fused kernel does
                if (first || Double.compare(v, doubleMin[g]) < 0) {
                    doubleMin[g] = v;
                }
                if (first || Double.compare(v, doubleMax[g]) > 0) {
                    doubleMax[g] = v;
                }
            } else {
                long v = NumericColumns.widenLong(data, lane.unsigned(), i);
                longSum[g] += v;
                if (first || v < longMin[g]) {
                    longMin[g] = v;
                }
                if (first || v > longMax[g]) {
                    longMax[g] = v;
                }
            }
        }

        FilteredAggregate result(int g) {
            if (lane.data() == null) {
                return new FilteredAggregate(rows[g], 0L, null, null, null);
            }
            boolean any = nonNull[g] > 0;
            if (lane.floating()) {
                return new FilteredAggregate(rows[g], nonNull[g], doubleSum[g],
                        any ? doubleMin[g] : null, any ? doubleMax[g] : null);
            }
            return new FilteredAggregate(rows[g], nonNull[g], longSum[g],
                    any ? longMin[g] : null, any ? longMax[g] : null);
        }
    }
}
