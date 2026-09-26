package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.model.DType;

import java.util.function.LongBinaryOperator;

/// Lazy RunEnd-encoded [ShortArray]. See [LazyRunEndLongArray] for semantics.
///
/// @param dtype   logical element type
/// @param length  total logical row count
/// @param values  values per run
/// @param runEnds cumulative run-end positions (absolute, before `offset`)
/// @param offset  starting absolute position
public record LazyRunEndShortArray(DType dtype, long length, ShortArray values, Array runEnds, long offset)
        implements ShortArray {

    @Override
    public short getShort(long i) {
        int k = RunEndArrays.findRun(runEnds, values.length(), i + offset);
        return values.getShort(k);
    }

    @Override
    public int getInt(long i) {
        return RunEndArrays.runInt(runEnds, values.length(), i + offset, values::getInt);
    }

    /// Emits each run's value `count` times instead of binary-searching per element. Without this
    /// the interface default walks by index, and every `getShort` runs a fresh [RunEndArrays#findRun]
    /// binary search — the dominant cost of a sequential scan over a run-end column. The Int, Long
    /// and Bool run-end records already had their typed forEach; Short and Byte did not.
    ///
    /// @param c consumer that receives each short element
    @Override
    public void forEachShort(ShortConsumer c) {
        RunEndArrays.walkRuns(runEnds, values.length(), offset, offset + length, (run, count) -> {
            short v = values.getShort(run);
            for (long r = 0; r < count; r++) {
                c.accept(v);
            }
        });
    }

    @Override
    public long fold(long identity, LongBinaryOperator op) {
        return RunEndArrays.foldInt(runEnds, values.length(), offset, length, values::getInt, identity, op);
    }
}
