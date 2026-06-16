package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.VortexException;

/// Package-private helpers shared by the `DictXxxArray` records.
///
/// Centralises the codes-type validation (so the four record `of`
/// factories agree on what counts as a valid codes array) and the scalar
/// `readCode` dispatch (so a single update fixes all four records).
final class DictArrays {

    private DictArrays() {
    }

    /// Validates that `codes` is one of the four supported narrow-int
    /// array types and that its length matches `expectedLength`.
    static void validateCodes(Array codes, long expectedLength) {
        if (!(codes instanceof ByteArray || codes instanceof ShortArray
                || codes instanceof IntArray || codes instanceof LongArray)) {
            throw new VortexException("Dict codes: unsupported codes array type: "
                    + codes.getClass().getSimpleName());
        }
        if (codes.length() != expectedLength) {
            throw new VortexException("Dict codes: length " + codes.length()
                    + " does not match expected " + expectedLength);
        }
    }

    /// Reads the code at row `i` from `codes`. One switch on the
    /// codes' Array type — JIT inline-caches on the stable concrete type.
    static long readCode(Array codes, long i) {
        return switch (codes) {
            case ByteArray ba -> Byte.toUnsignedLong(ba.getByte(i));
            case ShortArray sa -> Short.toUnsignedLong(sa.getShort(i));
            case IntArray ia -> Integer.toUnsignedLong(ia.getInt(i));
            case LongArray la -> la.getLong(i);
            default -> throw new VortexException("Dict codes: invalid codes type: "
                    + codes.getClass().getSimpleName());
        };
    }
}
