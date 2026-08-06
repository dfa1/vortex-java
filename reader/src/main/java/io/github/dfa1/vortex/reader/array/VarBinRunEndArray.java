package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.model.DType;

import java.lang.foreign.MemorySegment;
import java.util.Objects;
import java.util.Optional;
import java.util.function.IntConsumer;

/// Lazy RunEnd-encoded [VarBinArray]: `getBytes(i) = values.getBytes(findRun(i + offset))`.
///
/// The VarBin member of the `LazyRunEndXxxArray` family. Where the primitive variants
/// broadcast a run's scalar, this one resolves to the run's variable-length value, so a
/// column of `numRuns` distinct strings covering `length` rows costs `numRuns` strings of
/// storage instead of `length` copies of them — the whole point of the encoding, which the
/// previous eager expansion in `RunEndEncodingDecoder` discarded at decode time.
///
/// [#forEachByteLength(IntConsumer)] walks runs (one binary search up front, then a per-run
/// loop) so sequential reads are O(numRuns) work plus `length` emissions.
///
/// [#bytesSegment()] is the [MemorySegment#NULL] sentinel and [#segmentIfPresent()] is empty:
/// no single contiguous buffer holds the expanded rows, the same convention
/// [VarBinChunkedArray], [VarBinViewArray], and [VarBinConstantArray] use. Consumers that
/// need the flat bytes-plus-offsets shape get it on demand from
/// [VarBinArray#toOffsetMode(VarBinArray, java.lang.foreign.SegmentAllocator)].
///
/// The `runEnds` array is typed as [Array] because the run-ends ptype varies with the
/// longest-run count — backed by one of [ByteArray], [ShortArray], [IntArray], [LongArray].
///
/// @param dtype   logical element type (Utf8 or Binary)
/// @param length  total logical row count
/// @param values  value per run; length = `numRuns`
/// @param runEnds cumulative run-end positions (absolute, before `offset`); length = `numRuns`
/// @param offset  starting absolute position; logical row `i` maps to absolute `i + offset`
public record VarBinRunEndArray(DType dtype, long length, VarBinArray values, Array runEnds, long offset)
        implements VarBinArray {

    /// No single contiguous segment backs the expanded rows.
    ///
    /// @return the [MemorySegment#NULL] sentinel
    @Override
    public MemorySegment bytesSegment() {
        return MemorySegment.NULL;
    }

    /// No single contiguous segment backs the expanded rows.
    ///
    /// @return always empty
    @Override
    public Optional<MemorySegment> segmentIfPresent() {
        return Optional.empty();
    }

    @Override
    public byte[] getBytes(long i) {
        return values.getBytes(run(i));
    }

    @Override
    public String getString(long i) {
        return values.getString(run(i));
    }

    @Override
    public int getByteLength(long i) {
        return values.getByteLength(run(i));
    }

    @Override
    public void forEachByteLength(IntConsumer c) {
        RunEndArrays.walkRuns(runEnds, values.length(), offset, offset + length, (runIdx, count) -> {
            int len = values.getByteLength(runIdx);
            for (long r = 0; r < count; r++) {
                c.accept(len);
            }
        });
    }

    /// Zero-copy truncation: only the row count shrinks, since rows are resolved through
    /// `runEnds` on read and trailing runs past the new end simply go unvisited.
    ///
    /// @param rows number of leading rows to keep
    /// @return a length-`rows` view over the same runs
    @Override
    public VarBinArray limited(long rows) {
        return rows >= length ? this : new VarBinRunEndArray(dtype, rows, values, runEnds, offset);
    }

    /// Locates the run covering logical row `i`.
    ///
    /// @param i zero-based logical row index (must be in `[0, length)`)
    /// @return the index of the covering run, in `[0, values.length())`
    private int run(long i) {
        Objects.checkIndex(i, length);
        return RunEndArrays.findRun(runEnds, values.length(), i + offset);
    }
}
