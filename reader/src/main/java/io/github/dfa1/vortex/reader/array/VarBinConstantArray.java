package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.model.DType;

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.function.IntConsumer;

/// Metadata-only [VarBinArray] for `vortex.constant` Utf8/Binary columns: a single value
/// broadcast across `length` logical rows. No buffer is allocated and no per-row
/// materialization occurs — every accessor returns the same shared bytes, mirroring the
/// `LazyConstantXxxArray` family the primitive array types use for the same encoding.
///
/// @param dtype  logical element type (Utf8 or Binary)
/// @param length number of logical rows the constant is broadcast across
/// @param bytes  the constant value's raw bytes, shared across every row; [#getBytes(long)]
///               clones it per that method's copy contract
@SuppressWarnings("java:S6218") // internal data carrier; record components are arrays of immutable primitives or refs that flow through pipelines without ever being compared.
public record VarBinConstantArray(DType dtype, long length, byte[] bytes) implements VarBinArray {

    @Override
    public MemorySegment bytesSegment() {
        return MemorySegment.NULL;
    }

    /// No single contiguous segment backs a broadcast constant.
    ///
    /// @return always empty
    @Override
    public Optional<MemorySegment> segmentIfPresent() {
        return Optional.empty();
    }

    @Override
    public byte[] getBytes(long i) {
        Objects.checkIndex(i, length);
        return bytes.clone();
    }

    @Override
    public String getString(long i) {
        Objects.checkIndex(i, length);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @Override
    public int getByteLength(long i) {
        Objects.checkIndex(i, length);
        return bytes.length;
    }

    @Override
    public void forEachByteLength(IntConsumer c) {
        int len = bytes.length;
        for (long i = 0; i < length; i++) {
            c.accept(len);
        }
    }

    @Override
    public VarBinArray limited(long rows) {
        return rows >= length ? this : new VarBinConstantArray(dtype, rows, bytes);
    }
}
