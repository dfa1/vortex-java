package io.github.dfa1.vortex.reader;

import java.util.List;

/// Predicate tree for zone-map pruning. Evaluated against per-chunk min/max statistics;
/// chunks where no row can satisfy the filter are skipped entirely.
public sealed interface RowFilter
        permits RowFilter.And, RowFilter.Eq, RowFilter.Neq,
                        RowFilter.Gt, RowFilter.Gte, RowFilter.Lt, RowFilter.Lte {

    static RowFilter and(RowFilter... filters) {
        return new And(List.of(filters));
    }

    static RowFilter eq(String col, Object val) {
        return new Eq(col, val);
    }

    static RowFilter neq(String col, Object val) {
        return new Neq(col, val);
    }

    static RowFilter gt(String col, Comparable<?> val) {
        return new Gt(col, val);
    }

    static RowFilter gte(String col, Comparable<?> val) {
        return new Gte(col, val);
    }

    static RowFilter lt(String col, Comparable<?> val) {
        return new Lt(col, val);
    }

    static RowFilter lte(String col, Comparable<?> val) {
        return new Lte(col, val);
    }

    default RowFilter and(RowFilter other) {
        return new And(List.of(this, other));
    }

    record And(List<RowFilter> filters) implements RowFilter {
    }

    record Eq(String column, Object value) implements RowFilter {
    }

    record Neq(String column, Object value) implements RowFilter {
    }

    record Gt(String column, Comparable<?> value) implements RowFilter {
    }

    record Gte(String column, Comparable<?> value) implements RowFilter {
    }

    record Lt(String column, Comparable<?> value) implements RowFilter {
    }

    record Lte(String column, Comparable<?> value) implements RowFilter {
    }
}
