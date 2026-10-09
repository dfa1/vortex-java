package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;

import java.util.function.LongConsumer;

/// Package-private helper for the [LazyDateTimePartsLongArray] record.
///
/// `days`, `seconds` and `subseconds` children can each be one of
/// the four integer-typed array interfaces (signed or unsigned); the writer picks
/// the narrowest ptype that fits the value range. [#readLong(Array, long)]
/// centralizes the per-row read so the record itself stays compact.
final class DateTimePartsArrays {

    private DateTimePartsArrays() {
    }

    /// Reads `arr[i]` as a widened long, respecting the child's signedness.
    ///
    /// Unsigned children (`U8`, `U16`, `U32`) require zero-extension, not sign-extension.
    /// The Rust writer encodes seconds-within-day (0–86 399) as `U16` when values fit;
    /// without zero-extension, values ≥ 32 768 are misread as negative shorts and produce
    /// a `2^16`-second offset in the reassembled timestamp (#252).
    ///
    /// Recurses through [MaskedArray] to its raw payload without inspecting validity:
    /// the decoder intersects component validities into the reassembled array's own mask
    /// (#235), so a null row's filler value here is harmless — callers gate on that outer mask.
    ///
    /// @param arr source typed Array
    /// @param i   row index
    /// @return cell value as long
    /// @throws VortexException for unsupported array types
    static long readLong(Array arr, long i) {
        return switch (arr) {
            case ByteArray a -> isUnsigned(a) ? Byte.toUnsignedLong(a.getByte(i)) : a.getByte(i);
            case ShortArray a -> isUnsigned(a) ? Short.toUnsignedLong(a.getShort(i)) : a.getShort(i);
            case IntArray a -> isUnsigned(a) ? Integer.toUnsignedLong(a.getInt(i)) : a.getInt(i);
            case LongArray a -> a.getLong(i);
            case MaskedArray a -> readLong(a.inner(), i);
            default -> throw new VortexException(
                    "DateTimeParts: unsupported child array type: " + arr.getClass().getSimpleName());
        };
    }

    /// Emits every element of `arr` widened to `long`, in order, through the child's own sequential
    /// `forEach`: one type switch per child rather than per row, and a run-end or sparse child walks
    /// its runs or patches instead of searching them per row.
    ///
    /// @param arr a [ByteArray], [ShortArray], [IntArray], [LongArray] or a [MaskedArray] over one
    /// @param c   receives each widened element
    static void forEachWidened(Array arr, LongConsumer c) {
        switch (arr) {
            case ByteArray a when isUnsigned(a) -> a.forEachByte(v -> c.accept(Byte.toUnsignedLong(v)));
            case ByteArray a -> a.forEachByte(c::accept);
            case ShortArray a when isUnsigned(a) -> a.forEachShort(v -> c.accept(Short.toUnsignedLong(v)));
            case ShortArray a -> a.forEachShort(c::accept);
            case IntArray a when isUnsigned(a) -> a.forEachInt(v -> c.accept(Integer.toUnsignedLong(v)));
            case IntArray a -> a.forEachInt(c::accept);
            case LongArray a -> a.forEachLong(c);
            case MaskedArray a -> forEachWidened(a.inner(), c);
            default -> throw new VortexException(
                    "DateTimeParts: unsupported child array type: " + arr.getClass().getSimpleName());
        }
    }

    private static boolean isUnsigned(Array a) {
        return a.dtype() instanceof DType.Primitive p
                && (p.ptype() == PType.U8 || p.ptype() == PType.U16 || p.ptype() == PType.U32);
    }
}
