package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.DType;

import java.util.function.LongBinaryOperator;
import java.util.function.LongConsumer;

/// Lazy RunEnd-encoded [LongArray]. `getLong(i) = values.getLong(findRun(i + offset))`.
/// `forEachLong` / `fold` walk runs (one binary search at start, then per-run loops)
/// so sequential reads are O(numRuns) work plus length emissions.
///
/// The `runEnds` array is typed as [Array] because the run-ends ptype varies with
/// the longest-run count — backed by one of [ByteArray], [ShortArray],
/// [IntArray], [LongArray].
///
/// @param dtype   logical element type
/// @param length  total logical row count
/// @param values  values per run; length = `numRuns`
/// @param runEnds cumulative run-end positions (absolute, before `offset`);
///                length = `numRuns`
/// @param offset  starting absolute position; logical row `i` maps to
///                absolute `i + offset`
public record LazyRunEndLongArray(DType dtype, long length, LongArray values, Array runEnds, long offset)
        implements LongArray {

    @Override
    public long getLong(long i) {
        int k = RunEndArrays.findRun(runEnds, values.length(), i + offset);
        return values.getLong(k);
    }

    @Override
    public void forEachLong(LongConsumer c) {
        walkRuns((value, count) -> {
            for (long r = 0; r < count; r++) {
                c.accept(value);
            }
        });
    }

    @Override
    public long fold(long identity, LongBinaryOperator op) {
        long[] acc = {identity};
        walkRuns((value, count) -> {
            for (long r = 0; r < count; r++) {
                acc[0] = op.applyAsLong(acc[0], value);
            }
        });
        return acc[0];
    }

    private void walkRuns(RunConsumerLong consumer) {
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
                consumer.accept(values.getLong(run), count);
            }
            emittedFrom = emitTo;
            run++;
        }
    }

    @FunctionalInterface
    private interface RunConsumerLong {
        void accept(long value, long count);
    }
}
