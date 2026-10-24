package io.github.dfa1.vortex.calcite;

import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.reader.ScanFilter;
import io.github.dfa1.vortex.reader.compute.Predicate;

import org.apache.calcite.jdbc.JavaTypeFactoryImpl;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexUtil;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.type.SqlTypeName;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/// Translates Calcite `RexNode` predicates into a zone-map [ScanFilter], in two variants used by
/// [VortexTable]: a lenient translation for the scan path (an unrecognized predicate is simply
/// dropped, since Calcite still re-checks every row) and a strict translation for aggregate
/// push-down (a single untranslatable predicate abandons the whole rewrite, since answering from
/// stats is only sound when the [ScanFilter] captures the predicate in full).
final class RexFilterTranslator {

    /// Used only to expand `SEARCH`/`Sarg` predicates (e.g. `BETWEEN`, `IN`) back into ordinary
    /// comparison trees this translation understands.
    private static final RexBuilder REX_BUILDER = new RexBuilder(new JavaTypeFactoryImpl());

    private RexFilterTranslator() {
    }

    /// Translates the Calcite predicates into a zone-map [ScanFilter], keeping only what we can prune
    /// on: `=`, `<>`, `<`, `<=`, `>`, `>=` between a column and a literal, `IS [NOT] NULL`, a
    /// prefix `LIKE 'abc%'`, a single-column `OR` (such as an `IN` list), and `AND`. Predicates we don't understand are
    /// simply not pushed — Calcite still applies them.
    ///
    /// @param filters the predicates Calcite pushed onto the scan
    /// @param names   the table's column names, by field index
    /// @param types   the table's column dtypes, by field index
    /// @return the conjoined filter capturing every translatable predicate, or empty if none is
    static Optional<ScanFilter> toScanFilter(List<RexNode> filters, List<ColumnName> names, List<DType> types) {
        List<ScanFilter> pushed = new ArrayList<>();
        for (RexNode node : filters) {
            // Calcite encodes BETWEEN / IN / range unions as SEARCH(ref, Sarg); expand back to a
            // comparison tree (>=, <=, AND, OR) before translating.
            RexNode expanded = RexUtil.expandSearch(REX_BUILDER, null, node);
            toComparison(expanded, names, types).ifPresent(pushed::add);
        }
        if (pushed.isEmpty()) {
            return Optional.empty();
        }
        if (pushed.size() == 1) {
            return Optional.of(pushed.getFirst());
        }
        return Optional.of(ScanFilter.and(pushed.toArray(ScanFilter[]::new)));
    }

    /// Translates the predicates a `WHERE` pushed onto the scan into one [ScanFilter], **strictly**:
    /// returns empty if any predicate (or any conjunct of one) is not a column-vs-literal
    /// comparison or `AND` of them. The strictness is the correctness guard for aggregate
    /// push-down — answering from stats is only sound when the [ScanFilter] captures the *whole*
    /// predicate, so a single untranslatable conjunct must abandon the rewrite rather than silently
    /// drop a filter (as [#toScanFilter] does for the scan path, where Calcite still re-checks
    /// every row).
    ///
    /// @param filters the predicates Calcite pushed onto the scan
    /// @param names   the table's column names, by field index
    /// @param types   the table's column dtypes, by field index
    /// @return the conjoined filter, or empty when any predicate is not fully translatable
    static Optional<ScanFilter> translateStrict(List<RexNode> filters, List<ColumnName> names, List<DType> types) {
        List<ScanFilter> translated = new ArrayList<>();
        for (RexNode node : filters) {
            RexNode expanded = RexUtil.expandSearch(REX_BUILDER, null, node);
            Optional<ScanFilter> one = strictComparison(expanded, names, types);
            if (one.isEmpty()) {
                return Optional.empty();
            }
            translated.add(one.get());
        }
        if (translated.isEmpty()) {
            return Optional.empty();
        }
        if (translated.size() == 1) {
            return Optional.of(translated.getFirst());
        }
        return Optional.of(ScanFilter.and(translated.toArray(ScanFilter[]::new)));
    }

    /// Collects every column the filter references into `out`, so the scan can include them for
    /// zone-map pruning regardless of projection.
    ///
    /// @param filter the filter tree to walk
    /// @param out    the set to add every referenced column name into
    static void collectColumns(ScanFilter filter, Set<ColumnName> out) {
        switch (filter) {
            case ScanFilter.And(var parts) -> parts.forEach(f -> collectColumns(f, out));
            case ScanFilter.Column(var col, _) -> out.add(col);
        }
    }

    /// Lenient translation for the scan path ([#toScanFilter]): an unrecognized node (or `AND`
    /// conjunct) is simply dropped, since the scan re-checks every row so a partially captured filter
    /// is still correct, just less selective for zone-map pruning. Delegates to the shared
    /// [#comparison] dispatch with `strict = false`.
    private static Optional<ScanFilter> toComparison(RexNode node, List<ColumnName> names, List<DType> types) {
        return comparison(node, names, types, false);
    }

    /// Strict counterpart of [#toComparison]: the same grammar, but a single unrecognized node (or
    /// one `AND` conjunct) collapses the whole result to empty rather than being dropped. Used by
    /// [#translateStrict] so aggregate push-down answers from stats only when the [ScanFilter]
    /// captures the predicate in full. Delegates to the shared [#comparison] dispatch with
    /// `strict = true`.
    private static Optional<ScanFilter> strictComparison(RexNode node, List<ColumnName> names, List<DType> types) {
        return comparison(node, names, types, true);
    }

    /// Shared comparison-kind dispatch behind [#toComparison] (lenient) and [#strictComparison]
    /// (strict). The two differ only in how an untranslatable `AND` conjunct is handled: a `strict`
    /// walk abandons the whole predicate on the first one, while a lenient walk drops it and keeps
    /// the rest. Comparisons (`=`, `<>`, `<`, `<=`, `>`, `>=`, either operand order) and the null
    /// tests translate identically in both walks.
    ///
    /// An `OR` translates only when every disjunct does and all of them test one column, into one
    /// column predicate (an `IN` list arrives this way once `SEARCH` is expanded). Dropping a
    /// disjunct would narrow the filter and lose rows, so an `OR` is all-or-nothing in both walks.
    ///
    /// @param node   the (already `SEARCH`-expanded) predicate node
    /// @param names  the table's column names, by field index
    /// @param types  the table's column dtypes, by field index
    /// @param strict `true` to fail-closed on any untranslatable node and accept null tests, `false`
    ///               to drop untranslatable nodes
    /// @return the translated filter, or empty per the `strict` policy above
    private static Optional<ScanFilter> comparison(RexNode node, List<ColumnName> names, List<DType> types,
                                                  boolean strict) {
        if (!(node instanceof RexCall call)) {
            return Optional.empty();
        }
        return switch (call.getKind()) {
            case AND -> {
                List<ScanFilter> parts = new ArrayList<>();
                for (RexNode operand : call.getOperands()) {
                    Optional<ScanFilter> part = comparison(operand, names, types, strict);
                    if (part.isPresent()) {
                        parts.add(part.get());
                    } else if (strict) {
                        yield Optional.empty(); // one untranslatable conjunct abandons the whole predicate
                    }
                }
                yield parts.isEmpty() ? Optional.empty()
                        : Optional.of(ScanFilter.and(parts.toArray(ScanFilter[]::new)));
            }
            case OR -> {
                ColumnName column = null;
                Predicate predicate = null;
                for (RexNode operand : call.getOperands()) {
                    Optional<ScanFilter.Column> part = comparison(operand, names, types, true)
                            .flatMap(RexFilterTranslator::singleColumn);
                    if (part.isEmpty() || column != null && !column.equals(part.get().column())) {
                        yield Optional.empty();
                    }
                    column = part.get().column();
                    predicate = predicate == null ? part.get().predicate()
                            : new Predicate.Or(predicate, part.get().predicate());
                }
                yield Optional.of(new ScanFilter.Column(column, predicate));
            }
            case EQUALS, NOT_EQUALS, LESS_THAN, LESS_THAN_OR_EQUAL, GREATER_THAN, GREATER_THAN_OR_EQUAL ->
                    binary(call, names, types);
            case IS_NULL, IS_NOT_NULL -> nullCheck(call, names);
            case LIKE -> like(call, names, types);
            default -> Optional.empty();
        };
    }

    /// The filter as one column's predicate: itself for a column filter, the conjunction of an `AND`
    /// whose parts all test the same column, else empty.
    private static Optional<ScanFilter.Column> singleColumn(ScanFilter filter) {
        return switch (filter) {
            case ScanFilter.Column column -> Optional.of(column);
            case ScanFilter.And(var parts) -> {
                ColumnName column = null;
                Predicate predicate = null;
                for (ScanFilter part : parts) {
                    Optional<ScanFilter.Column> one = singleColumn(part);
                    if (one.isEmpty() || column != null && !column.equals(one.get().column())) {
                        yield Optional.empty();
                    }
                    column = one.get().column();
                    predicate = predicate == null ? one.get().predicate()
                            : new Predicate.And(predicate, one.get().predicate());
                }
                yield Optional.of(new ScanFilter.Column(column, predicate));
            }
        };
    }

    /// Translates `col IS NULL` / `col IS NOT NULL` over a bare column reference into the matching
    /// [ScanFilter], which the zone-map fold answers from each zone's null count. Anything other than
    /// a direct [RexInputRef] operand (e.g. `IS NULL` over an expression) abandons the translation —
    /// fail-closed, mirroring [#binary].
    private static Optional<ScanFilter> nullCheck(RexCall call, List<ColumnName> names) {
        List<RexNode> ops = call.getOperands();
        if (ops.size() != 1 || !(ops.getFirst() instanceof RexInputRef ref)) {
            return Optional.empty();
        }
        ColumnName col = names.get(ref.getIndex());
        return Optional.of(call.getKind() == SqlKind.IS_NULL
                ? ScanFilter.isNull(col)
                : ScanFilter.isNotNull(col));
    }

    /// Translates `col LIKE 'prefix%'` on a string column into the range `col >= prefix AND col <
    /// successor(prefix)`, which holds exactly the strings starting with `prefix`: the successor
    /// raises the prefix's last code point by one, and code-point order is the UTF-8 byte order
    /// [ScanFilter] compares strings in. A pattern with any other wildcard (`_`, an inner `%`) or an
    /// `ESCAPE` clause is not translated; Calcite evaluates it. `LIKE '%'` keeps every non-null row.
    private static Optional<ScanFilter> like(RexCall call, List<ColumnName> names, List<DType> types) {
        List<RexNode> ops = call.getOperands();
        if (ops.size() != 2 || !(ops.get(0) instanceof RexInputRef ref) || !(ops.get(1) instanceof RexLiteral lit)
                || !(types.get(ref.getIndex()) instanceof DType.Utf8)) {
            return Optional.empty();
        }
        String pattern = lit.getValueAs(String.class);
        if (pattern == null || !pattern.endsWith("%")) {
            return Optional.empty();
        }
        String prefix = pattern.substring(0, pattern.length() - 1);
        if (prefix.indexOf('%') >= 0 || prefix.indexOf('_') >= 0) {
            return Optional.empty();
        }
        ColumnName col = names.get(ref.getIndex());
        if (prefix.isEmpty()) {
            return Optional.of(ScanFilter.isNotNull(col));
        }
        return Optional.of(successor(prefix)
                .map(upper -> ScanFilter.and(ScanFilter.gte(col, prefix), ScanFilter.lt(col, upper)))
                .orElseGet(() -> ScanFilter.gte(col, prefix)));
    }

    /// The least string greater than every string starting with `prefix`: its last code point raised
    /// by one, skipping the surrogate block; a trailing U+10FFFF has no successor and is dropped
    /// first. Empty when every code point is U+10FFFF.
    static Optional<String> successor(String prefix) {
        int[] codePoints = prefix.codePoints().toArray();
        for (int end = codePoints.length - 1; end >= 0; end--) {
            int next = codePoints[end] + 1;
            if (next == Character.MIN_SURROGATE) {
                next = Character.MAX_SURROGATE + 1;
            }
            if (next <= Character.MAX_CODE_POINT) {
                codePoints[end] = next;
                return Optional.of(new String(codePoints, 0, end + 1));
            }
        }
        return Optional.empty();
    }

    private static Optional<ScanFilter> binary(RexCall call, List<ColumnName> names, List<DType> types) {
        List<RexNode> ops = call.getOperands();
        if (ops.size() != 2) {
            return Optional.empty();
        }
        SqlKind kind = call.getKind();
        RexInputRef ref;
        RexLiteral lit;
        if (columnRef(ops.get(0)) instanceof RexInputRef r && ops.get(1) instanceof RexLiteral l) {
            ref = r;
            lit = l;
        } else if (ops.get(0) instanceof RexLiteral l && columnRef(ops.get(1)) instanceof RexInputRef r) {
            // `5 < x` is `x > 5`
            ref = r;
            lit = l;
            kind = kind.reverse();
        } else {
            return Optional.empty();
        }
        Object val = literalValue(lit, types.get(ref.getIndex()));
        if (val == null) {
            return Optional.empty();
        }
        ColumnName col = names.get(ref.getIndex());
        Comparable<?> cmp = (Comparable<?>) val;
        return Optional.of(switch (kind) {
            case EQUALS -> ScanFilter.eq(col, val);
            case NOT_EQUALS -> ScanFilter.neq(col, val);
            case LESS_THAN -> ScanFilter.lt(col, cmp);
            case LESS_THAN_OR_EQUAL -> ScanFilter.lte(col, cmp);
            case GREATER_THAN -> ScanFilter.gt(col, cmp);
            case GREATER_THAN_OR_EQUAL -> ScanFilter.gte(col, cmp);
            default -> throw new IllegalStateException("unreachable kind: " + kind);
        });
    }

    /// The column a comparison operand reads: a bare reference, or one under a `CAST` that widens it
    /// without loss, which Calcite adds when a narrow column meets a wider literal
    /// (`order_serverId < 5` arrives as `CAST($5):INTEGER < 5` for a `SMALLINT` column). Comparing
    /// the column itself is then the same comparison. `null` for anything else, including a `CAST`
    /// that can change a value (`BIGINT` to `DOUBLE` rounds past 2^53).
    private static RexInputRef columnRef(RexNode node) {
        if (node instanceof RexInputRef ref) {
            return ref;
        }
        if (node instanceof RexCall cast && cast.getKind() == SqlKind.CAST
                && cast.getOperands().getFirst() instanceof RexInputRef ref
                && widens(ref.getType().getSqlTypeName(), cast.getType().getSqlTypeName())) {
            return ref;
        }
        return null;
    }

    private static boolean widens(SqlTypeName from, SqlTypeName to) {
        int fromRank = integerRank(from);
        int toRank = integerRank(to);
        if (fromRank > 0 && toRank > 0) {
            return toRank >= fromRank;
        }
        return (from == SqlTypeName.REAL || from == SqlTypeName.FLOAT) && to == SqlTypeName.DOUBLE;
    }

    private static int integerRank(SqlTypeName type) {
        return switch (type) {
            case TINYINT -> 1;
            case SMALLINT -> 2;
            case INTEGER -> 3;
            case BIGINT -> 4;
            default -> 0;
        };
    }

    /// Coerces a SQL literal to the Java type the column's zone-map statistics are stored as, so
    /// the comparison in [ScanFilter] is type-compatible. Integer scalars are stored as `Long` and
    /// floats as `Double` in the stats, regardless of the column's physical width — a mismatched
    /// boxed type silently disables pruning (the comparator swallows the `ClassCastException`).
    /// Returns `null` for unsupported columns.
    private static Object literalValue(RexLiteral lit, DType type) {
        return switch (type) {
            case DType.Utf8 _ -> lit.getValueAs(String.class);
            case DType.Primitive p -> switch (p.ptype()) {
                case F64, F32, F16 -> lit.getValueAs(Double.class);
                case I64, U64, I32, U32, I16, U16, I8, U8 -> lit.getValueAs(Long.class);
            };
            default -> null;
        };
    }
}
