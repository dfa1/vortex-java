package io.github.dfa1.vortex.reader.compute;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.reader.array.Array;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Objects;

/// The streaming [FilterKernel] of ADR 0013 §3. A primitive numeric column takes the boxing-free,
/// type-specialised fast lane in [PrimitiveFilter]; every other [Array] falls back to the generic
/// tier-2 path here, which works for any array through the per-element boxing accessor (no
/// encoded-domain specialisation — that is the deferred performance escalation). The two paths are
/// behaviourally identical, so the fast lane is a pure performance choice gated by a type check.
///
/// The kernel honours the incoming mask (an excluded position is never evaluated) and mirrors the
/// Rust three-valued-logic filter semantics: a null value makes every value predicate false, so the
/// row is excluded; the dedicated null tests select on validity directly. The output is a
/// [Mask.BitmapMask] of `array.length()` bits, allocated off-heap from the caller's [Arena] — the
/// loop writes only the selected bits and leans on [Arena#allocate(long)] zero-filling the rest.
final class StreamingFilterKernel implements FilterKernel {

    @Override
    public Mask apply(Array array, Mask current, Predicate predicate, Arena arena) {
        Objects.requireNonNull(array, "array");
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(predicate, "predicate");
        Objects.requireNonNull(arena, "arena");
        long n = array.length();
        if (current.length() != n) {
            throw new VortexException("filter: mask length " + current.length()
                    + " != array length " + n);
        }
        if (n == 0 || current instanceof Mask.AllFalse) {
            // Nothing can be selected — return an allocation-free all-false of the same length.
            return Mask.allFalse(n);
        }
        // Fast lane: a primitive numeric column runs the boxing-free, type-specialised loops. A null
        // return means the input is not specialisable (Decimal, Utf8, an unhandled type, or a
        // non-numeric predicate value), so the generic per-element path below takes over.
        Mask specialised = PrimitiveFilter.tryFilter(array, current, predicate, arena);
        if (specialised != null) {
            return specialised;
        }
        return applyGeneric(array, current, predicate, arena, n);
    }

    /// Runs the generic boxing baseline directly, the oracle the specialised fast lane must match.
    /// Package-private so an equivalence test can assert `specialised == generic` against it.
    ///
    /// @param array     the array to filter
    /// @param current   the incoming selection mask, already validated to match `array`'s length
    /// @param predicate the predicate to evaluate
    /// @param arena     the arena for the result bitmap
    /// @return the selection mask the generic per-element path produces
    Mask applyGeneric(Array array, Mask current, Predicate predicate, Arena arena) {
        long n = array.length();
        if (n == 0 || current instanceof Mask.AllFalse) {
            return Mask.allFalse(n);
        }
        return applyGeneric(array, current, predicate, arena, n);
    }

    private Mask applyGeneric(Array array, Mask current, Predicate predicate, Arena arena, long n) {
        long bytes = (n + 7) >>> 3;
        // Arena.allocate zero-fills (FFM spec), so the unselected bits are already 0 — the loop
        // below only ever sets the selected bits.
        MemorySegment bits = arena.allocate(bytes);
        for (long i = 0; i < n; i++) {
            if (current.get(i) && evaluate(array, i, predicate)) {
                long byteIndex = i >>> 3;
                int existing = bits.get(ValueLayout.JAVA_BYTE, byteIndex) & 0xff;
                bits.set(ValueLayout.JAVA_BYTE, byteIndex, (byte) (existing | (1 << (i & 7))));
            }
        }
        return new Mask.BitmapMask(bits, n);
    }

    /// Evaluates `predicate` against the single value at position `i`, recursing through the boolean
    /// composites. Value predicates short-circuit to `false` on a null position (three-valued logic);
    /// the null tests read validity directly.
    ///
    /// @param array     the array under test
    /// @param i         the zero-based position
    /// @param predicate the predicate to evaluate
    /// @return `true` if the value at `i` satisfies `predicate`
    private static boolean evaluate(Array array, long i, Predicate predicate) {
        return switch (predicate) {
            case Predicate.Eq eq -> !Values.isNullAt(array, i)
                    && Compare.values(Values.valueAt(array, i), eq.value(), array.dtype()) == 0;
            case Predicate.Neq neq -> !Values.isNullAt(array, i)
                    && Compare.values(Values.valueAt(array, i), neq.value(), array.dtype()) != 0;
            case Predicate.Lt lt -> !Values.isNullAt(array, i)
                    && Compare.values(Values.valueAt(array, i), lt.value(), array.dtype()) < 0;
            case Predicate.Gt gt -> !Values.isNullAt(array, i)
                    && Compare.values(Values.valueAt(array, i), gt.value(), array.dtype()) > 0;
            case Predicate.Lte lte -> !Values.isNullAt(array, i)
                    && Compare.values(Values.valueAt(array, i), lte.value(), array.dtype()) <= 0;
            case Predicate.Gte gte -> !Values.isNullAt(array, i)
                    && Compare.values(Values.valueAt(array, i), gte.value(), array.dtype()) >= 0;
            case Predicate.Between between -> evaluateBetween(array, i, between);
            case Predicate.IsNull ignored -> Values.isNullAt(array, i);
            case Predicate.IsNotNull ignored -> !Values.isNullAt(array, i);
            case Predicate.And and -> evaluate(array, i, and.left()) && evaluate(array, i, and.right());
            case Predicate.Or or -> evaluate(array, i, or.left()) || evaluate(array, i, or.right());
        };
    }

    /// Tests the inclusive `[lo, hi]` range, reading the value once so the two bound compares share a
    /// single decode.
    ///
    /// @param array   the array under test
    /// @param i       the zero-based position
    /// @param between the range predicate
    /// @return `true` if the value at `i` lies within `[lo, hi]`
    private static boolean evaluateBetween(Array array, long i, Predicate.Between between) {
        if (Values.isNullAt(array, i)) {
            return false;
        }
        Object value = Values.valueAt(array, i);
        return Compare.values(value, between.lo(), array.dtype()) >= 0
                && Compare.values(value, between.hi(), array.dtype()) <= 0;
    }
}
