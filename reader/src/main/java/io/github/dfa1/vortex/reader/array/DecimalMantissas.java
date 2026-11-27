package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.error.VortexException;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.math.BigInteger;

import static io.github.dfa1.vortex.core.io.VortexFormat.LE_INT;
import static io.github.dfa1.vortex.core.io.VortexFormat.LE_LONG;
import static io.github.dfa1.vortex.core.io.VortexFormat.LE_SHORT;

/// Reads the signed little-endian mantissa a `vortex.decimal` buffer stores per row, at any of
/// Rust's decimal storage widths (i8 … i256).
final class DecimalMantissas {

    private DecimalMantissas() {
    }

    /// Reads the `width`-byte two's-complement integer at `offset`.
    ///
    /// @throws VortexException if `width` is not 1, 2, 4, 8, 16 or 32
    static BigInteger readSignedLe(MemorySegment buf, long offset, int width) {
        return switch (width) {
            case 1 -> BigInteger.valueOf(buf.get(ValueLayout.JAVA_BYTE, offset));
            case 2 -> BigInteger.valueOf(buf.get(LE_SHORT, offset));
            case 4 -> BigInteger.valueOf(buf.get(LE_INT, offset));
            case 8 -> BigInteger.valueOf(buf.get(LE_LONG, offset));
            case 16, 32 -> readWideSignedLe(buf, offset, width);
            default -> throw new VortexException("decimal: unsupported element width " + width);
        };
    }

    /// Writes row `row`'s unscaled value as a `width`-byte two's-complement integer at `at`, read
    /// through the accessors: not every decimal array has a buffer to copy from
    /// (`vortex.decimal_byte_parts` keeps its value in parts).
    static void writeSignedLe(DecimalArray a, long row, MemorySegment out, long at, int width) {
        switch (width) {
            case 1 -> out.set(ValueLayout.JAVA_BYTE, at, (byte) a.getUnscaledLong(row));
            case 2 -> out.set(LE_SHORT, at, (short) a.getUnscaledLong(row));
            case 4 -> out.set(LE_INT, at, (int) a.getUnscaledLong(row));
            case 8 -> out.set(LE_LONG, at, a.getUnscaledLong(row));
            default -> {
                // big-endian minimal bytes, reversed and sign-extended to the width
                byte[] be = a.getDecimal(row).unscaledValue().toByteArray();
                byte sign = (byte) (be[0] < 0 ? -1 : 0);
                for (int b = 0; b < width; b++) {
                    out.set(ValueLayout.JAVA_BYTE, at + b, b < be.length ? be[be.length - 1 - b] : sign);
                }
            }
        }
    }

    private static BigInteger readWideSignedLe(MemorySegment buf, long offset, int width) {
        // Two's-complement i128/i256 on disk in little-endian; BigInteger ingests big-endian.
        // No SIMD intrinsic for 16- or 32-byte signed integers, so we materialize into a heap
        // buffer here. Only fires for decimal(>18, _) — narrow-precision fast paths above
        // stay allocation-free.
        byte[] be = new byte[width];
        for (int k = 0; k < width; k++) {
            be[width - 1 - k] = buf.get(ValueLayout.JAVA_BYTE, offset + k);
        }
        return new BigInteger(be);
    }
}
