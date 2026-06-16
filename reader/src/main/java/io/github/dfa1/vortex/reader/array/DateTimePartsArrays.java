package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.VortexException;

/// Package-private helper for the {@link LazyDateTimePartsLongArray} record.
///
/// {@code days}, {@code seconds} and {@code subseconds} children can each be one of
/// the four signed-integer typed array interfaces; the writer picks the narrowest
/// ptype that fits. {@link #readLong(Array, long)} centralises the per-row read so
/// the record itself stays compact.
final class DateTimePartsArrays {

    private DateTimePartsArrays() {
    }

    /// Reads {@code arr[i]} as a signed long. Recurses through {@link MaskedArray};
    /// throws on null cells so callers don't silently get garbage for nullable
    /// columns.
    ///
    /// @param arr source typed Array
    /// @param i   row index
    /// @return cell value as long
    /// @throws VortexException for null cells or unsupported array types
    static long readLong(Array arr, long i) {
        return switch (arr) {
            case ByteArray a -> a.getByte(i);
            case ShortArray a -> a.getShort(i);
            case IntArray a -> a.getInt(i);
            case LongArray a -> a.getLong(i);
            case MaskedArray a -> {
                if (!a.isValid(i)) {
                    throw new VortexException("DateTimeParts: null cell at index " + i);
                }
                yield readLong(a.inner(), i);
            }
            default -> throw new VortexException(
                    "DateTimeParts: unsupported child array type: " + arr.getClass().getSimpleName());
        };
    }
}
