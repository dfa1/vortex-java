package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.DType;

import java.util.function.IntBinaryOperator;
import java.util.function.IntConsumer;

/// Lazy RunEnd-encoded [IntArray]. See [LazyRunEndLongArray] for semantics.
///
/// @param dtype   logical element type
/// @param length  total logical row count
/// @param values  values per run
/// @param runEnds cumulative run-end positions (absolute, before `offset`)
/// @param offset  starting absolute position
public record LazyRunEndIntArray(DType dtype, long length, IntArray values, Array runEnds, long offset)
        implements IntArray {

    @Override
    public int getInt(long i) {
        int k = RunEndArrays.findRun(runEnds, values.length(), i + offset);
        return values.getInt(k);
    }

    @Override
    public void forEachInt(IntConsumer c) {
        walkRuns((value, count) -> {
            for (long r = 0; r < count; r++) {
                c.accept(value);
            }
        });
    }

    @Override
    public int fold(int identity, IntBinaryOperator op) {
        int[] acc = {identity};
        walkRuns((value, count) -> {
            for (long r = 0; r < count; r++) {
                acc[0] = op.applyAsInt(acc[0], value);
            }
        });
        return acc[0];
    }

    private void walkRuns(RunConsumerInt consumer) {
        long numRuns = values.length();
        long startAbs = offset;
        long endAbs = offset + length;
        int run = RunEndArrays.findRun(runEnds, numRuns, startAbs);
        long emittedFrom = startAbs;
        while (emittedFrom < endAbs && run < numRuns) {
            long runEnd = RunEndArrays.readRunEnd(runEnds, run);
            long emitTo = Math.min(runEnd, endAbs);
            long count = emitTo - emittedFrom;
            if (count > 0) {
                consumer.accept(values.getInt(run), count);
            }
            emittedFrom = emitTo;
            run++;
        }
    }

    @FunctionalInterface
    private interface RunConsumerInt {
        void accept(int value, long count);
    }
}
