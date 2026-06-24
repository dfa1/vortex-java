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

    @Override
    public long fold(long identity, LongBinaryOperator op) {
        return RunEndArrays.foldInt(runEnds, values.length(), offset, length, values::getInt, identity, op);
    }
}
