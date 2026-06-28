package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.model.DType;

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
        long n = length;
        for (long i = 0; i < n; i++) {
            c.accept(getLong(i));
        }
    }

    @Override
    public long fold(long identity, LongBinaryOperator op) {
        long acc = identity;
        long n = length;
        for (long i = 0; i < n; i++) {
            acc = op.applyAsLong(acc, getLong(i));
        }
        return acc;
    }
}
