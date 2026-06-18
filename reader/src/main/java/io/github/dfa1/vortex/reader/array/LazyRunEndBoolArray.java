package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.DType;

/// Lazy RunEnd-encoded [BoolArray]. `getBoolean(i) = values.getBoolean(findRun(i + offset))`.
/// `forEachBoolean` walks runs (one binary search at start, then per-run loops)
/// so sequential reads are O(numRuns) work plus length emissions.
///
/// @param dtype   logical Bool type
/// @param length  total logical row count
/// @param values  boolean values per run; length = `numRuns`
/// @param runEnds cumulative run-end positions (absolute, before `offset`);
///                length = `numRuns`; typed as [Array] because ends ptype varies
/// @param offset  starting absolute position; logical row `i` maps to absolute `i + offset`
public record LazyRunEndBoolArray(DType dtype, long length, BoolArray values, Array runEnds, long offset)
        implements BoolArray {

    @Override
    public boolean getBoolean(long i) {
        int k = RunEndArrays.findRun(runEnds, values.length(), i + offset);
        return values.getBoolean(k);
    }

    @Override
    public void forEachBoolean(BooleanConsumer c) {
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
                boolean v = values.getBoolean(run);
                for (long r = 0; r < count; r++) {
                    c.accept(v);
                }
            }
            emittedFrom = emitTo;
            run++;
        }
    }
}
