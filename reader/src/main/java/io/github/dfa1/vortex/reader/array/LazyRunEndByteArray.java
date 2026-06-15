package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.DType;

import java.util.function.LongBinaryOperator;

/// Lazy RunEnd-encoded {@link ByteArray}. See {@link LazyRunEndLongArray} for semantics.
///
/// @param dtype   logical element type
/// @param length  total logical row count
/// @param values  values per run
/// @param runEnds cumulative run-end positions (absolute, before {@code offset})
/// @param offset  starting absolute position
public record LazyRunEndByteArray(DType dtype, long length, ByteArray values, Array runEnds, long offset)
        implements ByteArray {

    @Override
    public byte getByte(long i) {
        int k = RunEndArrays.findRun(runEnds, values.length(), i + offset);
        return values.getByte(k);
    }

    @Override
    public int getInt(long i) {
        int k = RunEndArrays.findRun(runEnds, values.length(), i + offset);
        return values.getInt(k);
    }

    @Override
    public long fold(long identity, LongBinaryOperator op) {
        long[] acc = {identity};
        long numRuns = values.length();
        long startAbs = offset;
        long endAbs = offset + length;
        int run = RunEndArrays.findRun(runEnds, numRuns, startAbs);
        long emittedFrom = startAbs;
        while (emittedFrom < endAbs && run < numRuns) {
            long runEnd = RunEndArrays.readRunEnd(runEnds, run);
            long emitTo = Math.min(runEnd, endAbs);
            long count = emitTo - emittedFrom;
            long widened = values.getInt(run);
            for (long r = 0; r < count; r++) {
                acc[0] = op.applyAsLong(acc[0], widened);
            }
            emittedFrom = emitTo;
            run++;
        }
        return acc[0];
    }
}
