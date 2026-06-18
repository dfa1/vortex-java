package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.DType;

import java.util.function.LongBinaryOperator;

/// Lazy RunEnd-encoded [ByteArray]. See [LazyRunEndLongArray] for semantics.
///
/// @param dtype   logical element type
/// @param length  total logical row count
/// @param values  values per run
/// @param runEnds cumulative run-end positions (absolute, before `offset`)
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
        return RunEndArrays.runInt(runEnds, values.length(), i + offset, values::getInt);
    }

    @Override
    public long fold(long identity, LongBinaryOperator op) {
        return RunEndArrays.foldInt(runEnds, values.length(), offset, length, values::getInt, identity, op);
    }
}
