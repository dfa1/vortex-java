package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.error.VortexException;

import java.math.BigInteger;

/// Package-private helper for the [LazyDecimalBytePartsArray] record.
///
/// `vortex.decimal_byte_parts` with `lower_part_count == 0` stores the
/// decimal mantissa as a single signed-integer child column whose ptype the
/// encoder picks (one of `i8 / i16 / i32 / i64`). The child may be wrapped
/// in [MaskedArray] for nullable columns. [#readMantissa(Array, long)]
/// centralizes the per-row dispatch so the record itself stays compact.
final class DecimalBytePartsArrays {

    private DecimalBytePartsArrays() {
    }

    /// Reads `arr[i]` as a signed-magnitude [BigInteger] mantissa.
    /// Recurses through [MaskedArray]; throws on null cells so callers
    /// don't silently get a zero mantissa for invalid rows.
    ///
    /// @param arr source typed Array (must be one of Byte/Short/Int/Long, optionally MaskedArray-wrapped)
    /// @param i   row index
    /// @return cell value as a [BigInteger]
    /// @throws VortexException for null cells or unsupported array types
    static BigInteger readMantissa(Array arr, long i) {
        return BigInteger.valueOf(readMantissaLong(arr, i));
    }

    /// Reads the mantissa at `i` at the child's own width; every supported child fits a `long`.
    static long readMantissaLong(Array arr, long i) {
        return switch (arr) {
            case ByteArray a -> a.getByte(i);
            case ShortArray a -> a.getShort(i);
            case IntArray a -> a.getInt(i);
            case LongArray a -> a.getLong(i);
            case MaskedArray a -> {
                if (!a.isValid(i)) {
                    throw new VortexException("DecimalByteParts: null cell at index " + i);
                }
                yield readMantissaLong(a.inner(), i);
            }
            default -> throw new VortexException(
                    "DecimalByteParts: unsupported mantissa child type: " + arr.getClass().getSimpleName());
        };
    }
}
