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

/// Row selection: the given rows of another array, copied into flat buffers, as Rust's `filter`
/// does. Leaf arrays become materialized arrays, so the flat fast paths apply to a filtered column;
/// a dictionary copies only its codes; composite arrays select their children. This is how a
/// filtered scan returns only the rows that match, as Rust's scan does.
///
/// The rows are a sorted `int[]`, one entry per selected row (roaring's array container): a copy
/// loop reads them straight, where a bitmap makes every walk pay for its empty words.
public final class SelectedArrays {

    private SelectedArrays() {
    }

    /// The rows `rows[0] .. rows[rows.length - 1]` of `full`, in that order.
    ///
    /// @param full  the array to select from
    /// @param rows  row indices into `full`, strictly ascending, each in `[0, full.length())`
    /// @param arena allocator for the copied rows
    /// @return an array of `rows.length` rows with `full`'s dtype
    /// @throws VortexException if `full`'s array type has no row selection
    public static Array select(Array full, int[] rows, SegmentAllocator arena) {
        return select(full, rows, 0, arena);
    }

    /// The rows `rows[k] + offset` of `full`: an offset slice selects from the array it slices, so a
    /// dictionary under it stays one.
    // ponytail: a flat buffer copies by a typed gather or by runs, by density, anything else by a
    // per-row walk; Rust also has a SIMD compress (vortex-array filter/execute/buffer.rs), worth it
    // if filtered scans profile hot here
    private static Array select(Array full, int[] rows, long offset, SegmentAllocator arena) {
        int n = rows.length;
        return switch (full) {
            case MaskedArray m -> {
                BoolArray validity = null;
                if (m.validity() != null) {
                    validity = (BoolArray) select(m.validity(), rows, offset, arena);
                }
                yield new MaskedArray(select(m.inner(), rows, offset, arena), validity);
            }
            case OffsetLongArray o -> select(o.inner(), rows, offset + o.offset(), arena);
            case OffsetIntArray o -> select(o.inner(), rows, offset + o.offset(), arena);
            case OffsetShortArray o -> select(o.inner(), rows, offset + o.offset(), arena);
            case OffsetByteArray o -> select(o.inner(), rows, offset + o.offset(), arena);
            case OffsetDoubleArray o -> select(o.inner(), rows, offset + o.offset(), arena);
            case OffsetFloatArray o -> select(o.inner(), rows, offset + o.offset(), arena);
            case OffsetBoolArray o -> select(o.inner(), rows, offset + o.offset(), arena);
            case VarBinSlicedArray s -> select(s.inner(), rows, offset + s.offset(), arena);
            // A dictionary keeps its value pool and selects its codes, so the compute kernels'
            // dictionary lanes (code scans, per-entry string tests) still apply to the selection.
            case VarBinDictArray d -> new VarBinDictArray(d.dtype(), n, d.bytesSegment(), d.dictValOffsets(),
                    d.dictValOffPType(), copy(d.dictCodesSegs(), d.dictCodesPType().byteSize(), rows, offset, arena),
                    d.dictCodesPType());
            case DictLongArray d -> DictLongArray.of(d.dtype(), n, d.values(), codes(d.codes(), rows, offset, arena));
            case DictIntArray d -> DictIntArray.of(d.dtype(), n, d.values(), codes(d.codes(), rows, offset, arena));
            case DictShortArray d -> DictShortArray.of(d.dtype(), n, d.values(), codes(d.codes(), rows, offset, arena));
            case DictByteArray d -> DictByteArray.of(d.dtype(), n, d.values(), codes(d.codes(), rows, offset, arena));
            case DictDoubleArray d -> DictDoubleArray.of(d.dtype(), n, d.values(), codes(d.codes(), rows, offset, arena));
            case DictFloatArray d -> DictFloatArray.of(d.dtype(), n, d.values(), codes(d.codes(), rows, offset, arena));
            case DictFloat16Array d -> DictFloat16Array.of(d.dtype(), n, d.values(), codes(d.codes(), rows, offset, arena));
            case LongArray a -> new MaterializedLongArray(a.dtype(), n, longs(a, rows, offset, arena));
            case IntArray a -> new MaterializedIntArray(a.dtype(), n, ints(a, rows, offset, arena));
            case ShortArray a -> new MaterializedShortArray(a.dtype(), n, shorts(a, rows, offset, arena));
            case ByteArray a -> new MaterializedByteArray(a.dtype(), n, bytes(a, rows, offset, arena));
            case DoubleArray a -> new MaterializedDoubleArray(a.dtype(), n, doubles(a, rows, offset, arena));
            case FloatArray a -> new MaterializedFloatArray(a.dtype(), n, floats(a, rows, offset, arena));
            case Float16Array a -> new MaterializedFloat16Array(a.dtype(), n, halves(a, rows, offset, arena));
            case BoolArray a -> new MaterializedBoolArray(a.dtype(), n, bools(a, rows, offset, arena));
            case VarBinArray a -> strings(a, rows, offset, arena);
            case DecimalArray a -> decimals(a, rows, offset, arena);
            case NullArray a -> new NullArray(a.dtype(), n);
            case StructArray s -> {
                List<Array> fields = new ArrayList<>(s.fieldCount());
                for (int i = 0; i < s.fieldCount(); i++) {
                    fields.add(select(s.field(i), rows, offset, arena));
                }
                yield new StructArray((DType.Struct) s.dtype(), n, fields);
            }
            case FixedSizeListArray a -> {
                // a row's elements sit at row * fixedSize, so select those element rows
                int size = a.fixedSize();
                int[] elementRows = new int[Math.multiplyExact(n, size)];
                for (int k = 0; k < n; k++) {
                    int start = Math.multiplyExact(rows[k], size);
                    for (int j = 0; j < size; j++) {
                        elementRows[k * size + j] = start + j;
                    }
                }
                yield new FixedSizeListArray((DType.FixedSizeList) a.dtype(), n,
                        select(a.elements(), elementRows, offset * size, arena));
            }
            case ListArray a -> listView(a, rows, offset, arena);
            case ListViewArray a -> new ListViewArray((DType.List) a.dtype(), n, a.elements(),
                    select(a.offsets(), rows, offset, arena), select(a.sizes(), rows, offset, arena));
            case MapArray a -> new MapArray((DType.Map) a.dtype(), n, select(a.entries(), rows, offset, arena));
            case UnionArray a -> {
                List<Array> variants = new ArrayList<>(a.variantCount());
                for (int i = 0; i < a.variantCount(); i++) {
                    variants.add(select(a.variant(i), rows, offset, arena));
                }
                yield new UnionArray((DType.Union) a.dtype(), n, select(a.typeIds(), rows, offset, arena), variants);
            }
            default -> throw new VortexException(
                    "scan: cannot select rows of " + full.getClass().getSimpleName());
        };
    }

    /// The selected codes in a flat buffer of their own width, the shape the dictionary lanes scan
    /// fastest; a code array is one byte to eight per row, so the copy is small.
    private static Array codes(Array codes, int[] rows, long offset, SegmentAllocator arena) {
        int n = rows.length;
        return switch (codes) {
            case ByteArray c -> new MaterializedByteArray(c.dtype(), n, bytes(c, rows, offset, arena));
            case ShortArray c -> new MaterializedShortArray(c.dtype(), n, shorts(c, rows, offset, arena));
            case IntArray c -> new MaterializedIntArray(c.dtype(), n, ints(c, rows, offset, arena));
            case LongArray c -> new MaterializedLongArray(c.dtype(), n, longs(c, rows, offset, arena));
            default -> throw new VortexException("dictionary codes of type " + codes.dtype() + " are not integers");
        };
    }

    private static MemorySegment longs(LongArray a, int[] rows, long offset, SegmentAllocator arena) {
        MemorySegment src = flat(a);
        if (src != null) {
            return copy(src, 8, rows, offset, arena);
        }
        MemorySegment out = arena.allocate(Math.max(8, rows.length * 8L), 8);
        for (int k = 0; k < rows.length; k++) {
            out.setAtIndex(VortexFormat.LE_LONG, k, a.getLong(rows[k] + offset));
        }
        return out;
    }

    private static MemorySegment ints(IntArray a, int[] rows, long offset, SegmentAllocator arena) {
        MemorySegment src = flat(a);
        if (src != null) {
            return copy(src, 4, rows, offset, arena);
        }
        MemorySegment out = arena.allocate(Math.max(4, rows.length * 4L), 4);
        for (int k = 0; k < rows.length; k++) {
            out.setAtIndex(VortexFormat.LE_INT, k, a.getInt(rows[k] + offset));
        }
        return out;
    }

    private static MemorySegment shorts(ShortArray a, int[] rows, long offset, SegmentAllocator arena) {
        MemorySegment src = flat(a);
        if (src != null) {
            return copy(src, 2, rows, offset, arena);
        }
        MemorySegment out = arena.allocate(Math.max(2, rows.length * 2L), 2);
        for (int k = 0; k < rows.length; k++) {
            out.setAtIndex(VortexFormat.LE_SHORT, k, a.getShort(rows[k] + offset));
        }
        return out;
    }

    private static MemorySegment bytes(ByteArray a, int[] rows, long offset, SegmentAllocator arena) {
        MemorySegment src = flat(a);
        if (src != null) {
            return copy(src, 1, rows, offset, arena);
        }
        MemorySegment out = arena.allocate(Math.max(1, rows.length));
        for (int k = 0; k < rows.length; k++) {
            out.set(ValueLayout.JAVA_BYTE, k, a.getByte(rows[k] + offset));
        }
        return out;
    }

    private static MemorySegment doubles(DoubleArray a, int[] rows, long offset, SegmentAllocator arena) {
        MemorySegment src = flat(a);
        if (src != null) {
            return copy(src, 8, rows, offset, arena);
        }
        MemorySegment out = arena.allocate(Math.max(8, rows.length * 8L), 8);
        for (int k = 0; k < rows.length; k++) {
            out.setAtIndex(VortexFormat.LE_DOUBLE, k, a.getDouble(rows[k] + offset));
        }
        return out;
    }

    private static MemorySegment floats(FloatArray a, int[] rows, long offset, SegmentAllocator arena) {
        MemorySegment src = flat(a);
        if (src != null) {
            return copy(src, 4, rows, offset, arena);
        }
        MemorySegment out = arena.allocate(Math.max(4, rows.length * 4L), 4);
        for (int k = 0; k < rows.length; k++) {
            out.setAtIndex(VortexFormat.LE_FLOAT, k, a.getFloat(rows[k] + offset));
        }
        return out;
    }

    /// Re-encodes each selected half from its widened value, which is exact: every half is a float.
    private static MemorySegment halves(Float16Array a, int[] rows, long offset, SegmentAllocator arena) {
        MemorySegment src = flat(a);
        if (src != null) {
            return copy(src, 2, rows, offset, arena);
        }
        MemorySegment out = arena.allocate(Math.max(2, rows.length * 2L), 2);
        for (int k = 0; k < rows.length; k++) {
            out.setAtIndex(VortexFormat.LE_SHORT, k, Float.floatToFloat16(a.getFloat(rows[k] + offset)));
        }
        return out;
    }

    private static MemorySegment bools(BoolArray a, int[] rows, long offset, SegmentAllocator arena) {
        // a SegmentAllocator need not zero what it hands out, and only the set bits are written
        MemorySegment out = arena.allocate(Math.max(1, (rows.length + 7L) >>> 3)).fill((byte) 0);
        for (int k = 0; k < rows.length; k++) {
            if (a.getBoolean(rows[k] + offset)) {
                long at = (long) k >>> 3;
                out.set(ValueLayout.JAVA_BYTE, at, (byte) (out.get(ValueLayout.JAVA_BYTE, at) | 1 << (k & 7)));
            }
        }
        return out;
    }

    /// The selected strings, bytes and I64 offsets in fresh buffers.
    private static VarBinOffsetArray strings(VarBinArray a, int[] rows, long offset, SegmentAllocator arena) {
        long totalBytes = 0;
        for (int row : rows) {
            totalBytes += a.getByteLength(row + offset);
        }
        MemorySegment outBytes = arena.allocate(Math.max(1, totalBytes));
        MemorySegment outOffsets = arena.allocate((rows.length + 1L) * Long.BYTES, Long.BYTES);
        outOffsets.setAtIndex(VortexFormat.LE_LONG, 0, 0L);
        long at = 0;
        for (int k = 0; k < rows.length; k++) {
            byte[] b = a.getBytes(rows[k] + offset);
            MemorySegment.copy(MemorySegment.ofArray(b), 0, outBytes, at, b.length);
            at += b.length;
            outOffsets.setAtIndex(VortexFormat.LE_LONG, k + 1L, at);
        }
        return new VarBinOffsetArray(a.dtype(), rows.length, outBytes.asReadOnly(), outOffsets, PType.I64);
    }

    /// Writes each selected row's unscaled value as a little-endian two's-complement integer of the
    /// dtype's width, read through the accessors: not every decimal array has a buffer to gather
    /// from (`vortex.decimal_byte_parts` keeps its value in parts).
    private static LazyDecimalArray decimals(DecimalArray a, int[] rows, long offset, SegmentAllocator arena) {
        int width = ((DType.Decimal) a.dtype()).byteWidth();
        MemorySegment out = arena.allocate(Math.max(width, (long) rows.length * width), Math.min(width, 16));
        long at = 0;
        for (int row : rows) {
            long r = row + offset;
            switch (width) {
                case 1 -> out.set(ValueLayout.JAVA_BYTE, at, (byte) a.getUnscaledLong(r));
                case 2 -> out.set(VortexFormat.LE_SHORT, at, (short) a.getUnscaledLong(r));
                case 4 -> out.set(VortexFormat.LE_INT, at, (int) a.getUnscaledLong(r));
                case 8 -> out.set(VortexFormat.LE_LONG, at, a.getUnscaledLong(r));
                default -> {
                    // big-endian minimal bytes, reversed and sign-extended to the width
                    byte[] be = a.getDecimal(r).unscaledValue().toByteArray();
                    byte sign = (byte) (be[0] < 0 ? -1 : 0);
                    for (int b = 0; b < width; b++) {
                        out.set(ValueLayout.JAVA_BYTE, at + b, b < be.length ? be[be.length - 1 - b] : sign);
                    }
                }
            }
            at += width;
        }
        return new LazyDecimalArray(a.dtype(), rows.length, out, width);
    }

    /// The flat buffer of a materialized primitive array, one element per row, or `null`: a bool
    /// buffer packs eight rows a byte, and a broadcast constant holds one element for every row.
    private static MemorySegment flat(Array a) {
        if (a instanceof AbstractMaterializedArray m && !(a instanceof BoolArray) && m.elementCount == m.length) {
            return m.buffer;
        }
        return null;
    }

    /// The `width`-byte elements of `src` at the selected rows, in a new buffer, the way Rust's
    /// filter picks by density: a sparse selection gathers each row with a typed load and store; a
    /// dense one copies each run of consecutive rows in bulk, so it costs a few copies, not one a row.
    private static MemorySegment copy(MemorySegment src, int width, int[] rows, long offset, SegmentAllocator arena) {
        int n = rows.length;
        MemorySegment out = arena.allocate(Math.max(width, (long) n * width), Math.min(width, 16));
        if (n == 0) {
            return out;
        }
        if (n * 2L <= (long) rows[n - 1] - rows[0] + 1) {
            gather(src, width, rows, offset, out);
            return out;
        }
        int k = 0;
        while (k < n) {
            int end = k + 1;
            while (end < n && rows[end] == rows[end - 1] + 1) {
                end++;
            }
            MemorySegment.copy(src, (rows[k] + offset) * width, out, (long) k * width, (long) (end - k) * width);
            k = end;
        }
        return out;
    }

    private static void gather(MemorySegment src, int width, int[] rows, long offset, MemorySegment out) {
        switch (width) {
            case 1 -> {
                for (int k = 0; k < rows.length; k++) {
                    out.set(ValueLayout.JAVA_BYTE, k, src.get(ValueLayout.JAVA_BYTE, rows[k] + offset));
                }
            }
            case 2 -> {
                for (int k = 0; k < rows.length; k++) {
                    out.setAtIndex(VortexFormat.LE_SHORT, k, src.getAtIndex(VortexFormat.LE_SHORT, rows[k] + offset));
                }
            }
            case 4 -> {
                for (int k = 0; k < rows.length; k++) {
                    out.setAtIndex(VortexFormat.LE_INT, k, src.getAtIndex(VortexFormat.LE_INT, rows[k] + offset));
                }
            }
            case 8 -> {
                for (int k = 0; k < rows.length; k++) {
                    out.setAtIndex(VortexFormat.LE_LONG, k, src.getAtIndex(VortexFormat.LE_LONG, rows[k] + offset));
                }
            }
            default -> {
                for (int k = 0; k < rows.length; k++) {
                    MemorySegment.copy(src, (rows[k] + offset) * width, out, (long) k * width, width);
                }
            }
        }
    }

    /// A list's rows address its elements through `offsets[row] .. offsets[row + 1]`, which a
    /// selection breaks apart, so the selected rows become a list view over the same elements.
    private static Array listView(ListArray list, int[] rows, long offset, SegmentAllocator arena) {
        int n = rows.length;
        MemorySegment starts = arena.allocate(Math.max(8, n * 8L), 8);
        MemorySegment sizes = arena.allocate(Math.max(8, n * 8L), 8);
        for (int k = 0; k < n; k++) {
            long row = rows[k] + offset;
            long start = offsetAt(list.offsets(), row);
            starts.setAtIndex(VortexFormat.LE_LONG, k, start);
            sizes.setAtIndex(VortexFormat.LE_LONG, k, offsetAt(list.offsets(), row + 1) - start);
        }
        return new ListViewArray((DType.List) list.dtype(), n, list.elements(),
                new MaterializedLongArray(DType.I64, n, starts),
                new MaterializedLongArray(DType.I64, n, sizes));
    }

    private static long offsetAt(Array offsets, long i) {
        boolean unsigned = offsets.dtype().isUnsigned();
        return switch (offsets) {
            case LongArray a -> a.getLong(i);
            case IntArray a when unsigned -> Integer.toUnsignedLong(a.getInt(i));
            case IntArray a -> a.getInt(i);
            case ShortArray a when unsigned -> Short.toUnsignedLong(a.getShort(i));
            case ShortArray a -> a.getShort(i);
            case ByteArray a when unsigned -> Byte.toUnsignedLong(a.getByte(i));
            case ByteArray a -> a.getByte(i);
            default -> throw new VortexException("list offsets of type " + offsets.dtype() + " are not integers");
        };
    }
}
