package io.github.dfa1.vortex.reader.compute;

/// Predicate-lowering helpers for primitive numeric filter columns, shared by the fused
/// filter-and-aggregate kernels ([FusedFilterSum] / [FusedFilterAggregate]).
///
/// A comparison leaf over a primitive column lowers, once and outside the per-row loop (the hot-loop
/// rule's branch-split idiom), to a form the kernel tests with no boxing:
/// - long domain — an integer column read and widened to a `long`. Signedness is folded away with an
///   order-preserving XOR flip (`x ^ Long.MIN_VALUE` for unsigned columns, matching
///   [Long#compareUnsigned(long, long)]); every value leaf then lowers to a single inclusive
///   [LongRange] test on the flipped longs (with a `negate` flag for `Neq`).
/// - double domain — a floating column read and widened to a `double` and compared with
///   [Double#compare(double, double)] (see [#matchDouble(double, DoubleOp, double, double)]) so the
///   NaN / signed-zero ordering matches the generic per-element path exactly.
///
/// The lowering is the single source of truth for both domains, so the fused kernels can never lower a
/// predicate to bounds that drift from one another.
final class PrimitiveFilter {

    private PrimitiveFilter() {
    }

    /// Recursively checks that every comparison leaf holds a [Number] value, so each leaf unboxes to a
    /// primitive exactly as the generic path would.
    ///
    /// @param predicate the predicate to inspect
    /// @return `true` if every comparison leaf is numeric
    static boolean predicateOk(Predicate predicate) {
        return switch (predicate) {
            case Predicate.Eq eq -> eq.value() instanceof Number;
            case Predicate.Neq neq -> neq.value() instanceof Number;
            case Predicate.Lt lt -> lt.value() instanceof Number;
            case Predicate.Gt gt -> gt.value() instanceof Number;
            case Predicate.Lte lte -> lte.value() instanceof Number;
            case Predicate.Gte gte -> gte.value() instanceof Number;
            case Predicate.Between between -> between.lo() instanceof Number && between.hi() instanceof Number;
            case Predicate.IsNull ignored -> true;
            case Predicate.IsNotNull ignored -> true;
            case Predicate.And and -> predicateOk(and.left()) && predicateOk(and.right());
            case Predicate.Or or -> predicateOk(or.left()) && predicateOk(or.right());
        };
    }

    /// Lowers a long-domain comparison leaf to an inclusive range `[lo, hi]` on the order-preserving
    /// flipped longs, with a `negate` flag for the one leaf (Neq) that selects the complement of its
    /// range.
    ///
    /// @param predicate the comparison leaf (Eq / Neq / Lt / Lte / Gt / Gte / Between)
    /// @param flip      the order-preserving XOR flip (`Long.MIN_VALUE` unsigned, `0` signed)
    /// @return the inclusive range on the flipped longs and whether to select its complement
    static LongRange lowerLong(Predicate predicate, long flip) {
        // Every leaf lowers to a single inclusive range on the flipped longs; Neq is that same
        // range (the point [c, c]) tested negated, so the match is the complement of equality.
        return switch (predicate) {
            case Predicate.Eq eq -> {
                long c = ((Number) eq.value()).longValue() ^ flip;
                yield new LongRange(c, c, false);
            }
            case Predicate.Neq neq -> {
                long c = ((Number) neq.value()).longValue() ^ flip;
                yield new LongRange(c, c, true);
            }
            case Predicate.Lt lt -> {
                long c = ((Number) lt.value()).longValue() ^ flip;
                yield c == Long.MIN_VALUE
                        ? new LongRange(0L, -1L, false)
                        : new LongRange(Long.MIN_VALUE, c - 1, false);
            }
            case Predicate.Lte lte -> {
                // v <= c → inclusive [MIN, c]; no underflow guard needed (c is the upper bound).
                long c = ((Number) lte.value()).longValue() ^ flip;
                yield new LongRange(Long.MIN_VALUE, c, false);
            }
            case Predicate.Gt gt -> {
                long c = ((Number) gt.value()).longValue() ^ flip;
                yield c == Long.MAX_VALUE
                        ? new LongRange(0L, -1L, false)
                        : new LongRange(c + 1, Long.MAX_VALUE, false);
            }
            case Predicate.Gte gte -> {
                // v >= c → inclusive [c, MAX]; no overflow guard needed (c is the lower bound).
                long c = ((Number) gte.value()).longValue() ^ flip;
                yield new LongRange(c, Long.MAX_VALUE, false);
            }
            default -> {
                Predicate.Between between = (Predicate.Between) predicate;
                yield new LongRange(((Number) between.lo()).longValue() ^ flip,
                        ((Number) between.hi()).longValue() ^ flip, false);
            }
        };
    }

    /// An inclusive range `[lo, hi]` on the order-preserving flipped longs, with `negate` selecting
    /// the complement. A position matches when `lo <= v && v <= hi` differs from `negate`.
    ///
    /// @param lo     the inclusive lower bound in the flipped domain
    /// @param hi     the inclusive upper bound in the flipped domain (an empty range has `lo > hi`)
    /// @param negate `true` to select the complement of the range (Neq), `false` for a direct match
    record LongRange(long lo, long hi, boolean negate) {
    }

    /// Lowers a double-domain comparison leaf to its hoisted operator and the one or two bound values
    /// it compares against.
    ///
    /// @param predicate the comparison leaf (Eq / Neq / Lt / Lte / Gt / Gte / Between)
    /// @return the lowered operator and bounds (`hi` used only for [DoubleOp#BETWEEN])
    static DoubleBound lowerDouble(Predicate predicate) {
        return switch (predicate) {
            case Predicate.Eq eq -> new DoubleBound(DoubleOp.EQ, ((Number) eq.value()).doubleValue(), 0.0);
            case Predicate.Neq neq -> new DoubleBound(DoubleOp.NEQ, ((Number) neq.value()).doubleValue(), 0.0);
            case Predicate.Lt lt -> new DoubleBound(DoubleOp.LT, ((Number) lt.value()).doubleValue(), 0.0);
            case Predicate.Lte lte -> new DoubleBound(DoubleOp.LTE, ((Number) lte.value()).doubleValue(), 0.0);
            case Predicate.Gt gt -> new DoubleBound(DoubleOp.GT, ((Number) gt.value()).doubleValue(), 0.0);
            case Predicate.Gte gte -> new DoubleBound(DoubleOp.GTE, ((Number) gte.value()).doubleValue(), 0.0);
            default -> {
                Predicate.Between between = (Predicate.Between) predicate;
                yield new DoubleBound(DoubleOp.BETWEEN, ((Number) between.lo()).doubleValue(),
                        ((Number) between.hi()).doubleValue());
            }
        };
    }

    /// A lowered double-domain comparison: the hoisted operator and the value(s) it tests against.
    ///
    /// @param op the lowered operator
    /// @param lo the comparison value (and the inclusive lower bound for [DoubleOp#BETWEEN])
    /// @param hi the inclusive upper bound, used only for [DoubleOp#BETWEEN]
    record DoubleBound(DoubleOp op, double lo, double hi) {
    }

    /// Tests one double value against the lowered operator, mirroring the generic
    /// [Compare#values(Object, Object, io.github.dfa1.vortex.core.model.DType)] double branch's
    /// [Double#compare(double, double)] ordering.
    ///
    /// @param v  the value under test
    /// @param op the lowered operator
    /// @param lo the comparison value (and the inclusive lower bound for [DoubleOp#BETWEEN])
    /// @param hi the inclusive upper bound, used only for [DoubleOp#BETWEEN]
    /// @return `true` if `v` satisfies the operator
    static boolean matchDouble(double v, DoubleOp op, double lo, double hi) {
        return switch (op) {
            case EQ -> Double.compare(v, lo) == 0;
            case NEQ -> Double.compare(v, lo) != 0;
            case LT -> Double.compare(v, lo) < 0;
            case LTE -> Double.compare(v, lo) <= 0;
            case GT -> Double.compare(v, lo) > 0;
            case GTE -> Double.compare(v, lo) >= 0;
            case BETWEEN -> Double.compare(v, lo) >= 0 && Double.compare(v, hi) <= 0;
        };
    }

    /// The double-domain comparison operators a leaf lowers to, hoisted out of the per-row loop.
    enum DoubleOp {
        EQ, NEQ, LT, LTE, GT, GTE, BETWEEN
    }
}
