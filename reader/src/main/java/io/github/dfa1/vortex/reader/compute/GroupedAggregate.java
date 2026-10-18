package io.github.dfa1.vortex.reader.compute;

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

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongPredicate;

/// Grouped aggregation over a dictionary-encoded string column: rows are accumulated per dictionary
/// code in primitive arrays, and each code is turned into its string once, when the per-group
/// results are built. No row is materialized and no key is hashed per row.
///
/// Only a [VarBinDictArray] group column (or a slice of one) qualifies, and only an aggregate column
/// the long or double lane can fold; anything else returns empty so the caller falls back to its
/// row path.
final class GroupedAggregate {

    /// Most dictionary entries accumulated per chunk. The dictionary size comes from an untrusted
    /// offsets buffer, so a larger one declines rather than size the per-code arrays from it.
    private static final int MAX_GROUPS = 1 << 16;

    private GroupedAggregate() {
    }

    /// Folds `aggColumn` per value of `groupColumn` over the rows `filter` selects.
    ///
    /// @param chunk       the decoded chunk holding every referenced column
    /// @param groupColumn the dictionary-encoded string column to group by
    /// @param filter      the rows to fold, or `null` for every row
    /// @param aggColumn   the numeric column to reduce, or `null` to count rows only
    /// @return the fold per group value (the `null` key is the null group), or empty when the group
    ///         or aggregate column has a shape this kernel does not fold
    static Optional<Map<String, FilteredAggregate>> aggregate(Chunk chunk, ColumnName groupColumn,
                                                              RowFilter filter, ColumnName aggColumn) {
        Array group = chunk.column(groupColumn);
        BoolArray groupValidity = NumericColumns.validityOf(group);
        VarBinDictArray dict;
        long offset;
        switch (NumericColumns.unwrap(group)) {
            case VarBinDictArray d -> {
                dict = d;
                offset = 0;
            }
            case VarBinSlicedArray(var _, var _, VarBinDictArray d, long sliceOffset) -> {
                dict = d;
                offset = sliceOffset;
            }
            default -> {
                return Optional.empty();
            }
        }
        VarBinOffsetArray values = dict.values();
        if (!(dict.dtype() instanceof DType.Utf8) || values.length() > MAX_GROUPS) {
            return Optional.empty();
        }
        Lane lane = Lane.of(aggColumn == null ? null : chunk.column(aggColumn));
        if (lane == null) {
            return Optional.empty();
        }
        int groups = (int) values.length();
        LongPredicate selected = filter == null ? null : FusedFilterAggregate.rowTest(chunk, filter);
        // one slot per dictionary entry, plus the null group at index `groups`
        Accumulators acc = new Accumulators(groups + 1, lane);
        long n = chunk.rowCount();
        for (long i = 0; i < n; i++) {
            if (selected != null && !selected.test(i)) {
                continue;
            }
            int g;
            if (groupValidity != null && !groupValidity.getBoolean(i)) {
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
        Map<String, FilteredAggregate> result = new HashMap<>();
        for (int g = 0; g <= groups; g++) {
            if (acc.rows[g] > 0) {
                String key = g == groups ? null : values.getString(g);
                // two entries may hold the same string; a group is a value, not a code
                result.merge(key, acc.result(g), GroupedAggregate::combine);
            }
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
