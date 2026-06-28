package io.github.dfa1.vortex.reader.compute;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.BoolArray;
import io.github.dfa1.vortex.reader.array.ByteArray;
import io.github.dfa1.vortex.reader.array.DoubleArray;
import io.github.dfa1.vortex.reader.array.FloatArray;
import io.github.dfa1.vortex.reader.array.IntArray;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.ShortArray;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import static io.github.dfa1.vortex.core.io.PTypeIO.LE_LONG;

/// The type-specialised fast lane of [StreamingFilterKernel]: for a primitive numeric column it runs
/// a monomorphic, boxing-free loop with the comparison value unboxed once outside the loop, instead
/// of the generic [Values#valueAt(Array, long)] / [Compare#values(Object, Object, DType)] path that
/// boxes every element to an [Object].
///
/// The ADR 0013 §3 tier-2 contract still holds — nothing is materialised, the only allocation is the
/// result bitmap from the caller's [Arena] — but the per-element decode is the typed accessor
/// (`getLong`, `getInt`, `getDouble`, …) read straight into a primitive. All type, signedness, and
/// predicate-operator decisions are hoisted out of the per-row loop (the hot-loop rule's
/// branch-split idiom); only types this class recognises take the fast lane, everything else returns
/// `null` so the caller falls back to the generic kernel with no behavioural drift.
///
/// Two value domains cover the numerics:
/// - long domain — [LongArray] / [IntArray] / [ShortArray] / [ByteArray], read and widened to a
///   `long`. Signedness is folded away with an order-preserving XOR flip (`x ^ Long.MIN_VALUE` for
///   unsigned columns, matching [Long#compareUnsigned(long, long)]); every value leaf then lowers to
///   a single inclusive range test on the flipped longs.
/// - double domain — [DoubleArray] / [FloatArray], read and widened to a `double` and compared with
///   [Double#compare(double, double)] so the NaN / signed-zero ordering is bit-identical to the
///   generic path.
final class PrimitiveFilter {

    private PrimitiveFilter() {
    }

    /// Filters `array` by `predicate` through the specialised primitive loops, or returns `null` if
    /// the array or predicate is not one this class specialises (the caller then uses the generic
    /// kernel).
    ///
    /// @param array     the array to filter, possibly a [MaskedArray] over a primitive child
    /// @param current   the incoming selection mask, applied once over the whole predicate result
    /// @param predicate the predicate to evaluate
    /// @param arena     the arena for the result bitmap; its zero-fill seeds the unselected bits to 0
    /// @return the selection mask, or `null` if the input is not specialisable
    static Mask tryFilter(Array array, Mask current, Predicate predicate, Arena arena) {
        Array data;
        BoolArray validity;
        if (array instanceof MaskedArray masked) {
            data = masked.inner();
            validity = masked.validity();
        } else {
            data = array;
            validity = null;
        }
        if (!canSpecialise(data, predicate)) {
            return null;
        }
        long n = array.length();
        MemorySegment bits = eval(data, validity, predicate, n, arena);
        // The incoming mask is positional selection independent of null semantics, so it is applied
        // once over the whole predicate result rather than per leaf.
        applyCurrent(bits, current, n);
        return new Mask.BitmapMask(bits, n);
    }

    /// Reports whether the concrete array and the full predicate tree can take the specialised path:
    /// the array must be a primitive long- or double-domain column and every comparison leaf must
    /// carry a [Number] value (so it unboxes to a primitive exactly as the generic path would).
    ///
    /// @param data      the unwrapped (non-masked) array
    /// @param predicate the predicate to inspect
    /// @return `true` if both the array and the predicate are specialisable
    private static boolean canSpecialise(Array data, Predicate predicate) {
        if (!(data.dtype() instanceof DType.Primitive)) {
            return false;
        }
        boolean primitive = data instanceof LongArray || data instanceof IntArray
                || data instanceof ShortArray || data instanceof ByteArray
                || data instanceof DoubleArray || data instanceof FloatArray;
        return primitive && predicateOk(predicate);
    }

    /// Recursively checks that every comparison leaf holds a [Number] value.
    ///
    /// @param predicate the predicate to inspect
    /// @return `true` if every comparison leaf is numeric
    private static boolean predicateOk(Predicate predicate) {
        return switch (predicate) {
            case Predicate.Eq eq -> eq.value() instanceof Number;
            case Predicate.Lt lt -> lt.value() instanceof Number;
            case Predicate.Gt gt -> gt.value() instanceof Number;
            case Predicate.Between between -> between.lo() instanceof Number && between.hi() instanceof Number;
            case Predicate.IsNull ignored -> true;
            case Predicate.IsNotNull ignored -> true;
            case Predicate.And and -> predicateOk(and.left()) && predicateOk(and.right());
            case Predicate.Or or -> predicateOk(or.left()) && predicateOk(or.right());
        };
    }

    /// Evaluates the predicate tree into a fresh bitmap that encodes null semantics (a null position
    /// is excluded from a value leaf) but not the incoming mask. Composites combine child bitmaps
    /// word-wise; leaves run the specialised value or validity loops.
    ///
    /// @param data      the unwrapped array
    /// @param validity  the validity bitmap, or `null` when every position is valid
    /// @param predicate the predicate to evaluate
    /// @param n         the row count
    /// @param arena     the arena for the bitmap allocations
    /// @return the predicate-result bitmap (null-aware, mask-independent)
    private static MemorySegment eval(Array data, BoolArray validity, Predicate predicate, long n, Arena arena) {
        return switch (predicate) {
            case Predicate.And and -> {
                MemorySegment left = eval(data, validity, and.left(), n, arena);
                MemorySegment right = eval(data, validity, and.right(), n, arena);
                andWords(left, right, n);
                yield left;
            }
            case Predicate.Or or -> {
                MemorySegment left = eval(data, validity, or.left(), n, arena);
                MemorySegment right = eval(data, validity, or.right(), n, arena);
                orWords(left, right, n);
                yield left;
            }
            case Predicate.IsNull ignored -> nullLeaf(validity, n, arena, true);
            case Predicate.IsNotNull ignored -> nullLeaf(validity, n, arena, false);
            default -> valueLeaf(data, validity, predicate, n, arena);
        };
    }

    /// Runs a value comparison leaf: the specialised match loop fills the bitmap, then any null
    /// positions are cleared (three-valued logic — a null never satisfies a value predicate).
    ///
    /// @param data      the unwrapped array
    /// @param validity  the validity bitmap, or `null` when every position is valid
    /// @param predicate the comparison leaf (Eq / Lt / Gt / Between)
    /// @param n         the row count
    /// @param arena     the arena for the bitmap
    /// @return the value-match bitmap with null positions cleared
    private static MemorySegment valueLeaf(Array data, BoolArray validity, Predicate predicate, long n, Arena arena) {
        MemorySegment bits = arena.allocate((n + 7) >>> 3);
        if (data instanceof DoubleArray || data instanceof FloatArray) {
            doubleLeaf(data, predicate, bits, n);
        } else {
            longLeaf(data, predicate, bits, n);
        }
        if (validity != null) {
            for (long i = 0; i < n; i++) {
                if (!validity.getBoolean(i)) {
                    clearBit(bits, i);
                }
            }
        }
        return bits;
    }

    /// Lowers a long-domain comparison leaf to an inclusive range on the order-preserving flipped
    /// longs, then dispatches the typed match loop once (no per-element type or sign branch).
    ///
    /// @param data      the unwrapped long-domain array
    /// @param predicate the comparison leaf
    /// @param bits      the output bitmap
    /// @param n         the row count
    private static void longLeaf(Array data, Predicate predicate, MemorySegment bits, long n) {
        boolean unsigned = data.dtype().isUnsigned();
        long flip = unsigned ? Long.MIN_VALUE : 0L;
        long lc;
        long hc;
        switch (predicate) {
            case Predicate.Eq eq -> {
                long c = ((Number) eq.value()).longValue() ^ flip;
                lc = c;
                hc = c;
            }
            case Predicate.Lt lt -> {
                long c = ((Number) lt.value()).longValue() ^ flip;
                if (c == Long.MIN_VALUE) {
                    lc = 0L;
                    hc = -1L;
                } else {
                    lc = Long.MIN_VALUE;
                    hc = c - 1;
                }
            }
            case Predicate.Gt gt -> {
                long c = ((Number) gt.value()).longValue() ^ flip;
                if (c == Long.MAX_VALUE) {
                    lc = 0L;
                    hc = -1L;
                } else {
                    lc = c + 1;
                    hc = Long.MAX_VALUE;
                }
            }
            default -> {
                Predicate.Between between = (Predicate.Between) predicate;
                lc = ((Number) between.lo()).longValue() ^ flip;
                hc = ((Number) between.hi()).longValue() ^ flip;
            }
        }
        dispatchLongRange(data, lc, hc, flip, unsigned, bits, n);
    }

    /// Dispatches the inclusive-range match to the typed monomorphic loop for the concrete array.
    ///
    /// @param data     the unwrapped long-domain array
    /// @param lc       the inclusive lower bound in the flipped domain
    /// @param hc       the inclusive upper bound in the flipped domain (an empty range has `lc > hc`)
    /// @param flip     the order-preserving XOR flip (`Long.MIN_VALUE` unsigned, `0` signed)
    /// @param unsigned whether the column is unsigned (narrow types zero-extend)
    /// @param bits     the output bitmap
    /// @param n        the row count
    private static void dispatchLongRange(Array data, long lc, long hc, long flip, boolean unsigned,
                                          MemorySegment bits, long n) {
        if (data instanceof LongArray la) {
            // No-box fast lane: read straight to long, fold sign with a hoisted XOR, single range test.
            for (long i = 0; i < n; i++) {
                long v = la.getLong(i) ^ flip;
                if (lc <= v && v <= hc) {
                    setBit(bits, i);
                }
            }
        } else if (data instanceof IntArray ia) {
            // Sign is hoisted (loop-invariant): zero-extend unsigned then flip, else sign-extend.
            if (unsigned) {
                for (long i = 0; i < n; i++) {
                    long v = (ia.getInt(i) & 0xFFFFFFFFL) ^ flip;
                    if (lc <= v && v <= hc) {
                        setBit(bits, i);
                    }
                }
            } else {
                for (long i = 0; i < n; i++) {
                    long v = ia.getInt(i);
                    if (lc <= v && v <= hc) {
                        setBit(bits, i);
                    }
                }
            }
        } else if (data instanceof ShortArray sa) {
            if (unsigned) {
                for (long i = 0; i < n; i++) {
                    long v = (sa.getShort(i) & 0xFFFFL) ^ flip;
                    if (lc <= v && v <= hc) {
                        setBit(bits, i);
                    }
                }
            } else {
                for (long i = 0; i < n; i++) {
                    long v = sa.getShort(i);
                    if (lc <= v && v <= hc) {
                        setBit(bits, i);
                    }
                }
            }
        } else {
            ByteArray ba = (ByteArray) data;
            if (unsigned) {
                for (long i = 0; i < n; i++) {
                    long v = (ba.getByte(i) & 0xFFL) ^ flip;
                    if (lc <= v && v <= hc) {
                        setBit(bits, i);
                    }
                }
            } else {
                for (long i = 0; i < n; i++) {
                    long v = ba.getByte(i);
                    if (lc <= v && v <= hc) {
                        setBit(bits, i);
                    }
                }
            }
        }
    }

    /// Runs a double-domain comparison leaf with [Double#compare(double, double)] so the NaN and
    /// signed-zero ordering matches the generic path exactly.
    ///
    /// @param data      the unwrapped double-domain array
    /// @param predicate the comparison leaf
    /// @param bits      the output bitmap
    /// @param n         the row count
    private static void doubleLeaf(Array data, Predicate predicate, MemorySegment bits, long n) {
        DoubleOp op;
        double lo;
        double hi = 0.0;
        switch (predicate) {
            case Predicate.Eq eq -> {
                op = DoubleOp.EQ;
                lo = ((Number) eq.value()).doubleValue();
            }
            case Predicate.Lt lt -> {
                op = DoubleOp.LT;
                lo = ((Number) lt.value()).doubleValue();
            }
            case Predicate.Gt gt -> {
                op = DoubleOp.GT;
                lo = ((Number) gt.value()).doubleValue();
            }
            default -> {
                Predicate.Between between = (Predicate.Between) predicate;
                op = DoubleOp.BETWEEN;
                lo = ((Number) between.lo()).doubleValue();
                hi = ((Number) between.hi()).doubleValue();
            }
        }
        // No-box fast lane: typed double read; the op is hoisted (loop-invariant) so C2 unswitches it.
        if (data instanceof DoubleArray da) {
            for (long i = 0; i < n; i++) {
                if (matchDouble(da.getDouble(i), op, lo, hi)) {
                    setBit(bits, i);
                }
            }
        } else {
            FloatArray fa = (FloatArray) data;
            for (long i = 0; i < n; i++) {
                if (matchDouble(fa.getFloat(i), op, lo, hi)) {
                    setBit(bits, i);
                }
            }
        }
    }

    /// Tests one double value against the lowered operator, mirroring the generic
    /// [Compare#values(Object, Object, DType)] double branch's [Double#compare(double, double)]
    /// ordering.
    ///
    /// @param v  the value under test
    /// @param op the lowered operator
    /// @param lo the comparison value (and the inclusive lower bound for [DoubleOp#BETWEEN])
    /// @param hi the inclusive upper bound, used only for [DoubleOp#BETWEEN]
    /// @return `true` if `v` satisfies the operator
    private static boolean matchDouble(double v, DoubleOp op, double lo, double hi) {
        return switch (op) {
            case EQ -> Double.compare(v, lo) == 0;
            case LT -> Double.compare(v, lo) < 0;
            case GT -> Double.compare(v, lo) > 0;
            case BETWEEN -> Double.compare(v, lo) >= 0 && Double.compare(v, hi) <= 0;
        };
    }

    /// Builds the bitmap for a null test: [Predicate.IsNull] selects invalid positions,
    /// [Predicate.IsNotNull] selects valid ones. With no validity bitmap every position is valid.
    ///
    /// @param validity the validity bitmap, or `null` when every position is valid
    /// @param n        the row count
    /// @param arena    the arena for the bitmap
    /// @param isNull   `true` for IS NULL, `false` for IS NOT NULL
    /// @return the null-test bitmap
    private static MemorySegment nullLeaf(BoolArray validity, long n, Arena arena, boolean isNull) {
        MemorySegment bits = arena.allocate((n + 7) >>> 3);
        if (validity == null) {
            if (!isNull) {
                for (long i = 0; i < n; i++) {
                    setBit(bits, i);
                }
            }
            return bits;
        }
        for (long i = 0; i < n; i++) {
            boolean valid = validity.getBoolean(i);
            if (isNull != valid) {
                setBit(bits, i);
            }
        }
        return bits;
    }

    /// Intersects the incoming mask into the predicate-result bitmap, clearing any position the mask
    /// rejects. An all-true mask is the common first-stage case and is skipped wholesale.
    ///
    /// @param bits    the predicate-result bitmap, mutated in place
    /// @param current the incoming selection mask
    /// @param n       the row count
    private static void applyCurrent(MemorySegment bits, Mask current, long n) {
        if (current instanceof Mask.AllTrue) {
            return;
        }
        for (long i = 0; i < n; i++) {
            if (!current.get(i)) {
                clearBit(bits, i);
            }
        }
    }

    /// Ands `right` into `left` word-wise; padding bits past the row count stay zero because every
    /// setter only ever writes positions in `[0, n)`.
    ///
    /// @param left  the destination bitmap, mutated in place
    /// @param right the source bitmap
    /// @param n     the row count
    private static void andWords(MemorySegment left, MemorySegment right, long n) {
        long bytes = (n + 7) >>> 3;
        long words = bytes >>> 3;
        for (long w = 0; w < words; w++) {
            long off = w << 3;
            left.set(LE_LONG, off, left.get(LE_LONG, off) & right.get(LE_LONG, off));
        }
        for (long b = words << 3; b < bytes; b++) {
            left.set(ValueLayout.JAVA_BYTE, b,
                    (byte) (left.get(ValueLayout.JAVA_BYTE, b) & right.get(ValueLayout.JAVA_BYTE, b)));
        }
    }

    /// Ors `right` into `left` word-wise.
    ///
    /// @param left  the destination bitmap, mutated in place
    /// @param right the source bitmap
    /// @param n     the row count
    private static void orWords(MemorySegment left, MemorySegment right, long n) {
        long bytes = (n + 7) >>> 3;
        long words = bytes >>> 3;
        for (long w = 0; w < words; w++) {
            long off = w << 3;
            left.set(LE_LONG, off, left.get(LE_LONG, off) | right.get(LE_LONG, off));
        }
        for (long b = words << 3; b < bytes; b++) {
            left.set(ValueLayout.JAVA_BYTE, b,
                    (byte) (left.get(ValueLayout.JAVA_BYTE, b) | right.get(ValueLayout.JAVA_BYTE, b)));
        }
    }

    /// Sets the validity bit for position `i` (LSB-first within each byte).
    ///
    /// @param bits the bitmap
    /// @param i    the zero-based position
    private static void setBit(MemorySegment bits, long i) {
        long byteIndex = i >>> 3;
        int existing = bits.get(ValueLayout.JAVA_BYTE, byteIndex) & 0xff;
        bits.set(ValueLayout.JAVA_BYTE, byteIndex, (byte) (existing | (1 << (i & 7))));
    }

    /// Clears the validity bit for position `i`.
    ///
    /// @param bits the bitmap
    /// @param i    the zero-based position
    private static void clearBit(MemorySegment bits, long i) {
        long byteIndex = i >>> 3;
        int existing = bits.get(ValueLayout.JAVA_BYTE, byteIndex) & 0xff;
        bits.set(ValueLayout.JAVA_BYTE, byteIndex, (byte) (existing & ~(1 << (i & 7))));
    }

    /// The double-domain comparison operators a leaf lowers to, hoisted out of the per-row loop.
    private enum DoubleOp {
        EQ, LT, GT, BETWEEN
    }
}
