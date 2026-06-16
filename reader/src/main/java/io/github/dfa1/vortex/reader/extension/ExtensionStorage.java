package io.github.dfa1.vortex.reader.extension;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.ByteArray;
import io.github.dfa1.vortex.reader.array.IntArray;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.ShortArray;
import io.github.dfa1.vortex.encoding.TimeUnit;

import java.nio.ByteBuffer;
import java.time.Instant;

/// Low-level storage helpers for extension decode paths.
public final class ExtensionStorage {

    private ExtensionStorage() {
    }

    /// Reads a signed integer from any of the integer primitive arrays as `long`.
    /// Recurses through {@link MaskedArray}; throws on null cells so callers don't silently
    /// get garbage for nullable columns.
    ///
    /// @param storage signed-integer storage array
    /// @param i       row index
    /// @return cell value as long
    /// @throws VortexException if storage isn't an integer primitive or the cell is null
    public static long epochInteger(Array storage, long i) {
        return switch (storage) {
            case ByteArray a -> a.getByte(i);
            case ShortArray a -> a.getShort(i);
            case IntArray a -> a.getInt(i);
            case LongArray a -> a.getLong(i);
            case MaskedArray a -> {
                if (!a.isValid(i)) {
                    throw new VortexException("null cell at index " + i);
                }
                yield epochInteger(a.inner(), i);
            }
            default -> throw new VortexException(
                    "unsupported storage type " + storage.getClass().getSimpleName());
        };
    }

    /// Reads the {@link TimeUnit} metadata byte at the buffer's current position;
    /// throws if the buffer is null or empty.
    ///
    /// @param ext declared extension dtype
    /// @return decoded time unit
    /// @throws VortexException if the metadata is missing
    public static TimeUnit readUnit(DType.Extension ext) {
        ByteBuffer meta = ext.metadata();
        if (meta == null || !meta.hasRemaining()) {
            throw new VortexException("missing TimeUnit metadata byte for " + ext.extensionId());
        }
        return TimeUnit.fromTag(meta.get(meta.position()));
    }

    /// Constructs an {@link Instant} from a signed epoch count in the given unit.
    ///
    /// @param raw  epoch count
    /// @param unit time resolution; must not be {@link TimeUnit#Days}
    /// @return matching {@link Instant}
    /// @throws VortexException if `unit` is {@link TimeUnit#Days}
    public static Instant instantFromRaw(long raw, TimeUnit unit) {
        return switch (unit) {
            case Seconds -> Instant.ofEpochSecond(raw);
            case Milliseconds -> Instant.ofEpochMilli(raw);
            case Microseconds -> {
                long secs = Math.floorDiv(raw, 1_000_000L);
                long nanos = Math.floorMod(raw, 1_000_000L) * 1_000L;
                yield Instant.ofEpochSecond(secs, nanos);
            }
            case Nanoseconds -> {
                long secs = Math.floorDiv(raw, 1_000_000_000L);
                long nanos = Math.floorMod(raw, 1_000_000_000L);
                yield Instant.ofEpochSecond(secs, nanos);
            }
            case Days -> throw new VortexException("Days unit not valid for instant");
        };
    }

    /// Throws {@link IndexOutOfBoundsException} if `i` is outside `[0, length)`.
    ///
    /// @param i      row index to check
    /// @param length array length
    public static void checkBounds(long i, long length) {
        if (i < 0 || i >= length) {
            throw new IndexOutOfBoundsException("index " + i + " out of bounds for length " + length);
        }
    }
}
