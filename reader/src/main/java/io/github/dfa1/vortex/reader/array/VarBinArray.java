package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.encoding.PTypeIO;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.function.IntConsumer;

/// Sealed interface for variable-length binary / UTF-8 string columns.
///
/// Three implementations: {@link OffsetMode} for standard offset-based layout,
/// {@link DictMode} for dictionary-encoded strings, and {@link ChunkedMode} for
/// multi-chunk columns. All accessors resolve transparently regardless of mode;
/// only {@link OffsetMode} exposes {@link OffsetMode#offsetsSegment()} and
/// {@link OffsetMode#offsetsPtype()}.
public sealed interface VarBinArray extends Array
        permits VarBinArray.OffsetMode, VarBinArray.DictMode, VarBinArray.ChunkedMode {

    /// Returns the concatenated raw bytes segment backing all elements.
    ///
    /// @return the bytes {@link MemorySegment}
    MemorySegment bytesSegment();

    /// Returns a copy of the raw bytes for element {@code i}.
    ///
    /// @param i zero-based logical index (must be in {@code [0, length)})
    /// @return a newly allocated byte array containing the raw bytes of element {@code i}
    byte[] getBytes(long i);

    /// Returns the UTF-8 decoded string for element {@code i}.
    ///
    /// @param i zero-based logical index (must be in {@code [0, length)})
    /// @return the UTF-8 string at position {@code i}
    String getString(long i);

    /// Returns the byte length of element {@code i} without copying the data.
    ///
    /// @param i zero-based logical index (must be in {@code [0, length)})
    /// @return the number of bytes in element {@code i}
    int getByteLength(long i);

    /// Passes the byte length of each element to the given consumer in row order.
    ///
    /// @param c consumer called once per element with the byte length at each index
    void forEachByteLength(IntConsumer c);

    /// Returns a new {@code VarBinArray} containing only the first {@code rows} elements.
    ///
    /// @param rows number of rows to retain; if {@code rows >= length} returns this array unchanged
    /// @return a {@code VarBinArray} containing the first {@code rows} elements
    VarBinArray truncate(long rows);

    /// Creates a dict-mode {@code VarBinArray}. Lengths and bytes are resolved via the
    /// dictionary on each access; no string materialization occurs at construction time.
    ///
    /// @param dtype           logical type (Utf8 or Binary)
    /// @param n               number of logical elements (rows)
    /// @param dictValBytes    concatenated raw bytes for all dictionary values
    /// @param dictValOffsets  offsets into {@code dictValBytes} for each dictionary entry (length = dictSize + 1)
    /// @param dictValOffPType physical type of the dictionary value offsets
    /// @param dictCodesSegs   per-row dictionary code indices (length = n)
    /// @param dictCodesPType  physical type of the dictionary codes
    /// @return a new dict-mode {@code VarBinArray}
    static VarBinArray ofDict(DType dtype, long n,
            MemorySegment dictValBytes,
            MemorySegment dictValOffsets, PType dictValOffPType,
            MemorySegment dictCodesSegs, PType dictCodesPType) {
        return new DictMode(dtype, n, dictValBytes, dictValOffsets, dictValOffPType,
                dictCodesSegs, dictCodesPType);
    }

    /// Standard offset-based {@code VarBinArray}.
    ///
    /// Element {@code i} occupies {@code bytesSegment[offsetsSegment[i]..offsetsSegment[i+1]]}.
    ///
    /// @param dtype          logical type (Utf8 or Binary)
    /// @param length         number of variable-length elements
    /// @param bytesSegment   concatenated raw byte data for all elements
    /// @param offsetsSegment offsets segment of length {@code length + 1}
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
                    c.accept(offsetsSegment.getAtIndex(PTypeIO.LE_INT, i + 1)
                            - offsetsSegment.getAtIndex(PTypeIO.LE_INT, i));
                }
            } else {
                for (long i = 0; i < n; i++) {
                    c.accept((int) (offsetsSegment.getAtIndex(PTypeIO.LE_LONG, i + 1)
                            - offsetsSegment.getAtIndex(PTypeIO.LE_LONG, i)));
                }
            }
        }

        @Override
        public VarBinArray truncate(long rows) {
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
                return offsetsSegment.getAtIndex(PTypeIO.LE_INT, i);
            }
            return offsetsSegment.getAtIndex(PTypeIO.LE_LONG, i);
        }
    }

    /// Dictionary-encoded {@code VarBinArray}.
    ///
    /// Stores dictionary values and per-row codes; all accessors resolve through the
    /// dictionary without materializing strings at construction time.
    ///
    /// @param dtype           logical type (Utf8 or Binary)
    /// @param length          number of logical elements (rows)
    /// @param bytesSegment    concatenated raw bytes for all dictionary values
    /// @param dictValOffsets  offsets into {@code bytesSegment} for each dictionary entry (length = dictSize + 1)
    /// @param dictValOffPType physical type of the dictionary value offsets
    /// @param dictCodesSegs   per-row dictionary code indices (length = {@code length})
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
            for (long i = 0; i < length; i++) {
                long code = dictReadCode(i);
                c.accept((int) (dictReadOff(code + 1) - dictReadOff(code)));
            }
        }

        @Override
        public VarBinArray truncate(long rows) {
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
                case U16 -> Short.toUnsignedLong(dictCodesSegs.getAtIndex(PTypeIO.LE_SHORT, i));
                case U32 -> Integer.toUnsignedLong(dictCodesSegs.getAtIndex(PTypeIO.LE_INT, i));
                case I32 -> dictCodesSegs.getAtIndex(PTypeIO.LE_INT, i);
                case I64, U64 -> dictCodesSegs.getAtIndex(PTypeIO.LE_LONG, i);
                default -> throw new VortexException("unsupported codes ptype: " + dictCodesPType);
            };
        }

        private long dictReadOff(long i) {
            if (dictValOffPType == PType.I32 || dictValOffPType == PType.U32) {
                return dictValOffsets.getAtIndex(PTypeIO.LE_INT, i);
            }
            return dictValOffsets.getAtIndex(PTypeIO.LE_LONG, i);
        }
    }

    /// Multi-chunk {@code VarBinArray} — wraps a list of child {@code VarBinArray}s plus
    /// cumulative row offsets. Per-row accessors binary-search {@code offsets} to find the
    /// owning chunk and delegate. Per ADR 0012, preserves zero-copy on multi-chunk Utf8 /
    /// Binary columns: each chunk's underlying segments stay live (mmap slices); no concat.
    ///
    /// {@link #bytesSegment()} is the {@link MemorySegment#NULL} sentinel — chunked
    /// arrays have no single contiguous bytes segment. Callers that need contiguous
    /// bytes must materialise via the chunked children.
    ///
    /// @param dtype    logical element type (Utf8 or Binary)
    /// @param length   total logical row count
    /// @param children chunk arrays in scan order; each is itself a {@link VarBinArray}
    /// @param offsets  cumulative row counts; length = {@code children.length + 1}
    @SuppressWarnings("java:S6218") // internal data carrier; record components are arrays of immutable primitives or refs that flow through pipelines without ever being compared.
    record ChunkedMode(DType dtype, long length, VarBinArray[] children, long[] offsets)
            implements VarBinArray {

        /// Builds a {@code ChunkedMode} from a list of chunk arrays.
        ///
        /// @param dtype     logical element type
        /// @param totalRows expected total row count
        /// @param chunks    non-empty list of {@link VarBinArray} chunks
        /// @return a new {@code ChunkedMode}
        /// @throws VortexException on empty input, non-{@link VarBinArray} chunks, or row-count mismatch
        public static ChunkedMode of(DType dtype, long totalRows,
                java.util.List<? extends Array> chunks) {
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
        public VarBinArray truncate(long rows) {
            if (rows >= length) {
                return this;
            }
            // Keep full children that fit, recursively truncate the boundary child.
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
                    kept.add(children[i].truncate(rows - start));
                }
            }
            return ChunkedMode.of(dtype, rows, kept);
        }
    }
}
