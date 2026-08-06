package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.io.VortexFormat;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.util.Optional;
import java.util.function.IntConsumer;

/// Interface for variable-length binary / UTF-8 string columns.
///
/// Implementations: [VarBinOffsetArray] for standard offset-based layout, [VarBinDictArray] for
/// dictionary-encoded strings, [VarBinChunkedArray] for multi-chunk columns, [VarBinViewArray]
/// for Arrow StringView / BinaryView layout (16-byte view per row + zero or more shared data
/// buffers), [VarBinSlicedArray] for a zero-copy row-shifted view, and [VarBinConstantArray] for
/// a `vortex.constant` value broadcast across every row. All accessors resolve transparently
/// regardless of implementation; only [VarBinOffsetArray] exposes
/// [VarBinOffsetArray#offsetsSegment()] and [VarBinOffsetArray#offsetsPtype()].
///
/// Deliberately `non-sealed` (unlike the sibling `ByteArray`/`LongArray`/… families reopened the
/// same way off [Array]'s seal): which representation backs a given `VarBinArray` is an
/// implementation detail, not part of the type's contract, so new representations live as
/// ordinary top-level classes in this package rather than requiring this interface to enumerate
/// them.
public non-sealed interface VarBinArray extends Array {

    /// Returns the concatenated raw bytes segment backing all elements.
    ///
    /// @return the bytes [MemorySegment]
    MemorySegment bytesSegment();

    /// Returns the concatenated raw bytes segment directly — the primary data
    /// buffer is already materialized, so no copy or allocation is needed.
    /// Note this is the data buffer only; the per-row offsets are exposed
    /// separately by [VarBinOffsetArray#offsetsSegment()].
    ///
    /// @param arena unused; the existing bytes segment is returned as-is
    /// @return the concatenated raw bytes [MemorySegment]
    @Override
    default MemorySegment materialize(SegmentAllocator arena) {
        return bytesSegment();
    }

    /// Returns the concatenated raw bytes segment — already materialized, no allocation.
    ///
    /// @return the bytes [MemorySegment]
    @Override
    default Optional<MemorySegment> segmentIfPresent() {
        return Optional.of(bytesSegment());
    }

    /// Returns a copy of the raw bytes for element `i`.
    ///
    /// @param i zero-based logical index (must be in `[0, length)`)
    /// @return a newly allocated byte array containing the raw bytes of element `i`
    byte[] getBytes(long i);

    /// Returns the UTF-8 decoded string for element `i`.
    ///
    /// @param i zero-based logical index (must be in `[0, length)`)
    /// @return the UTF-8 string at position `i`
    String getString(long i);

    /// Returns the byte length of element `i` without copying the data.
    ///
    /// @param i zero-based logical index (must be in `[0, length)`)
    /// @return the number of bytes in element `i`
    int getByteLength(long i);

    /// Passes the byte length of each element to the given consumer in row order.
    ///
    /// @param c consumer called once per element with the byte length at each index
    void forEachByteLength(IntConsumer c);

    /// Returns a new `VarBinArray` containing only the first `rows` elements.
    ///
    /// @param rows number of rows to retain; if `rows >= length` returns this array unchanged
    /// @return a `VarBinArray` containing the first `rows` elements
    VarBinArray limited(long rows);

    /// Materializes any `VarBinArray` into a flat [VarBinOffsetArray]. The fast path
    /// returns `src` unchanged when it is already a [VarBinOffsetArray]. Other implementations
    /// ([VarBinViewArray] in particular) walk every row through the typed accessors, copy the
    /// bytes into a fresh contiguous segment allocated from `arena`, and build an I64
    /// offsets table. Used by parent decoders (dict, sparse, runend) whose downstream code
    /// depends on the bytes-plus-offsets shape.
    ///
    /// @param src   any VarBinArray
    /// @param arena allocator for the materialized bytes and offsets segments
    /// @return a [VarBinOffsetArray] view over the same logical content
    static VarBinOffsetArray toOffsetMode(VarBinArray src, SegmentAllocator arena) {
        if (src instanceof VarBinOffsetArray om) {
            return om;
        }
        long n = src.length();
        long totalBytes = 0;
        for (long i = 0; i < n; i++) {
            totalBytes += src.getByteLength(i);
        }
        MemorySegment outBytes = arena.allocate(totalBytes > 0 ? totalBytes : 1);
        MemorySegment outOffsets = arena.allocate((n + 1) * Long.BYTES, Long.BYTES);
        outOffsets.setAtIndex(VortexFormat.LE_LONG, 0, 0L);
        long bytePos = 0;
        for (long i = 0; i < n; i++) {
            byte[] b = src.getBytes(i);
            MemorySegment.copy(MemorySegment.ofArray(b), 0, outBytes, bytePos, b.length);
            bytePos += b.length;
            outOffsets.setAtIndex(VortexFormat.LE_LONG, i + 1, bytePos);
        }
        return new VarBinOffsetArray(src.dtype(), n, outBytes.asReadOnly(), outOffsets, PType.I64);
    }

    /// Creates a dict-mode `VarBinArray`. Lengths and bytes are resolved via the
    /// dictionary on each access; no string materialization occurs at construction time.
    ///
    /// @param dtype           logical type (Utf8 or Binary)
    /// @param n               number of logical elements (rows)
    /// @param dictValBytes    concatenated raw bytes for all dictionary values
    /// @param dictValOffsets  offsets into `dictValBytes` for each dictionary entry (length = dictSize + 1)
    /// @param dictValOffPType physical type of the dictionary value offsets
    /// @param dictCodesSegs   per-row dictionary code indices (length = n)
    /// @param dictCodesPType  physical type of the dictionary codes
    /// @return a new dict-mode `VarBinArray`
    static VarBinArray ofDict(DType dtype, long n,
            MemorySegment dictValBytes,
            MemorySegment dictValOffsets, PType dictValOffPType,
            MemorySegment dictCodesSegs, PType dictCodesPType) {
        return new VarBinDictArray(dtype, n, dictValBytes, dictValOffsets, dictValOffPType,
                dictCodesSegs, dictCodesPType);
    }
}
