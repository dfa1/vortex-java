package io.github.dfa1.vortex.calcite;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.reader.RowFilter;

import org.apache.calcite.jdbc.JavaTypeFactoryImpl;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexUtil;
import org.apache.calcite.sql.SqlKind;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/// Translates Calcite `RexNode` predicates into a zone-map [RowFilter], in two variants used by
/// [VortexTable]: a lenient translation for the scan path (an unrecognized predicate is simply
/// dropped, since Calcite still re-checks every row) and a strict translation for aggregate
/// push-down (a single untranslatable predicate abandons the whole rewrite, since answering from
/// stats is only sound when the [RowFilter] captures the predicate in full).
final class RexFilterTranslator {

    /// Used only to expand `SEARCH`/`Sarg` predicates (e.g. `BETWEEN`, `IN`) back into ordinary
    /// comparison trees this translation understands.
    private static final RexBuilder REX_BUILDER = new RexBuilder(new JavaTypeFactoryImpl());

    private RexFilterTranslator() {
    }

    /// Translates the Calcite predicates into a zone-map [RowFilter], keeping only the comparisons
    /// we can prune on (`=`, `<>`, `<`, `<=`, `>`, `>=` between a column and a literal, plus `AND`).
    /// Predicates we don't understand are simply not pushed — Calcite still applies them.
    ///
    /// @param filters the predicates Calcite pushed onto the scan
    /// @param names   the table's column names, by field index
    /// @param types   the table's column dtypes, by field index
    /// @return the conjoined filter capturing every translatable predicate, or empty if none is
    static Optional<RowFilter> toRowFilter(List<RexNode> filters, List<String> names, List<DType> types) {
        List<RowFilter> pushed = new ArrayList<>();
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
        return Optional.of(RowFilter.and(pushed.toArray(RowFilter[]::new)));
    }

    /// Translates the predicates a `WHERE` pushed onto the scan into one [RowFilter], **strictly**:
    /// returns empty if any predicate (or any conjunct of one) is not a column-vs-literal
    /// comparison or `AND` of them. The strictness is the correctness guard for aggregate
    /// push-down — answering from stats is only sound when the [RowFilter] captures the *whole*
    /// predicate, so a single untranslatable conjunct must abandon the rewrite rather than silently
    /// drop a filter (as [#toRowFilter] does for the scan path, where Calcite still re-checks
    /// every row).
    ///
    /// @param filters the predicates Calcite pushed onto the scan
    /// @param names   the table's column names, by field index
    /// @param types   the table's column dtypes, by field index
    /// @return the conjoined filter, or empty when any predicate is not fully translatable
    static Optional<RowFilter> translateStrict(List<RexNode> filters, List<String> names, List<DType> types) {
        List<RowFilter> translated = new ArrayList<>();
        for (RexNode node : filters) {
            RexNode expanded = RexUtil.expandSearch(REX_BUILDER, null, node);
            Optional<RowFilter> one = strictComparison(expanded, names, types);
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
        return Optional.of(RowFilter.and(translated.toArray(RowFilter[]::new)));
    }

    /// Collects every column the filter references into `out`, so the scan can include them for
    /// zone-map pruning regardless of projection.
    ///
    /// @param filter the filter tree to walk
    /// @param out    the set to add every referenced column name into
    static void collectColumns(RowFilter filter, Set<String> out) {
        switch (filter) {
            case RowFilter.And(var parts) -> parts.forEach(f -> collectColumns(f, out));
            case RowFilter.Column(var col, var _) -> out.add(col.value());
        }
    }

    /// Lenient translation for the scan path ([#toRowFilter]): an unrecognized node (or `AND`
    /// conjunct) is simply dropped, since the scan re-checks every row so a partially captured filter
    /// is still correct, just less selective for zone-map pruning. Delegates to the shared
    /// [#comparison] dispatch with `strict = false`.
    private static Optional<RowFilter> toComparison(RexNode node, List<String> names, List<DType> types) {
        return comparison(node, names, types, false);
    }

    /// Strict counterpart of [#toComparison]: the same column-vs-literal / `AND` grammar, but a
    /// single unrecognized node (or one `AND` conjunct) collapses the whole result to empty rather
    /// than being dropped, and bare `IS NULL` / `IS NOT NULL` are also translatable. Used by
    /// [#translateStrict] so aggregate push-down answers from stats only when the [RowFilter]
    /// captures the predicate in full. Delegates to the shared [#comparison] dispatch with
    /// `strict = true`.
    private static Optional<RowFilter> strictComparison(RexNode node, List<String> names, List<DType> types) {
        return comparison(node, names, types, true);
    }

    /// Shared comparison-kind dispatch behind [#toComparison] (lenient) and [#strictComparison]
    /// (strict). The two differ only in how an untranslatable node is handled and whether the null
    /// tests are accepted, both keyed off `strict`:
    /// - in an `AND`, a `strict` walk abandons the whole predicate on the first untranslatable
    ///   conjunct, while a lenient walk drops it and keeps the rest;
    /// - bare `IS NULL` / `IS NOT NULL` translate only in the `strict` walk (the lenient scan path has
    ///   no zone-map use for them and drops them).
    /// The column-vs-literal comparison kinds (`=`, `<>`, `<`, `<=`, `>`, `>=`) translate identically
    /// in both walks through [#binary].
    ///
    /// @param node   the (already `SEARCH`-expanded) predicate node
    /// @param names  the table's column names, by field index
    /// @param types  the table's column dtypes, by field index
    /// @param strict `true` to fail-closed on any untranslatable node and accept null tests, `false`
    ///               to drop untranslatable nodes
    /// @return the translated filter, or empty per the `strict` policy above
    private static Optional<RowFilter> comparison(RexNode node, List<String> names, List<DType> types,
                                                  boolean strict) {
        if (!(node instanceof RexCall call)) {
            return Optional.empty();
        }
        return switch (call.getKind()) {
            case AND -> {
                List<RowFilter> parts = new ArrayList<>();
                for (RexNode operand : call.getOperands()) {
                    Optional<RowFilter> part = comparison(operand, names, types, strict);
                    if (part.isPresent()) {
                        parts.add(part.get());
                    } else if (strict) {
                        yield Optional.empty(); // one untranslatable conjunct abandons the whole predicate
                    }
                }
                yield parts.isEmpty() ? Optional.empty()
                        : Optional.of(RowFilter.and(parts.toArray(RowFilter[]::new)));
            }
            case EQUALS, NOT_EQUALS, LESS_THAN, LESS_THAN_OR_EQUAL, GREATER_THAN, GREATER_THAN_OR_EQUAL ->
                    binary(call, names, types);
            case IS_NULL, IS_NOT_NULL -> strict ? nullCheck(call, names) : Optional.empty();
            default -> Optional.empty();
        };
    }

    /// Translates `col IS NULL` / `col IS NOT NULL` over a bare column reference into the matching
    /// [RowFilter], which the zone-map fold answers from each zone's null count. Anything other than
    /// a direct [RexInputRef] operand (e.g. `IS NULL` over an expression) abandons the translation —
    /// fail-closed, mirroring [#binary].
    private static Optional<RowFilter> nullCheck(RexCall call, List<String> names) {
        List<RexNode> ops = call.getOperands();
        if (ops.size() != 1 || !(ops.getFirst() instanceof RexInputRef ref)) {
            return Optional.empty();
        }
        String col = names.get(ref.getIndex());
        return Optional.of(call.getKind() == SqlKind.IS_NULL
                ? RowFilter.isNull(col)
                : RowFilter.isNotNull(col));
    }

    private static Optional<RowFilter> binary(RexCall call, List<String> names, List<DType> types) {
        List<RexNode> ops = call.getOperands();
        if (ops.size() != 2 || !(ops.get(0) instanceof RexInputRef ref) || !(ops.get(1) instanceof RexLiteral lit)) {
            return Optional.empty();
        }
        Object val = literalValue(lit, types.get(ref.getIndex()));
        if (val == null) {
            return Optional.empty();
        }
        String col = names.get(ref.getIndex());
        Comparable<?> cmp = (Comparable<?>) val;
        return Optional.of(switch (call.getKind()) {
            case EQUALS -> RowFilter.eq(col, val);
            case NOT_EQUALS -> RowFilter.neq(col, val);
            case LESS_THAN -> RowFilter.lt(col, cmp);
            case LESS_THAN_OR_EQUAL -> RowFilter.lte(col, cmp);
            case GREATER_THAN -> RowFilter.gt(col, cmp);
            case GREATER_THAN_OR_EQUAL -> RowFilter.gte(col, cmp);
            default -> throw new IllegalStateException("unreachable kind: " + call.getKind());
        });
    }

    /// Coerces a SQL literal to the Java type the column's zone-map statistics are stored as, so
    /// the comparison in [RowFilter] is type-compatible. Integer scalars are stored as `Long` and
    /// floats as `Double` in the stats, regardless of the column's physical width — a mismatched
    /// boxed type silently disables pruning (the comparator swallows the `ClassCastException`).
    /// Returns `null` for unsupported columns.
    private static Object literalValue(RexLiteral lit, DType type) {
        return switch (type) {
            case DType.Utf8 _ -> lit.getValueAs(String.class);
            case DType.Primitive p -> switch (p.ptype()) {
                case F64, F32 -> lit.getValueAs(Double.class);
                case I64, U64, I32, U32, I16, U16, I8, U8 -> lit.getValueAs(Long.class);
                default -> null;
            };
            default -> null;
        };
    }
}
