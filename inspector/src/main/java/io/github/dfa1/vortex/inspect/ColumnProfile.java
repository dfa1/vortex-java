package io.github.dfa1.vortex.inspect;

import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.reader.ArrayStats;
import io.github.dfa1.vortex.reader.ScanIterator;
import io.github.dfa1.vortex.reader.ScanOptions;
import io.github.dfa1.vortex.reader.VortexHandle;
import io.github.dfa1.vortex.reader.Zone;

import java.util.ArrayList;
import java.util.List;

/// One top-level column's statistics, folded from what the file already records - its zone map,
/// or each chunk's embedded stats when there is none. No data segment is decoded, so a profile
/// costs the same on a 10 GB file as on a 10 KB one, and over HTTP only the footer and the zone
/// tables are fetched.
///
/// Every aggregate is either exact or `null`: a column-wide value is reported only when every zone
/// that holds a value carries it, and min/max only when each zone's bound is exact (Rust records a
/// string's zone bounds truncated, so a utf8 column usually has no min/max here).
///
/// @param name      column name
/// @param type      column dtype
/// @param rows      rows in the file
/// @param nullCount null values in the column, or `null` if a zone does not record it
/// @param nanCount  `NaN` values in a float column, or `null` if unknown
/// @param min       the column's exact minimum, or `null` if unknown
/// @param max       the column's exact maximum, or `null` if unknown
/// @param sum       the column's sum (`Long` for integer and bool columns, `Double` for floats), or `null` if unknown
/// @param zones     the zone map in row order, empty when the column has none
public record ColumnProfile(
        String name,
        DType type,
        long rows,
        Long nullCount,
        Long nanCount,
        Object min,
        Object max,
        Object sum,
        List<Zone> zones) {

    /// Profiles every top-level column of the file.
    ///
    /// @param handle an open file
    /// @return one profile per schema field in order; empty when the root dtype is not a struct
    public static List<ColumnProfile> of(VortexHandle handle) {
        if (!(handle.dtype() instanceof DType.Struct schema)) {
            return List.of();
        }
        long rows = handle.layout().rowCount();
        List<ColumnProfile> profiles = new ArrayList<>(schema.fieldNames().size());
        try (ScanIterator iter = handle.scan(ScanOptions.all())) {
            for (int i = 0; i < schema.fieldNames().size(); i++) {
                ColumnName name = schema.fieldNames().get(i);
                ColumnProfile profile = fold(name.value(), schema.fieldTypes().get(i), rows,
                        iter.columnZoneStats(name), iter.columnZones(name));
                if (profile.min() == null || profile.max() == null || profile.sum() == null) {
                    profile = profile.orChunks(fold(name.value(), profile.type(), rows, iter.chunkStats(name), List.of()));
                }
                profiles.add(profile);
            }
        }
        return List.copyOf(profiles);
    }

    /// The mean of the non-null, non-`NaN` values: `sum / count`. For a bool column that is the
    /// share of `true`.
    ///
    /// @return the mean, or `null` when the sum or the null count is unknown, or no value is present
    public Double mean() {
        if (!(sum instanceof Number total) || nullCount == null) {
            return null;
        }
        long present = rows - nullCount - (nanCount == null ? 0 : nanCount);
        return present <= 0 ? null : total.doubleValue() / present;
    }

    /// Fills what the zone map left unknown from the chunks' own array stats: a `vortex.zoned` map
    /// records no sum (Rust's aggregate set), and only truncated bounds for a string, while each
    /// chunk's array stats carry both exactly. Null counts stay the zone map's.
    private ColumnProfile orChunks(ColumnProfile chunks) {
        return new ColumnProfile(name, type, rows, nullCount, nanCount,
                min != null ? min : chunks.min(),
                max != null ? max : chunks.max(),
                sum != null ? sum : chunks.sum(),
                zones);
    }

    static ColumnProfile fold(String name, DType type, long rows, List<ArrayStats> stats, List<Zone> zones) {
        Long nulls = 0L;
        Long nans = 0L;
        Object min = null;
        Object max = null;
        Object sum = null;
        boolean minKnown = true;
        boolean maxKnown = true;
        boolean sumKnown = true;
        for (ArrayStats zone : stats) {
            nulls = add(nulls, zone.nullCount());
            nans = add(nans, zone.nanCount());
            // Rust leaves min/max/sum absent on an all-null zone; that says nothing about the rest.
            if (zone.min() == null && zone.max() == null && zone.sum() == null && zone.nullCount() != null
                    && zone.nullCount() > 0) {
                continue;
            }
            minKnown &= zone.min() != null && zone.minExact();
            maxKnown &= zone.max() != null && zone.maxExact();
            sumKnown &= zone.sum() != null;
            if (minKnown) {
                min = VortexInspector.pickMin(min, zone.min());
            }
            if (maxKnown) {
                max = VortexInspector.pickMax(max, zone.max());
            }
            if (sumKnown) {
                sum = addSum(sum, zone.sum());
                sumKnown = sum != null;
            }
        }
        boolean any = !stats.isEmpty();
        return new ColumnProfile(name, type, rows,
                any ? nulls : null,
                any ? nans : null,
                any && minKnown ? min : null,
                any && maxKnown ? max : null,
                any && sumKnown ? sum : null,
                zones);
    }

    private static Long add(Long total, Long part) {
        return total == null || part == null ? null : total + part;
    }

    /// Integer sums stay exact and give up on overflow rather than wrap; anything else (float sums,
    /// mixed types) adds as `double`.
    private static Object addSum(Object total, Object part) {
        if (total == null) {
            return part;
        }
        if (total instanceof Long a && part instanceof Long b) {
            try {
                return Math.addExact(a, b);
            } catch (ArithmeticException _) {
                return null;
            }
        }
        if (total instanceof Number a && part instanceof Number b) {
            return a.doubleValue() + b.doubleValue();
        }
        return null;
    }
}
