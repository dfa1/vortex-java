package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.model.DType;

import java.lang.foreign.MemorySegment;
import java.util.Optional;
import java.util.function.IntConsumer;

/// Sliced view over a [VarBinArray]: every accessor delegates to `inner`
/// with the row index shifted by `offset`. Used by the scan iterator to
/// surface a column that was decoded once (because it shares a single
/// flat layout across multiple aligned chunks) as a per-chunk slice
/// without copying.
///
/// @param dtype  logical element type (typically [DType.Utf8] or [DType.Binary])
/// @param length number of logical elements in this slice
/// @param inner  underlying VarBin array
/// @param offset starting row index into `inner`
public record VarBinSlicedArray(DType dtype, long length, VarBinArray inner, long offset)
        implements VarBinArray {

    @Override
    public MemorySegment bytesSegment() {
        return inner.bytesSegment();
    }

    /// Delegates the probe to the wrapped array — empty if the inner is
    /// itself composite (chunked / view).
    ///
    /// @return the inner array's segment if segment-backed, otherwise empty
    @Override
    public Optional<MemorySegment> segmentIfPresent() {
        return inner.segmentIfPresent();
    }

    @Override
    public byte[] getBytes(long i) {
        return inner.getBytes(i + offset);
    }

    @Override
    public String getString(long i) {
        return inner.getString(i + offset);
    }

    @Override
    public int getByteLength(long i) {
        return inner.getByteLength(i + offset);
    }

    @Override
    public void forEachByteLength(IntConsumer c) {
        for (long i = 0; i < length; i++) {
            c.accept(getByteLength(i));
        }
    }

    @Override
    public VarBinArray limited(long rows) {
        if (rows >= length) {
            return this;
        }
        return new VarBinSlicedArray(dtype, rows, inner, offset);
    }
}
