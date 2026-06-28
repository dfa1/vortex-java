package io.github.dfa1.vortex.reader.compute;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.BoolArray;
import io.github.dfa1.vortex.reader.array.ByteArray;
import io.github.dfa1.vortex.reader.array.DecimalArray;
import io.github.dfa1.vortex.reader.array.DoubleArray;
import io.github.dfa1.vortex.reader.array.FloatArray;
import io.github.dfa1.vortex.reader.array.IntArray;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.ShortArray;
import io.github.dfa1.vortex.reader.array.VarBinArray;

/// The single generic value-access path shared by every streaming kernel: one element read and one
/// null test that work for *any* [Array] via its typed accessor, the matrix-explosion mitigation
/// ADR 0013 prescribes (one generic path over the encodings, specialize the hot ones later).
///
/// Reads decode through the per-type accessor a single element at a time — the tier-2 streaming
/// contract of ADR 0013 §3. No method here ever calls [Array#materialize(java.lang.foreign.SegmentAllocator)]:
/// a full-buffer materialize is the forbidden tier-3 last resort and the kernels do not need it.
final class Values {

    private Values() {
    }

    /// Reports whether the value at position `i` is null.
    ///
    /// Only a [MaskedArray] carries nulls; any other array is non-nullable, so every position is
    /// present. A masked array delegates to [MaskedArray#isValid(long)].
    ///
    /// @param array the array to test
    /// @param i     the zero-based position
    /// @return `true` if the value at `i` is null
    static boolean isNullAt(Array array, long i) {
        return array instanceof MaskedArray masked && !masked.isValid(i);
    }

    /// Reads the value at position `i` as a boxed scalar through the array's typed accessor.
    ///
    /// A [MaskedArray] is unwrapped to its inner payload first — validity is a separate concern
    /// handled by [#isNullAt(Array, long)]. Integer values are returned as zero-extended [Long]s for
    /// unsigned columns so a narrow unsigned value never reads back as a negative number; floating
    /// values keep their natural [Float] / [Double] box, decimals a [java.math.BigDecimal], and
    /// var-binary columns a [String].
    ///
    /// @param array the array to read from
    /// @param i     the zero-based position
    /// @return the boxed value at `i`
    /// @throws VortexException if the array's type has no scalar accessor for compute
    static Object valueAt(Array array, long i) {
        Array data = array instanceof MaskedArray masked ? masked.inner() : array;
        // NOTE: this boxing path is the correctness baseline of ADR 0013 §3 tier 2 — one generic
        // accessor read that works for every encoding. It deliberately violates the hot-loop
        // "no per-element boxing" ideal; encoded-domain specialization of the hot encodings (ALP,
        // FoR, BitPacked, Dict) is the deferred performance escalation, not part of this step.
        return switch (data) {
            case LongArray la -> la.getLong(i);
            case IntArray ia -> widenInteger(ia.getInt(i), data.dtype());
            case ShortArray sa -> widenInteger(sa.getShort(i), data.dtype());
            case ByteArray ba -> widenInteger(ba.getByte(i), data.dtype());
            case DoubleArray da -> da.getDouble(i);
            case FloatArray fa -> fa.getFloat(i);
            case BoolArray bo -> bo.getBoolean(i);
            case DecimalArray de -> de.getDecimal(i);
            case VarBinArray vb -> vb.getString(i);
            default -> throw new VortexException(
                    "compute: no scalar accessor for array type " + data.getClass().getSimpleName());
        };
    }

    /// Boxes an integer value, zero-extending the low bits of an unsigned column so the result
    /// reflects the unsigned magnitude rather than a sign-extended negative.
    ///
    /// @param raw   the value already widened to `long` by the typed accessor (sign-extended)
    /// @param dtype the column dtype that decides whether to zero-extend
    /// @return the boxed value as a [Long]
    private static Long widenInteger(long raw, DType dtype) {
        if (dtype instanceof DType.Primitive prim) {
            return switch (prim.ptype()) {
                case U8 -> raw & 0xFFL;
                case U16 -> raw & 0xFFFFL;
                case U32 -> raw & 0xFFFFFFFFL;
                default -> raw;
            };
        }
        return raw;
    }
}
