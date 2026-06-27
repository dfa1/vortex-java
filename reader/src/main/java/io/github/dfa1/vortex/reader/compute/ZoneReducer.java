package io.github.dfa1.vortex.reader.compute;

import io.github.dfa1.vortex.reader.ArrayStats;
import io.github.dfa1.vortex.reader.ScanIterator;
import io.github.dfa1.vortex.reader.ScanOptions;
import io.github.dfa1.vortex.reader.VortexReader;

import java.util.List;
import java.util.Objects;

/// Answers column reductions from the per-zone statistics table, without decoding any data
/// segment.
///
/// This is the whole-zone tier of the aggregate push-down sketched in ADR 0013 §6: a reduction
/// folds the per-zone rows surfaced by [ScanIterator#columnZoneStats(String)] instead of streaming
/// the column. The boundary (residual) tier — partially-selected zones under a predicate — is a
/// later increment; today the reducer takes no predicate and folds every zone.
///
/// The reducer lives in `reader.compute` as the seam a future `vortex-compute` module extracts: it
/// depends only on the public reader scan API, so the move is mechanical once a second consumer
/// (the arrow bridge, a query façade) appears.
public final class ZoneReducer {

    private final VortexReader reader;

    /// Creates a reducer over an open reader.
    ///
    /// @param reader an open reader over the file; not closed by this reducer
    public ZoneReducer(VortexReader reader) {
        this.reader = Objects.requireNonNull(reader, "reader");
    }

    /// Folds the per-zone `SUM` statistics for `column` into the column total, or returns `null`
    /// when the reduction cannot be answered from the zone-map table — a column with no zone map,
    /// or a zone whose `SUM` was not retained (e.g. an overflowed zone) — so the caller streams the
    /// column instead.
    ///
    /// Integer columns fold into a [Long] (exact, wrapping at 2^63 like SQL `SUM(BIGINT)`); floating
    /// columns into a [Double]. The two are never mixed: a column is one dtype, and the zone-stat
    /// boxing already distinguishes them.
    ///
    /// @param column the numeric column name
    /// @return the column sum as a [Long] or [Double], or `null` if no zone carries a usable sum
    public Number sum(String column) {
        Objects.requireNonNull(column, "column");
        try (ScanIterator scan = reader.scan(ScanOptions.columns(column))) {
            List<ArrayStats> zones = scan.columnZoneStats(column);
            if (zones.isEmpty()) {
                return null;
            }
            long longSum = 0L;
            double doubleSum = 0.0;
            boolean isFloating = false;
            for (ArrayStats zone : zones) {
                switch (zone.sum()) {
                    case Long l -> longSum += l;
                    case Double d -> {
                        isFloating = true;
                        doubleSum += d;
                    }
                    case null, default -> {
                        // A zone with no usable sum (no zone map, or an unhandled stat type) — the
                        // whole reduction can't be pushed down, so signal a fall back to a scan.
                        return null;
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
}
