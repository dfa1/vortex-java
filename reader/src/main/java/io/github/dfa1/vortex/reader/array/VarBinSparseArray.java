package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.model.DType;

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.function.IntConsumer;

/// Lazy Sparse-encoded [VarBinArray]: `getBytes(i) = patchValues[binSearch(i + offset)]`, or
/// `fill` at every unpatched position.
///
/// The VarBin member of the `LazySparseXxxArray` family, built on the same
/// `findPatch`/`walkPatches` helpers the primitive variants use. Where those broadcast a
/// scalar fill, this one broadcasts the fill's raw bytes, so a sparse column costs
/// `numPatches` values of storage rather than a merged `length`-row bytes buffer plus an
/// `(length + 1)` offsets table.
///
/// The fill bytes matter beyond allocation: the eager merge this replaces described unpatched
/// rows as zero-length ranges, which rendered every one of them as the empty string no matter
/// what the fill scalar said. The Rust reference resolves an unpatched row to the fill value
/// for utf8/binary exactly as it does for primitives.
///
/// [#forEachByteLength(IntConsumer)] walks patches in order (one binary search up front, then
/// a per-patch step) so sequential reads are O(numPatches) work plus `length` emissions, not
/// O(length x log(numPatches)).
///
/// [#bytesSegment()] is the [MemorySegment#NULL] sentinel and [#segmentIfPresent()] is empty:
/// no single contiguous buffer holds the resolved rows, the same convention
/// [VarBinChunkedArray], [VarBinRunEndArray], and [VarBinConstantArray] use. Consumers that
/// need the flat bytes-plus-offsets shape get it on demand from
/// [VarBinArray#toOffsetMode(VarBinArray, java.lang.foreign.SegmentAllocator)].
///
/// The `patchIndices` array is typed as [Array] because the indices ptype varies — backed by
/// one of [ByteArray], [ShortArray], [IntArray], [LongArray].
///
/// A patch-free array is not represented here but as a [VarBinConstantArray] over the same
/// fill bytes, which resolves in O(1) with no search at all; `patchValues` is therefore always
/// non-null and non-empty.
///
/// @param dtype        logical element type (Utf8 or Binary)
/// @param length       total logical row count
/// @param fill         raw bytes of the fill scalar, shared by every unpatched row;
///                     [#getBytes(long)] clones it per that method's copy contract
/// @param patchValues  values for patched positions; length = `numPatches`
/// @param patchIndices sorted absolute positions of patches; length = `numPatches`
/// @param offset       starting absolute position; logical row `i` maps to absolute `i + offset`
@SuppressWarnings("java:S6218") // internal data carrier; record components are arrays of immutable primitives or refs that flow through pipelines without ever being compared.
public record VarBinSparseArray(DType dtype, long length, byte[] fill,
                                VarBinArray patchValues, Array patchIndices, long offset)
        implements VarBinArray {

    /// No single contiguous segment backs the resolved rows.
    ///
    /// @return the [MemorySegment#NULL] sentinel
    @Override
    public MemorySegment bytesSegment() {
        return MemorySegment.NULL;
    }

    /// No single contiguous segment backs the resolved rows.
    ///
    /// @return always empty
    @Override
    public Optional<MemorySegment> segmentIfPresent() {
        return Optional.empty();
    }

    @Override
    public byte[] getBytes(long i) {
        int p = patch(i);
        return p >= 0 ? patchValues.getBytes(p) : fill.clone();
    }

    @Override
    public String getString(long i) {
        int p = patch(i);
        return p >= 0 ? patchValues.getString(p) : new String(fill, StandardCharsets.UTF_8);
    }

    @Override
    public int getByteLength(long i) {
        int p = patch(i);
        return p >= 0 ? patchValues.getByteLength(p) : fill.length;
    }

    @Override
    public void forEachByteLength(IntConsumer c) {
        int fillLen = fill.length;
        SparseArrays.walkPatches(patchIndices, patchValues.length(), offset, offset + length,
                () -> c.accept(fillLen),
                p -> c.accept(patchValues.getByteLength(p)));
    }

    /// Zero-copy truncation: only the row count shrinks, since rows are resolved through
    /// `patchIndices` on read and trailing patches past the new end simply go unvisited.
    ///
    /// @param rows number of leading rows to keep
    /// @return a length-`rows` view over the same patches
    @Override
    public VarBinArray limited(long rows) {
        return rows >= length ? this
                : new VarBinSparseArray(dtype, rows, fill, patchValues, patchIndices, offset);
    }

    /// Locates the patch at logical row `i`.
    ///
    /// @param i zero-based logical row index (must be in `[0, length)`)
    /// @return the patch index, or `-1` when the row is unpatched
    private int patch(long i) {
        Objects.checkIndex(i, length);
        return SparseArrays.findPatch(patchIndices, patchValues.length(), i + offset);
    }
}
