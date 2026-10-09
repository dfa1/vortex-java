package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.io.VortexFormat;
import io.github.dfa1.vortex.core.model.DType;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.util.function.LongBinaryOperator;
import java.util.function.LongConsumer;

/// Lazy `vortex.datetimeparts` reassembly as a [LongArray].
///
/// The encoding splits each raw epoch count into three children — `days`,
/// `seconds` (within the day) and `subseconds` (within the second).
/// Reconstruction is
///
/// ```
/// raw = days * unitsPerDay + seconds * unitsPerSecond + subseconds
/// ```
///
/// where `unitsPerSecond` = `TimeUnit.divisor()` and
/// `unitsPerDay` = `86_400 * unitsPerSecond`. The reassembled long carries the
/// same epoch count the downstream extension decoder
/// (`TimestampExtensionDecoder`, `DateExtensionDecoder`, etc.) expects;
/// no buffer materialization occurs at construction time.
///
/// The record's [#dtype()] is the parent Extension dtype (e.g.
/// `vortex.timestamp`) so it slots transparently into the extension-decode
/// pipeline. Children may be any signed integer typed Array
/// ([ByteArray]/[ShortArray]/[IntArray]/[LongArray]); the
/// per-row [DateTimePartsArrays#readLong] switch handles widening.
///
/// @param dtype            logical element type (typically a `DType.Extension`)
/// @param length           total logical row count
/// @param daysArr          per-row signed days
/// @param secondsArr       per-row signed seconds within the day
/// @param subsecondsArr    per-row signed sub-second count
/// @param unitsPerDay      multiplier for the days component (= 86_400 × unitsPerSecond)
/// @param unitsPerSecond   multiplier for the seconds component (= unit divisor)
public record LazyDateTimePartsLongArray(
        DType dtype, long length,
        Array daysArr, Array secondsArr, Array subsecondsArr,
        long unitsPerDay, long unitsPerSecond)
        implements LongArray {

    @Override
    public long getLong(long i) {
        return DateTimePartsArrays.readLong(daysArr, i) * unitsPerDay
                + DateTimePartsArrays.readLong(secondsArr, i) * unitsPerSecond
                + DateTimePartsArrays.readLong(subsecondsArr, i);
    }

    @Override
    public void forEachLong(LongConsumer c) {
        if (!childrenAligned()) {
            for (long i = 0; i < length; i++) {
                c.accept(getLong(i));
            }
            return;
        }
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment values = materialize(scratch);
            for (long i = 0; i < length; i++) {
                c.accept(values.getAtIndex(VortexFormat.LE_LONG, i));
            }
        }
    }

    @Override
    public long fold(long identity, LongBinaryOperator op) {
        long[] acc = {identity};
        forEachLong(v -> acc[0] = op.applyAsLong(acc[0], v));
        return acc[0];
    }

    /// Reassembles column-wise: one sequential pass per child, each through the child's own
    /// `forEach`, accumulating into the output. Per-row [#getLong(long)] instead re-resolved every
    /// child position from scratch (a binary search per row for a run-end child), which dominated a
    /// full scan of real timestamp columns.
    ///
    /// @param arena allocator for the output segment
    /// @return a little-endian `i64` segment of reassembled epoch counts
    @Override
    public MemorySegment materialize(SegmentAllocator arena) {
        if (!childrenAligned()) {
            return LongArray.super.materialize(arena);
        }
        MemorySegment dst = arena.allocate(length * 8L, 8);
        long[] at = {0};
        DateTimePartsArrays.forEachWidened(daysArr,
                d -> dst.setAtIndex(VortexFormat.LE_LONG, at[0]++, d * unitsPerDay));
        at[0] = 0;
        DateTimePartsArrays.forEachWidened(secondsArr, s -> {
            long k = at[0]++;
            dst.setAtIndex(VortexFormat.LE_LONG, k, dst.getAtIndex(VortexFormat.LE_LONG, k) + s * unitsPerSecond);
        });
        at[0] = 0;
        DateTimePartsArrays.forEachWidened(subsecondsArr, s -> {
            long k = at[0]++;
            dst.setAtIndex(VortexFormat.LE_LONG, k, dst.getAtIndex(VortexFormat.LE_LONG, k) + s);
        });
        return dst;
    }

    // A full child walk emits child.length() values, so it is only safe into a `length`-row buffer
    // when every child is exactly that long; a malformed file falls back to the per-row path.
    private boolean childrenAligned() {
        return daysArr.length() == length && secondsArr.length() == length && subsecondsArr.length() == length;
    }
}
