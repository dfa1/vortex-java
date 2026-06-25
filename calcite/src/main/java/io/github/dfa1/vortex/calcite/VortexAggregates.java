package io.github.dfa1.vortex.calcite;

import io.github.dfa1.vortex.reader.ArrayStats;
import io.github.dfa1.vortex.reader.Chunk;
import io.github.dfa1.vortex.reader.ScanIterator;
import io.github.dfa1.vortex.reader.ScanOptions;
import io.github.dfa1.vortex.reader.VortexReader;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.DoubleArray;
import io.github.dfa1.vortex.reader.array.FloatArray;
import io.github.dfa1.vortex.reader.array.IntArray;
import io.github.dfa1.vortex.reader.array.LongArray;

/// Column aggregates answered with the cheapest available source.
///
/// `MIN` / `MAX` / `COUNT` are read from the per-segment zone-map statistics embedded in the
/// file footer — no data segment is decoded. `SUM` (and therefore `AVG`) has no zone statistic
/// in the current writer, so it falls back to a streaming scan. This split is the whole point
/// of the demo: the stats-backed aggregates are effectively free, and `SUM`/`AVG` show what the
/// next writer increment (emit a per-zone `SUM`) would make free too (ADR 0013 §6).
public final class VortexAggregates {

    /// Where an aggregate's value came from.
    public enum Source {
        /// Read from zone-map statistics in the footer; no data decoded.
        ZONE_STATS_PUSHDOWN,
        /// Computed by streaming the column's data segments (no usable statistic).
        FULL_SCAN
    }

    /// A column's aggregate summary plus where each part was sourced.
    ///
    /// `sum` is a [Long] for integer columns (exact, matching SQL `SUM(BIGINT)` which also wraps
    /// at 2^63) and a [Double] for floating-point columns — never a `double` accumulation of
    /// integers, which would lose precision past 2^53.
    ///
    /// @param column      the column name
    /// @param min         minimum value (boxed `Double`/`Long`), or `null` if unknown
    /// @param max         maximum value, or `null` if unknown
    /// @param count       count of non-null rows
    /// @param sum         sum of values (`Long` for integer columns, `Double` for floats), or `null`
    /// @param avg         mean (`sum / count`), or `null` if not computed
    /// @param minMaxSource source of `min`/`max`/`count`
    /// @param sumSource    source of `sum`/`avg`
    public record Summary(String column, Object min, Object max, long count, Number sum, Double avg,
                          Source minMaxSource, Source sumSource) {
    }

    private VortexAggregates() {
    }

    /// Computes `MIN`/`MAX`/`COUNT`/`SUM`/`AVG` for a numeric column, pushing down what the zone
    /// statistics allow and scanning only for `SUM`/`AVG`.
    ///
    /// @param reader an open reader over the file
    /// @param column the numeric column name
    /// @return the column's aggregate summary
    public static Summary of(VortexReader reader, String column) {
        ArrayStats stats = reader.columnStats().getOrDefault(column, ArrayStats.empty());
        long totalRows = totalRows(reader);
        long nullCount = stats.nullCount() == null ? 0L : stats.nullCount();
        long count = totalRows - nullCount;

        // MIN/MAX/COUNT: straight from footer zone-map stats, no data segment touched.
        Object min = stats.min();
        Object max = stats.max();

        // SUM/AVG: no SUM zone-stat exists today, so stream the column once. Integer columns sum
        // into a long (exact); floating columns into a double.
        Number sum = scanSum(reader, column);
        Double avg = count == 0 ? null : sum.doubleValue() / count;

        return new Summary(column, min, max, count, sum, avg,
                Source.ZONE_STATS_PUSHDOWN, Source.FULL_SCAN);
    }

    private static long totalRows(VortexReader reader) {
        try (ScanIterator scan = reader.scan(ScanOptions.all())) {
            long total = 0L;
            for (long c : scan.chunkRowCounts()) {
                total += c;
            }
            return total;
        }
    }

    private static Number scanSum(VortexReader reader, String column) {
        long longSum = 0L;
        double doubleSum = 0.0;
        boolean isFloating = false;
        try (ScanIterator scan = reader.scan(ScanOptions.columns(column))) {
            while (scan.hasNext()) {
                try (Chunk chunk = scan.next()) {
                    long n = chunk.rowCount();
                    switch (chunk.<Array>column(column)) {
                        case LongArray a -> {
                            for (long i = 0; i < n; i++) {
                                longSum += a.getLong(i);
                            }
                        }
                        case IntArray a -> {
                            for (long i = 0; i < n; i++) {
                                longSum += a.getInt(i);
                            }
                        }
                        case DoubleArray a -> {
                            isFloating = true;
                            for (long i = 0; i < n; i++) {
                                doubleSum += a.getDouble(i);
                            }
                        }
                        case FloatArray a -> {
                            isFloating = true;
                            for (long i = 0; i < n; i++) {
                                doubleSum += a.getFloat(i);
                            }
                        }
                        default -> throw new IllegalArgumentException("not a numeric column: " + column);
                    }
                }
            }
        }
        // Return via if/else, not a ?: ternary: a numeric conditional unboxes both branches and
        // widens the Long to double, silently turning an integer-column sum into a Double.
        if (isFloating) {
            return doubleSum;
        }
        return longSum;
    }
}
