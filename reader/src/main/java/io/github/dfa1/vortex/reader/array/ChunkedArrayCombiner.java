package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.io.VortexFormat;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;

/// Stitches the per-chunk arrays of one logical column into a single view, dispatching on the
/// column's [DType]. Each family gets its zero-copy composite shape (ADR 0012): primitive and
/// boolean chunks fold into the `ChunkedXxxArray` records, variable-length chunks into
/// [VarBinArray.ChunkedMode], and list chunks into a stitched [ListArray] whose bulk element data
/// stays zero-copy (the child element arrays are themselves combined recursively) while only the
/// small outer offsets table is rebuilt so per-chunk offsets — which each reset to zero — become one
/// cumulative table.
///
/// Extracted from `ChunkedLayoutDecoder` so the same dispatch drives both the top-level chunked
/// column and the recursive combine of a chunked list's elements. Every unsupported dtype fails with
/// a [VortexException] rather than a raw `ClassCastException` — the reader parses untrusted input and
/// must never leak a JDK runtime exception (issue #268).
public final class ChunkedArrayCombiner {

    private ChunkedArrayCombiner() {
    }

    /// Combines the per-chunk arrays of one column into a single logical array, choosing the shape
    /// from `dtype`. Masked chunks keep their per-chunk validity: it is concatenated into one
    /// row-level bitmap and the result re-wrapped in a [MaskedArray], so a nullable column that
    /// spans more than one chunk retains its nulls.
    ///
    /// @param dtype     the column's logical type
    /// @param totalRows the total logical row count across all chunks
    /// @param chunks    the decoded per-chunk arrays, in row order (non-empty)
    /// @param arena     allocator for the combined validity bitmap and, for lists, the rebuilt offsets
    /// @return the combined [Array], masked when any chunk contributes nulls
    /// @throws VortexException on an empty chunk list or a dtype with no chunked shape
    public static Array combine(DType dtype, long totalRows, List<Array> chunks,
            SegmentAllocator arena) {
        if (chunks.isEmpty()) {
            throw new VortexException("chunked combine: empty chunk list");
        }
        Array data = switch (dtype) {
            case DType.Bool ignored -> ChunkedBoolArray.of(dtype, totalRows, chunks);
            case DType.Utf8 ignored -> VarBinArray.ChunkedMode.of(dtype, totalRows, chunks, arena);
            case DType.Binary ignored -> VarBinArray.ChunkedMode.of(dtype, totalRows, chunks, arena);
            case DType.List list -> combineLists(list, totalRows, chunks, arena);
            case DType.Primitive prim -> combinePrimitive(prim.ptype(), dtype, totalRows, chunks);
            default -> throw new VortexException("unsupported dtype for chunked layout: " + dtype);
        };
        BoolArray validity = combineChunkValidity(chunks, totalRows, arena);
        return validity != null ? new MaskedArray(data, validity) : data;
    }

    private static Array combinePrimitive(PType ptype, DType dtype, long totalRows,
            List<Array> chunks) {
        return switch (ptype) {
            case I64, U64 -> ChunkedLongArray.of(dtype, totalRows, chunks);
            case I32, U32 -> ChunkedIntArray.of(dtype, totalRows, chunks);
            case F64 -> ChunkedDoubleArray.of(dtype, totalRows, chunks);
            case F32 -> ChunkedFloatArray.of(dtype, totalRows, chunks);
            case I16, U16 -> ChunkedShortArray.of(dtype, totalRows, chunks);
            case I8, U8 -> ChunkedByteArray.of(dtype, totalRows, chunks);
            default -> throw new VortexException("unsupported ptype for chunked layout: " + ptype);
        };
    }

    /// Stitches per-chunk [ListArray] chunks into one [ListArray]. The element arrays of every chunk
    /// are combined recursively (staying zero-copy), and one cumulative I64 offsets table of
    /// `totalRows + 1` entries is built in `arena`: each chunk's local offsets are shifted by the
    /// number of elements contributed by earlier chunks, since per-chunk offsets each restart at
    /// zero.
    ///
    /// @param dtype     the list's logical type
    /// @param totalRows the total outer-list row count across all chunks
    /// @param chunks    the per-chunk list arrays (each a [ListArray], possibly wrapped [MaskedArray])
    /// @param arena     allocator for the rebuilt offsets segment
    /// @return a single [ListArray] over the combined chunks
    private static ListArray combineLists(DType.List dtype, long totalRows, List<Array> chunks,
            SegmentAllocator arena) {
        var listChunks = new ArrayList<ListArray>(chunks.size());
        for (Array chunk : chunks) {
            Array unwrapped = chunk instanceof MaskedArray m ? m.inner() : chunk;
            if (unwrapped instanceof ListArray la) {
                listChunks.add(la);
            } else {
                throw new VortexException("chunked list: chunk is not a ListArray: "
                        + unwrapped.getClass().getSimpleName());
            }
        }
        long outerRows = 0;
        for (ListArray la : listChunks) {
            outerRows += la.length();
        }
        if (outerRows != totalRows) {
            throw new VortexException("chunked list: chunk rows sum to " + outerRows
                    + ", expected " + totalRows);
        }

        var elementChunks = new ArrayList<Array>(listChunks.size());
        for (ListArray la : listChunks) {
            elementChunks.add(la.elements());
        }
        Array combinedElements = combine(dtype.elementType(), sumLengths(elementChunks),
                elementChunks, arena);

        MemorySegment offsets = arena.allocate((totalRows + 1) * Long.BYTES, Long.BYTES);
        offsets.setAtIndex(VortexFormat.LE_LONG, 0, 0L);
        long outRow = 0;
        long elementBase = 0;
        for (ListArray la : listChunks) {
            long localRows = la.length();
            Array localOffsets = la.offsets();
            for (long i = 0; i < localRows; i++) {
                long localEnd = readOffset(localOffsets, i + 1);
                offsets.setAtIndex(VortexFormat.LE_LONG, outRow + i + 1, elementBase + localEnd);
            }
            outRow += localRows;
            elementBase += readOffset(localOffsets, localRows);
        }
        Array offsetsArray = new MaterializedLongArray(DType.I64, totalRows + 1, offsets.asReadOnly());
        return new ListArray(dtype, totalRows, combinedElements, offsetsArray);
    }

    private static long sumLengths(List<Array> arrays) {
        long total = 0;
        for (Array a : arrays) {
            total += a.length();
        }
        return total;
    }

    /// Reads offset `idx` from a list offsets array as a non-negative long, widening whatever integer
    /// ptype the encoder chose (it picks the narrowest that fits the max offset).
    ///
    /// @param offsets the offsets array
    /// @param idx     the offset index to read
    /// @return the offset value as a long
    private static long readOffset(Array offsets, long idx) {
        return switch (offsets) {
            case LongArray la -> la.getLong(idx);
            case IntArray ia -> Integer.toUnsignedLong(ia.getInt(idx));
            case ShortArray sa -> sa.getInt(idx);
            case ByteArray ba -> ba.getInt(idx);
            default -> throw new VortexException("unexpected list offsets type: "
                    + offsets.getClass().getSimpleName());
        };
    }

    /// Concatenates the per-chunk validity of masked chunks into one row-level bitmap, or returns
    /// `null` when no chunk is nullable. Unmasked chunks (and masked chunks with a `null` validity,
    /// which mean all-valid) contribute all-valid rows; an entirely-null [NullArray] chunk (#269)
    /// contributes all-invalid rows.
    ///
    /// @param chunkArrays the decoded per-chunk arrays, in row order
    /// @param totalRows    the total logical row count across all chunks
    /// @param arena        allocator for the combined bitmap
    /// @return a bit-packed row-validity [BoolArray] of `totalRows` bits, or `null` if all valid
    private static BoolArray combineChunkValidity(List<Array> chunkArrays, long totalRows,
            SegmentAllocator arena) {
        boolean anyNullable = false;
        for (Array chunk : chunkArrays) {
            if ((chunk instanceof MaskedArray m && m.validity() != null) || chunk instanceof NullArray) {
                anyNullable = true;
                break;
            }
        }
        if (!anyNullable) {
            return null;
        }
        MemorySegment bits = arena.allocate((totalRows + 7) >>> 3);
        long row = 0;
        for (Array chunk : chunkArrays) {
            long chunkLen = chunk.length();
            if (!(chunk instanceof NullArray)) {
                BoolArray validity = chunk instanceof MaskedArray m ? m.validity() : null;
                for (long i = 0; i < chunkLen; i++) {
                    if (validity == null || validity.getBoolean(i)) {
                        long globalRow = row + i;
                        long byteIdx = globalRow >>> 3;
                        byte cur = bits.get(ValueLayout.JAVA_BYTE, byteIdx);
                        bits.set(ValueLayout.JAVA_BYTE, byteIdx,
                                (byte) ((cur & 0xff) | (1 << (globalRow & 7))));
                    }
                }
            }
            row += chunkLen;
        }
        return new MaterializedBoolArray(DType.BOOL, totalRows, bits.asReadOnly());
    }
}
