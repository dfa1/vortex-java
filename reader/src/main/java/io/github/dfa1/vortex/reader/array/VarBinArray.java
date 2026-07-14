package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.io.VortexFormat;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.function.IntConsumer;

/// Sealed interface for variable-length binary / UTF-8 string columns.
///
/// Four implementations: [OffsetMode] for standard offset-based layout,
/// [DictMode] for dictionary-encoded strings, [ChunkedMode] for
/// multi-chunk columns, and [ViewMode] for Arrow StringView / BinaryView
/// layout (16-byte view per row + zero or more shared data buffers). All
/// accessors resolve transparently regardless of mode; only [OffsetMode]
/// exposes [OffsetMode#offsetsSegment()] and [OffsetMode#offsetsPtype()].
public sealed interface VarBinArray extends Array
        permits VarBinArray.OffsetMode, VarBinArray.DictMode, VarBinArray.ChunkedMode,
                VarBinArray.ViewMode, VarBinArray.SlicedMode {

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
    record SlicedMode(DType dtype, long length, VarBinArray inner, long offset)
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
            return new SlicedMode(dtype, rows, inner, offset);
        }
    }


    /// Returns the concatenated raw bytes segment backing all elements.
    ///
    /// @return the bytes [MemorySegment]
    MemorySegment bytesSegment();

    /// Returns the concatenated raw bytes segment directly — the primary data
    /// buffer is already materialized, so no copy or allocation is needed.
    /// Note this is the data buffer only; the per-row offsets are exposed
    /// separately by [OffsetMode#offsetsSegment()].
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

    /// Materializes any `VarBinArray` into a flat [OffsetMode]. The fast path
    /// returns `src` unchanged when it is already an [OffsetMode]. Other modes
    /// (ViewMode in particular) walk every row through the typed accessors, copy the bytes
    /// into a fresh contiguous segment allocated from `arena`, and build an I64
    /// offsets table. Used by parent decoders (dict, sparse, runend) whose downstream code
    /// depends on the bytes-plus-offsets shape.
    ///
    /// @param src   any VarBinArray
    /// @param arena allocator for the materialized bytes and offsets segments
    /// @return an OffsetMode view over the same logical content
    static OffsetMode toOffsetMode(VarBinArray src, SegmentAllocator arena) {
        if (src instanceof OffsetMode om) {
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
        return new OffsetMode(src.dtype(), n, outBytes.asReadOnly(), outOffsets, PType.I64);
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
        return new DictMode(dtype, n, dictValBytes, dictValOffsets, dictValOffPType,
                dictCodesSegs, dictCodesPType);
    }

    /// Standard offset-based `VarBinArray`.
    ///
    /// Element `i` occupies `bytesSegment[offsetsSegment[i]..offsetsSegment[i+1]]`.
    ///
    /// @param dtype          logical type (Utf8 or Binary)
    /// @param length         number of variable-length elements
    /// @param bytesSegment   concatenated raw byte data for all elements
    /// @param offsetsSegment offsets segment of length `length + 1`
    /// @param offsetsPtype   physical type of the offsets values (I32/U32 or I64/U64)
    @SuppressWarnings("java:S6218") // internal data carrier; record components are arrays of immutable primitives or refs that flow through pipelines without ever being compared.
    record OffsetMode(DType dtype, long length, MemorySegment bytesSegment,
                      MemorySegment offsetsSegment, PType offsetsPtype)
            implements VarBinArray {

        @Override
        public byte[] getBytes(long i) {
            long start = readOffset(i);
            long end = readOffset(i + 1);
            byte[] out = new byte[(int) (end - start)];
            MemorySegment.copy(bytesSegment, start, MemorySegment.ofArray(out), 0, end - start);
            return out;
        }

        @Override
        public String getString(long i) {
            return new String(getBytes(i), StandardCharsets.UTF_8);
        }

        @Override
        public int getByteLength(long i) {
            return (int) (readOffset(i + 1) - readOffset(i));
        }

        @Override
        public void forEachByteLength(IntConsumer c) {
            long n = length;
            if (offsetsPtype == PType.I32 || offsetsPtype == PType.U32) {
                for (long i = 0; i < n; i++) {
                    c.accept(offsetsSegment.getAtIndex(VortexFormat.LE_INT, i + 1)
                            - offsetsSegment.getAtIndex(VortexFormat.LE_INT, i));
                }
            } else {
                for (long i = 0; i < n; i++) {
                    c.accept((int) (offsetsSegment.getAtIndex(VortexFormat.LE_LONG, i + 1)
                            - offsetsSegment.getAtIndex(VortexFormat.LE_LONG, i)));
                }
            }
        }

        @Override
        public VarBinArray limited(long rows) {
            if (rows >= length) {
                return this;
            }
            long byteEnd = readOffset(rows);
            int offBytes = (offsetsPtype == PType.I32 || offsetsPtype == PType.U32)
                    ? Integer.BYTES : Long.BYTES;
            MemorySegment newOffsetsSeg = offsetsSegment.asSlice(0, (rows + 1) * offBytes);
            return new OffsetMode(dtype, rows,
                    bytesSegment.asSlice(0, byteEnd > 0 ? byteEnd : 0), newOffsetsSeg, offsetsPtype);
        }

        private long readOffset(long i) {
            if (offsetsPtype == PType.I32 || offsetsPtype == PType.U32) {
                return offsetsSegment.getAtIndex(VortexFormat.LE_INT, i);
            }
            return offsetsSegment.getAtIndex(VortexFormat.LE_LONG, i);
        }
    }

    /// Dictionary-encoded `VarBinArray`.
    ///
    /// Stores dictionary values and per-row codes; all accessors resolve through the
    /// dictionary without materializing strings at construction time.
    ///
    /// @param dtype           logical type (Utf8 or Binary)
    /// @param length          number of logical elements (rows)
    /// @param bytesSegment    concatenated raw bytes for all dictionary values
    /// @param dictValOffsets  offsets into `bytesSegment` for each dictionary entry (length = dictSize + 1)
    /// @param dictValOffPType physical type of the dictionary value offsets
    /// @param dictCodesSegs   per-row dictionary code indices (length = `length`)
    /// @param dictCodesPType  physical type of the dictionary codes
    @SuppressWarnings("java:S6218") // internal data carrier; record components are arrays of immutable primitives or refs that flow through pipelines without ever being compared.
    record DictMode(DType dtype, long length, MemorySegment bytesSegment,
                    MemorySegment dictValOffsets, PType dictValOffPType,
                    MemorySegment dictCodesSegs, PType dictCodesPType)
            implements VarBinArray {

        @Override
        public byte[] getBytes(long i) {
            long code = dictReadCode(i);
            long start = dictReadOff(code);
            long end = dictReadOff(code + 1);
            byte[] out = new byte[(int) (end - start)];
            MemorySegment.copy(bytesSegment, start, MemorySegment.ofArray(out), 0, end - start);
            return out;
        }

        @Override
        public String getString(long i) {
            return new String(getBytes(i), StandardCharsets.UTF_8);
        }

        @Override
        public int getByteLength(long i) {
            long code = dictReadCode(i);
            return (int) (dictReadOff(code + 1) - dictReadOff(code));
        }

        @Override
        public void forEachByteLength(IntConsumer c) {
            // Hot loop: hoist both ptype dispatches out of the per-row body so C2 sees a
            // uniform, fixed-stride loop (CLAUDE.md hot-loop rule). A variable-target
            // switch(dictValOffPType)/switch(dictCodesPType) per element blocks C2
            // superword vectorization and adds invariant width/bounds arithmetic on every
            // row (regression introduced by #215). I32 dict-value offsets are by far the
            // most common (FSST + most dict encodings emit 32-bit offsets), so the fast
            // path reads offsets at a constant 4-byte stride and branch-splits the code
            // read once; wider offset ptypes take the general per-row path.
            if (dictValOffPType == PType.I32) {
                forEachI32OffsetByteLength(c);
            } else {
                for (long i = 0; i < length; i++) {
                    long code = dictReadCode(i);
                    c.accept((int) (dictReadOff(code + 1) - dictReadOff(code)));
                }
            }
        }

        /// Fast path of [#forEachByteLength(IntConsumer)] for I32 dict-value offsets: the
        /// code-ptype switch is hoisted out of the loop so each specialized loop body reads
        /// codes at a single fixed stride and computes lengths from a constant 4-byte offset
        /// stride, leaving the per-row body uniform and vectorizable.
        ///
        /// @param c consumer called once per row with the byte length at that index
        private void forEachI32OffsetByteLength(IntConsumer c) {
            long n = length;
            switch (dictCodesPType) {
                case U8 -> {
                    for (long i = 0; i < n; i++) {
                        c.accept(i32OffsetLength(
                                Byte.toUnsignedLong(dictCodesSegs.get(ValueLayout.JAVA_BYTE, i))));
                    }
                }
                case U16 -> {
                    for (long i = 0; i < n; i++) {
                        c.accept(i32OffsetLength(
                                Short.toUnsignedLong(dictCodesSegs.getAtIndex(VortexFormat.LE_SHORT, i))));
                    }
                }
                case U32 -> {
                    for (long i = 0; i < n; i++) {
                        c.accept(i32OffsetLength(
                                Integer.toUnsignedLong(dictCodesSegs.getAtIndex(VortexFormat.LE_INT, i))));
                    }
                }
                case I32 -> {
                    for (long i = 0; i < n; i++) {
                        c.accept(i32OffsetLength(dictCodesSegs.getAtIndex(VortexFormat.LE_INT, i)));
                    }
                }
                case I64, U64 -> {
                    for (long i = 0; i < n; i++) {
                        c.accept(i32OffsetLength(dictCodesSegs.getAtIndex(VortexFormat.LE_LONG, i)));
                    }
                }
                default -> throw new VortexException("unsupported codes ptype: " + dictCodesPType);
            }
        }

        /// Byte length of the dictionary entry `code` when the value offsets are I32:
        /// `offsets[code + 1] - offsets[code]` read at a constant 4-byte stride. The
        /// segment access itself bounds-checks, so an out-of-range code is caught without
        /// the per-row width recomputation that [#dictReadOff(long)] performs.
        ///
        /// @param code zero-based dictionary entry index (in `[0, dictSize)`)
        /// @return the byte length of dictionary entry `code`
        private int i32OffsetLength(long code) {
            return dictValOffsets.getAtIndex(VortexFormat.LE_INT, code + 1)
                    - dictValOffsets.getAtIndex(VortexFormat.LE_INT, code);
        }

        @Override
        public VarBinArray limited(long rows) {
            if (rows >= length) {
                return this;
            }
            int codeBytes = dictCodesPType.byteSize();
            return VarBinArray.ofDict(dtype, rows, bytesSegment, dictValOffsets, dictValOffPType,
                    dictCodesSegs.asSlice(0, rows * codeBytes), dictCodesPType);
        }

        private long dictReadCode(long i) {
            return switch (dictCodesPType) {
                case U8 -> Byte.toUnsignedLong(dictCodesSegs.get(ValueLayout.JAVA_BYTE, i));
                case U16 -> Short.toUnsignedLong(dictCodesSegs.getAtIndex(VortexFormat.LE_SHORT, i));
                case U32 -> Integer.toUnsignedLong(dictCodesSegs.getAtIndex(VortexFormat.LE_INT, i));
                case I32 -> dictCodesSegs.getAtIndex(VortexFormat.LE_INT, i);
                case I64, U64 -> dictCodesSegs.getAtIndex(VortexFormat.LE_LONG, i);
                default -> throw new VortexException("unsupported codes ptype: " + dictCodesPType);
            };
        }

        /// Reads dictionary-value offset `i` at the true width of [#dictValOffPType].
        ///
        /// Offsets can arrive at any integer width — FSST decompresses its values to a
        /// child with I32 offsets, legacy dicts use I64, and narrow sequence-encoded
        /// offsets keep their U8/U16 ptype on the wire. Reading at the wrong width (e.g.
        /// an 8-byte read against a 4-byte-stride buffer) walks off the segment. The
        /// buffer bounds and ptype are untrusted input, so both are checked here and a
        /// [VortexException] is thrown rather than a bare `IndexOutOfBoundsException`
        /// (ADR 0003).
        ///
        /// @param i zero-based offset index (in `[0, dictSize]`)
        /// @return the offset value widened to a signed long
        private long dictReadOff(long i) {
            int width = dictValOffPType.byteSize();
            long byteOffset = i * width;
            if (i < 0 || byteOffset + width > dictValOffsets.byteSize()) {
                throw new VortexException("dict value offset index " + i + " (" + dictValOffPType
                        + ", " + width + "-byte) out of range for offsets segment of "
                        + dictValOffsets.byteSize() + " bytes");
            }
            return switch (dictValOffPType) {
                case U8 -> Byte.toUnsignedLong(dictValOffsets.get(ValueLayout.JAVA_BYTE, byteOffset));
                case I8 -> dictValOffsets.get(ValueLayout.JAVA_BYTE, byteOffset);
                case U16 -> Short.toUnsignedLong(dictValOffsets.get(VortexFormat.LE_SHORT, byteOffset));
                case I16 -> dictValOffsets.get(VortexFormat.LE_SHORT, byteOffset);
                case U32 -> Integer.toUnsignedLong(dictValOffsets.get(VortexFormat.LE_INT, byteOffset));
                case I32 -> dictValOffsets.get(VortexFormat.LE_INT, byteOffset);
                case I64, U64 -> dictValOffsets.get(VortexFormat.LE_LONG, byteOffset);
                default -> throw new VortexException(
                        "unsupported dict value offset ptype: " + dictValOffPType);
            };
        }
    }

    /// Multi-chunk `VarBinArray` — wraps a list of child `VarBinArray`s plus
    /// cumulative row offsets. Per-row accessors binary-search `offsets` to find the
    /// owning chunk and delegate. Per ADR 0012, preserves zero-copy on multi-chunk Utf8 /
    /// Binary columns: each chunk's underlying segments stay live (mmap slices); no concat.
    ///
    /// [#bytesSegment()] is the [MemorySegment#NULL] sentinel — chunked
    /// arrays have no single contiguous bytes segment. Callers that need contiguous
    /// bytes must materialize via the chunked children.
    ///
    /// @param dtype    logical element type (Utf8 or Binary)
    /// @param length   total logical row count
    /// @param children chunk arrays in scan order; each is itself a [VarBinArray]
    /// @param offsets  cumulative row counts; length = `children.length + 1`
    @SuppressWarnings("java:S6218") // internal data carrier; record components are arrays of immutable primitives or refs that flow through pipelines without ever being compared.
    record ChunkedMode(DType dtype, long length, VarBinArray[] children, long[] offsets)
            implements VarBinArray {

        /// Builds a `ChunkedMode` from a list of chunk arrays that are already all typed
        /// [VarBinArray]s (no all-null [NullArray] chunks). Used by [#limited(long)],
        /// whose children are always concrete `VarBinArray`s.
        ///
        /// @param dtype     logical element type
        /// @param totalRows expected total row count
        /// @param chunks    non-empty list of [VarBinArray] chunks
        /// @return a new `ChunkedMode`
        /// @throws VortexException on empty input, non-[VarBinArray] chunks, or row-count mismatch
        public static ChunkedMode of(DType dtype, long totalRows,
                java.util.List<? extends Array> chunks) {
            return of(dtype, totalRows, chunks, null);
        }

        /// Builds a `ChunkedMode` from a list of chunk arrays.
        ///
        /// An entirely-null chunk decodes to a [NullArray] rather than a [VarBinArray]
        /// (e.g. a `vortex.null` flat, or `vortex.constant` with a null scalar, #269).
        /// Such a chunk is materialized into an all-null [OffsetMode] of the same row
        /// count — every row zero-length, all offsets zero — so the chunked column keeps
        /// a uniform `VarBinArray` shape. Row-level nullability is preserved separately by
        /// the caller's validity bitmap.
        ///
        /// @param dtype     logical element type
        /// @param totalRows expected total row count
        /// @param chunks    non-empty list of chunk arrays; each a [VarBinArray] or [NullArray]
        /// @param arena     allocator for the offsets segment of a materialized null chunk;
        ///                  may be `null` only when no chunk is a [NullArray]
        /// @return a new `ChunkedMode`
        /// @throws VortexException on empty input, non-`VarBinArray`/`NullArray` chunks,
        ///                         or row-count mismatch
        public static ChunkedMode of(DType dtype, long totalRows,
                java.util.List<? extends Array> chunks, SegmentAllocator arena) {
            if (chunks.isEmpty()) {
                throw new VortexException("VarBinArray.ChunkedMode: empty chunk list");
            }
            var typed = new java.util.ArrayList<VarBinArray>(chunks.size());
            for (Array c : chunks) {
                Array data = c instanceof MaskedArray m ? m.inner() : c;
                if (data instanceof ChunkedMode nested) {
                    java.util.Collections.addAll(typed, nested.children);
                } else if (data instanceof VarBinArray vb) {
                    typed.add(vb);
                } else if (data instanceof NullArray na) {
                    if (arena == null) {
                        throw new VortexException(
                                "VarBinArray.ChunkedMode: null chunk requires an allocator");
                    }
                    typed.add(allNull(dtype, na.length(), arena));
                } else {
                    throw new VortexException("VarBinArray.ChunkedMode: chunk is not a VarBinArray: "
                            + data.getClass().getSimpleName());
                }
            }
            long[] off = new long[typed.size() + 1];
            for (int i = 0; i < typed.size(); i++) {
                off[i + 1] = off[i] + typed.get(i).length();
            }
            if (off[off.length - 1] != totalRows) {
                throw new VortexException("VarBinArray.ChunkedMode: chunk rows sum to "
                        + off[off.length - 1] + ", expected " + totalRows);
            }
            return new ChunkedMode(dtype, totalRows, typed.toArray(VarBinArray[]::new), off);
        }

        /// Builds an all-null [OffsetMode] of `n` rows: an empty bytes segment and an
        /// offsets segment of `n + 1` zeros, so every row is zero-length. Row nullability
        /// is carried by the caller's validity bitmap, not the byte data.
        ///
        /// @param dtype logical element type (Utf8 or Binary)
        /// @param n     number of all-null rows
        /// @param arena allocator for the offsets segment
        /// @return an [OffsetMode] with `n` zero-length rows
        private static OffsetMode allNull(DType dtype, long n, SegmentAllocator arena) {
            MemorySegment offsets = arena.allocate((n + 1) * Long.BYTES, Long.BYTES);
            return new OffsetMode(dtype, n, MemorySegment.NULL, offsets, PType.I64);
        }

        private int findChunk(long i) {
            int hit = java.util.Arrays.binarySearch(offsets, i);
            int idx = hit >= 0 ? hit : -hit - 2;
            if (idx >= children.length) {
                idx = children.length - 1;
            }
            return idx;
        }

        @Override
        public MemorySegment bytesSegment() {
            return MemorySegment.NULL;
        }

        /// No single contiguous segment — chunked data lives across children.
        ///
        /// @return always empty
        @Override
        public Optional<MemorySegment> segmentIfPresent() {
            return Optional.empty();
        }

        @Override
        public byte[] getBytes(long i) {
            int c = findChunk(i);
            return children[c].getBytes(i - offsets[c]);
        }

        @Override
        public String getString(long i) {
            int c = findChunk(i);
            return children[c].getString(i - offsets[c]);
        }

        @Override
        public int getByteLength(long i) {
            int c = findChunk(i);
            return children[c].getByteLength(i - offsets[c]);
        }

        @Override
        public void forEachByteLength(IntConsumer c) {
            for (VarBinArray child : children) {
                child.forEachByteLength(c);
            }
        }

        @Override
        public VarBinArray limited(long rows) {
            if (rows >= length) {
                return this;
            }
            // Keep full children that fit, recursively limited the boundary child.
            var kept = new java.util.ArrayList<Array>(children.length);
            for (int i = 0; i < children.length; i++) {
                long start = offsets[i];
                long end = offsets[i + 1];
                if (start >= rows) {
                    break;
                }
                if (end <= rows) {
                    kept.add(children[i]);
                } else {
                    kept.add(children[i].limited(rows - start));
                }
            }
            return ChunkedMode.of(dtype, rows, kept);
        }
    }

    /// Arrow StringView / BinaryView `VarBinArray`.
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
    record ViewMode(DType dtype, long length, MemorySegment views, MemorySegment[] dataBufs)
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
            return views.get(VortexFormat.LE_INT, i * VIEW_SIZE);
        }

        @Override
        public byte[] getBytes(long i) {
            long viewOff = i * VIEW_SIZE;
            int size = views.get(VortexFormat.LE_INT, viewOff);
            byte[] out = new byte[size];
            if (size <= MAX_INLINED_SIZE) {
                MemorySegment.copy(views, viewOff + 4, MemorySegment.ofArray(out), 0, size);
            } else {
                int bufferIndex = views.get(VortexFormat.LE_INT, viewOff + 8);
                long srcOffset = Integer.toUnsignedLong(views.get(VortexFormat.LE_INT, viewOff + 12));
                MemorySegment.copy(dataBufs[bufferIndex], srcOffset, MemorySegment.ofArray(out), 0, size);
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
            for (long i = 0; i < n; i++) {
                c.accept(views.get(VortexFormat.LE_INT, i * VIEW_SIZE));
            }
        }

        @Override
        public VarBinArray limited(long rows) {
            if (rows >= length) {
                return this;
            }
            return new ViewMode(dtype, rows, views.asSlice(0, rows * VIEW_SIZE), dataBufs);
        }
    }
}
