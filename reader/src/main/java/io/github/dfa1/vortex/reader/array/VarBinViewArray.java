package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.io.VortexFormat;

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.function.IntConsumer;

/// Arrow StringView / BinaryView [VarBinArray].
///
/// Each row is a 16-byte view in `views`: bytes 0-3 are the u32 size; for
/// sizes ≤ 12 bytes the data is inlined in bytes 4..15; for sizes > 12 bytes
/// bytes 4-7 hold a 4-byte prefix (ignored on read), bytes 8-11 the u32 buffer
/// index into `dataBufs`, and bytes 12-15 the u32 offset within that
/// buffer. Per-row accessors resolve the view on demand — no concat or
/// materialization at construction time.
///
/// [#bytesSegment()] returns [MemorySegment#NULL] because there is
/// no single contiguous bytes segment; callers needing one must materialize via
/// the typed accessors.
///
/// @param dtype    logical element type (Utf8 or Binary)
/// @param length   total logical row count
/// @param views    16-byte view per row; length must be ≥ `length * 16`
/// @param dataBufs zero or more shared data buffers referenced by long views
@SuppressWarnings("java:S6218") // internal data carrier; record components are arrays of immutable refs that flow through pipelines without ever being compared.
public record VarBinViewArray(DType dtype, long length, MemorySegment views, MemorySegment[] dataBufs)
        implements VarBinArray {

    private static final int VIEW_SIZE = 16;
    private static final int MAX_INLINED_SIZE = 12;

    @Override
    public MemorySegment bytesSegment() {
        return MemorySegment.NULL;
    }

    /// No single contiguous segment — view rows reference shared data buffers.
    ///
    /// @return always empty
    @Override
    public Optional<MemorySegment> segmentIfPresent() {
        return Optional.empty();
    }

    @Override
    public int getByteLength(long i) {
        return checkedSize(views.get(VortexFormat.LE_INT, viewOffset(i)));
    }

    @Override
    public byte[] getBytes(long i) {
        long viewOff = viewOffset(i);
        int size = checkedSize(views.get(VortexFormat.LE_INT, viewOff));
        byte[] out = new byte[size];
        if (size <= MAX_INLINED_SIZE) {
            // Inlined data always fits the remaining 12 bytes of the view itself.
            MemorySegment.copy(views, viewOff + 4, MemorySegment.ofArray(out), 0, size);
        } else {
            int bufferIndex = views.get(VortexFormat.LE_INT, viewOff + 8);
            long srcOffset = Integer.toUnsignedLong(views.get(VortexFormat.LE_INT, viewOff + 12));
            if (bufferIndex < 0 || bufferIndex >= dataBufs.length) {
                throw new VortexException("varbin view at row " + i + " references data buffer "
                        + bufferIndex + " of " + dataBufs.length);
            }
            MemorySegment buf = dataBufs[bufferIndex];
            if (srcOffset + size > buf.byteSize()) {
                throw new VortexException("varbin view bytes [" + srcOffset + ", "
                        + (srcOffset + size) + ") out of range for data buffer " + bufferIndex
                        + " of " + buf.byteSize() + " bytes");
            }
            MemorySegment.copy(buf, srcOffset, MemorySegment.ofArray(out), 0, size);
        }
        return out;
    }

    @Override
    public String getString(long i) {
        return new String(getBytes(i), StandardCharsets.UTF_8);
    }

    @Override
    public void forEachByteLength(IntConsumer c) {
        long n = length;
        // Sized once, outside the loop, so the per-row body stays uniform — same
        // trade-off as VarBinOffsetArray: a negative size on the wire still reaches the
        // consumer, and [#getBytes(long)] rejects it when the row is read.
        checkViewsExtent(n);
        for (long i = 0; i < n; i++) {
            c.accept(views.get(VortexFormat.LE_INT, i * VIEW_SIZE));
        }
    }

    @Override
    public VarBinArray limited(long rows) {
        if (rows >= length) {
            return this;
        }
        checkViewsExtent(rows);
        return new VarBinViewArray(dtype, rows, views.asSlice(0, rows * VIEW_SIZE), dataBufs);
    }

    /// Byte offset of view `i`, rejecting a row the views segment does not cover.
    ///
    /// @param i zero-based row index
    /// @return the byte offset of the 16-byte view for row `i`
    private long viewOffset(long i) {
        long off = i * VIEW_SIZE;
        if (i < 0 || off + VIEW_SIZE > views.byteSize()) {
            throw new VortexException("varbin view index " + i
                    + " out of range for a views segment of " + views.byteSize() + " bytes");
        }
        return off;
    }

    /// Verifies the views segment holds `rows` complete 16-byte views.
    ///
    /// @param rows number of rows whose views are about to be read
    private void checkViewsExtent(long rows) {
        if (rows > views.byteSize() / VIEW_SIZE) {
            throw new VortexException("varbin views segment of " + views.byteSize()
                    + " bytes holds fewer than " + rows + " views");
        }
    }

    /// Rejects a negative element size read from a view header, which would otherwise
    /// reach `new byte[size]` as a `NegativeArraySizeException` (ADR 0003).
    ///
    /// @param size element size read from the view
    /// @return `size` when it is non-negative
    private static int checkedSize(int size) {
        if (size < 0) {
            throw new VortexException("negative varbin view size " + size);
        }
        return size;
    }
}
