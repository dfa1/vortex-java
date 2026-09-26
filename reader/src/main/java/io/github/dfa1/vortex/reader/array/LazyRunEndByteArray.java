package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.model.DType;

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

    /// Emits each run's value `count` times instead of binary-searching per element. See
    /// [LazyRunEndShortArray#forEachShort] — same gap, same fix.
    ///
    /// @param c consumer that receives each byte element
    @Override
    public void forEachByte(ByteConsumer c) {
        RunEndArrays.walkRuns(runEnds, values.length(), offset, offset + length, (run, count) -> {
            byte v = values.getByte(run);
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
