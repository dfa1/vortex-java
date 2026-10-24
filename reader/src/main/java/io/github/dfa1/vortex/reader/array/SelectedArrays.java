package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.io.VortexFormat;
import io.github.dfa1.vortex.core.model.DType;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.ValueLayout;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.LongBinaryOperator;

/// Row selection: an array of some rows of another array, in the given order. Leaf arrays get an
/// index-mapped view, `get(i) = inner.get(rows[i])`, with no copy; composite arrays select their
/// children. This is how a filtered scan returns only the rows that match, as Rust's scan does.
public final class SelectedArrays {

    private SelectedArrays() {
    }

    /// The rows `rows[0] .. rows[rows.length - 1]` of `full`, in that order.
    ///
    /// @param full  the array to select from
    /// @param rows  row indices into `full`, each in `[0, full.length())`
    /// @param arena allocator for the per-row offsets a list selection rebuilds
    /// @return an array of `rows.length` rows with `full`'s dtype
    /// @throws VortexException if `full`'s array type has no row selection
    public static Array select(Array full, long[] rows, SegmentAllocator arena) {
        long n = rows.length;
        return switch (full) {
            case MaskedArray m -> {
                BoolArray validity = null;
                if (m.validity() != null) {
                    validity = (BoolArray) select(m.validity(), rows, arena);
                }
                yield new MaskedArray(select(m.inner(), rows, arena), validity);
            }
            // A slice selects from the array it slices, so a dictionary under it stays one.
            case OffsetLongArray o -> select(o.inner(), shifted(rows, o.offset()), arena);
            case OffsetIntArray o -> select(o.inner(), shifted(rows, o.offset()), arena);
            case OffsetShortArray o -> select(o.inner(), shifted(rows, o.offset()), arena);
            case OffsetByteArray o -> select(o.inner(), shifted(rows, o.offset()), arena);
            case OffsetDoubleArray o -> select(o.inner(), shifted(rows, o.offset()), arena);
            case OffsetFloatArray o -> select(o.inner(), shifted(rows, o.offset()), arena);
            case OffsetBoolArray o -> select(o.inner(), shifted(rows, o.offset()), arena);
            case VarBinSlicedArray s -> select(s.inner(), shifted(rows, s.offset()), arena);
            // A dictionary keeps its value pool and selects its codes, so the compute kernels'
            // dictionary lanes (code scans, per-entry string tests) still apply to the selection.
            case VarBinDictArray d -> new VarBinDictArray(d.dtype(), n, d.bytesSegment(), d.dictValOffsets(),
                    d.dictValOffPType(), gather(d.dictCodesSegs(), d.dictCodesPType().byteSize(), rows, arena),
                    d.dictCodesPType());
            case DictLongArray d -> DictLongArray.of(d.dtype(), n, d.values(), codes(d.codes(), rows, arena));
            case DictIntArray d -> DictIntArray.of(d.dtype(), n, d.values(), codes(d.codes(), rows, arena));
            case DictShortArray d -> DictShortArray.of(d.dtype(), n, d.values(), codes(d.codes(), rows, arena));
            case DictByteArray d -> DictByteArray.of(d.dtype(), n, d.values(), codes(d.codes(), rows, arena));
            case DictDoubleArray d -> DictDoubleArray.of(d.dtype(), n, d.values(), codes(d.codes(), rows, arena));
            case DictFloatArray d -> DictFloatArray.of(d.dtype(), n, d.values(), codes(d.codes(), rows, arena));
            case DictFloat16Array d -> DictFloat16Array.of(d.dtype(), n, d.values(), codes(d.codes(), rows, arena));
            case LongArray a -> new Longs(a, rows);
            case IntArray a -> new Ints(a, rows);
            case ShortArray a -> new Shorts(a, rows);
            case ByteArray a -> new Bytes(a, rows);
            case DoubleArray a -> new Doubles(a, rows);
            case FloatArray a -> new Floats(a, rows);
            case Float16Array a -> new Float16s(a, rows);
            case BoolArray a -> new Bools(a, rows);
            case VarBinArray a -> new VarBins(a, rows);
            case DecimalArray a -> new Decimals(a, rows);
            case NullArray a -> new NullArray(a.dtype(), n);
            case StructArray s -> {
                List<Array> fields = new ArrayList<>(s.fieldCount());
                for (int i = 0; i < s.fieldCount(); i++) {
                    fields.add(select(s.field(i), rows, arena));
                }
                yield new StructArray((DType.Struct) s.dtype(), n, fields);
            }
            case FixedSizeListArray a -> {
                // a row's elements sit at row * fixedSize, so select those element rows
                int size = a.fixedSize();
                long[] elementRows = new long[Math.multiplyExact(rows.length, size)];
                for (int k = 0; k < rows.length; k++) {
                    for (int j = 0; j < size; j++) {
                        elementRows[k * size + j] = rows[k] * size + j;
                    }
                }
                yield new FixedSizeListArray((DType.FixedSizeList) a.dtype(), n, select(a.elements(), elementRows, arena));
            }
            case ListArray a -> listView(a, rows, arena);
            case ListViewArray a -> new ListViewArray((DType.List) a.dtype(), n, a.elements(),
                    select(a.offsets(), rows, arena), select(a.sizes(), rows, arena));
            case MapArray a -> new MapArray((DType.Map) a.dtype(), n, select(a.entries(), rows, arena));
            case UnionArray a -> {
                List<Array> variants = new ArrayList<>(a.variantCount());
                for (int i = 0; i < a.variantCount(); i++) {
                    variants.add(select(a.variant(i), rows, arena));
                }
                yield new UnionArray((DType.Union) a.dtype(), n, select(a.typeIds(), rows, arena), variants);
            }
            default -> throw new VortexException(
                    "scan: cannot select rows of " + full.getClass().getSimpleName());
        };
    }

    private static long[] shifted(long[] rows, long offset) {
        long[] out = new long[rows.length];
        for (int i = 0; i < rows.length; i++) {
            out[i] = rows[i] + offset;
        }
        return out;
    }

    /// The selected codes in a flat buffer of their own width, the shape the dictionary lanes scan
    /// fastest; a code array is one byte to eight per row, so the copy is small.
    private static Array codes(Array codes, long[] rows, SegmentAllocator arena) {
        long n = rows.length;
        return switch (codes) {
            case ByteArray c -> {
                MemorySegment out = arena.allocate(Math.max(1, n));
                for (int i = 0; i < rows.length; i++) {
                    out.set(ValueLayout.JAVA_BYTE, i, c.getByte(rows[i]));
                }
                yield new MaterializedByteArray(c.dtype(), n, out);
            }
            case ShortArray c -> {
                MemorySegment out = arena.allocate(Math.max(2, n * 2), 2);
                for (int i = 0; i < rows.length; i++) {
                    out.setAtIndex(VortexFormat.LE_SHORT, i, c.getShort(rows[i]));
                }
                yield new MaterializedShortArray(c.dtype(), n, out);
            }
            case IntArray c -> {
                MemorySegment out = arena.allocate(Math.max(4, n * 4), 4);
                for (int i = 0; i < rows.length; i++) {
                    out.setAtIndex(VortexFormat.LE_INT, i, c.getInt(rows[i]));
                }
                yield new MaterializedIntArray(c.dtype(), n, out);
            }
            case LongArray c -> {
                MemorySegment out = arena.allocate(Math.max(8, n * 8), 8);
                for (int i = 0; i < rows.length; i++) {
                    out.setAtIndex(VortexFormat.LE_LONG, i, c.getLong(rows[i]));
                }
                yield new MaterializedLongArray(c.dtype(), n, out);
            }
            default -> throw new VortexException("dictionary codes of type " + codes.dtype() + " are not integers");
        };
    }

    /// The `width`-byte elements of `src` at `rows`, in a new buffer.
    private static MemorySegment gather(MemorySegment src, int width, long[] rows, SegmentAllocator arena) {
        MemorySegment out = arena.allocate(Math.max(width, (long) rows.length * width), width);
        for (int i = 0; i < rows.length; i++) {
            MemorySegment.copy(src, rows[i] * width, out, (long) i * width, width);
        }
        return out;
    }

    /// A list's rows address its elements through `offsets[row] .. offsets[row + 1]`, which a
    /// selection breaks apart, so the selected rows become a list view over the same elements.
    private static Array listView(ListArray list, long[] rows, SegmentAllocator arena) {
        MemorySegment starts = arena.allocate(rows.length * 8L, 8);
        MemorySegment sizes = arena.allocate(rows.length * 8L, 8);
        for (int k = 0; k < rows.length; k++) {
            long start = offsetAt(list.offsets(), rows[k]);
            starts.setAtIndex(VortexFormat.LE_LONG, k, start);
            sizes.setAtIndex(VortexFormat.LE_LONG, k, offsetAt(list.offsets(), rows[k] + 1) - start);
        }
        return new ListViewArray((DType.List) list.dtype(), rows.length, list.elements(),
                new MaterializedLongArray(DType.I64, rows.length, starts),
                new MaterializedLongArray(DType.I64, rows.length, sizes));
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

    private record Longs(LongArray inner, long[] rows) implements LongArray {
        @Override
        public long length() {
            return rows.length;
        }

        @Override
        public DType dtype() {
            return inner.dtype();
        }

        @Override
        public long getLong(long i) {
            return inner.getLong(rows[(int) i]);
        }
    }

    private record Ints(IntArray inner, long[] rows) implements IntArray {
        @Override
        public long length() {
            return rows.length;
        }

        @Override
        public DType dtype() {
            return inner.dtype();
        }

        @Override
        public int getInt(long i) {
            return inner.getInt(rows[(int) i]);
        }
    }

    private record Shorts(ShortArray inner, long[] rows) implements ShortArray {
        @Override
        public long length() {
            return rows.length;
        }

        @Override
        public DType dtype() {
            return inner.dtype();
        }

        @Override
        public short getShort(long i) {
            return inner.getShort(rows[(int) i]);
        }

        @Override
        public long fold(long identity, LongBinaryOperator op) {
            long acc = identity;
            for (long i = 0; i < rows.length; i++) {
                acc = op.applyAsLong(acc, getShort(i));
            }
            return acc;
        }
    }

    private record Bytes(ByteArray inner, long[] rows) implements ByteArray {
        @Override
        public long length() {
            return rows.length;
        }

        @Override
        public DType dtype() {
            return inner.dtype();
        }

        @Override
        public byte getByte(long i) {
            return inner.getByte(rows[(int) i]);
        }

        @Override
        public long fold(long identity, LongBinaryOperator op) {
            long acc = identity;
            for (long i = 0; i < rows.length; i++) {
                acc = op.applyAsLong(acc, getByte(i));
            }
            return acc;
        }
    }

    private record Doubles(DoubleArray inner, long[] rows) implements DoubleArray {
        @Override
        public long length() {
            return rows.length;
        }

        @Override
        public DType dtype() {
            return inner.dtype();
        }

        @Override
        public double getDouble(long i) {
            return inner.getDouble(rows[(int) i]);
        }
    }

    private record Floats(FloatArray inner, long[] rows) implements FloatArray {
        @Override
        public long length() {
            return rows.length;
        }

        @Override
        public DType dtype() {
            return inner.dtype();
        }

        @Override
        public float getFloat(long i) {
            return inner.getFloat(rows[(int) i]);
        }
    }

    private record Float16s(Float16Array inner, long[] rows) implements Float16Array {
        @Override
        public long length() {
            return rows.length;
        }

        @Override
        public DType dtype() {
            return inner.dtype();
        }

        @Override
        public float getFloat(long i) {
            return inner.getFloat(rows[(int) i]);
        }

        @Override
        public Array limited(long n) {
            return new Float16s(inner, Arrays.copyOf(rows, (int) n));
        }

        /// Re-encodes each selected half from its widened value, which is exact: every half is a
        /// float.
        @Override
        public MemorySegment materialize(SegmentAllocator arena) {
            MemorySegment out = arena.allocate(rows.length * 2L, 2);
            for (int i = 0; i < rows.length; i++) {
                out.setAtIndex(VortexFormat.LE_SHORT, i, Float.floatToFloat16(getFloat(i)));
            }
            return out;
        }
    }

    private record Bools(BoolArray inner, long[] rows) implements BoolArray {
        @Override
        public long length() {
            return rows.length;
        }

        @Override
        public DType dtype() {
            return inner.dtype();
        }

        @Override
        public boolean getBoolean(long i) {
            return inner.getBoolean(rows[(int) i]);
        }
    }

    private record VarBins(VarBinArray inner, long[] rows) implements VarBinArray {
        @Override
        public long length() {
            return rows.length;
        }

        @Override
        public DType dtype() {
            return inner.dtype();
        }

        @Override
        public MemorySegment bytesSegment() {
            return inner.bytesSegment();
        }

        @Override
        public byte[] getBytes(long i) {
            return inner.getBytes(rows[(int) i]);
        }

        @Override
        public String getString(long i) {
            return inner.getString(rows[(int) i]);
        }

        @Override
        public int compareUnsigned(long i, byte[] other) {
            return inner.compareUnsigned(rows[(int) i], other);
        }

        @Override
        public int getByteLength(long i) {
            return inner.getByteLength(rows[(int) i]);
        }

        @Override
        public void forEachByteLength(IntConsumer c) {
            for (int i = 0; i < rows.length; i++) {
                c.accept(getByteLength(i));
            }
        }

        @Override
        public VarBinArray limited(long n) {
            return new VarBins(inner, Arrays.copyOf(rows, (int) n));
        }
    }

    private record Decimals(DecimalArray inner, long[] rows) implements DecimalArray {
        @Override
        public long length() {
            return rows.length;
        }

        @Override
        public DType dtype() {
            return inner.dtype();
        }

        @Override
        public BigDecimal getDecimal(long i) {
            return inner.getDecimal(rows[(int) i]);
        }

        @Override
        public long getUnscaledLong(long i) {
            return inner.getUnscaledLong(rows[(int) i]);
        }

        @Override
        public Array limited(long n) {
            return new Decimals(inner, Arrays.copyOf(rows, (int) n));
        }

        /// Gathers each selected row's little-endian unscaled bytes from the inner buffer.
        @Override
        public MemorySegment materialize(SegmentAllocator arena) {
            int width = ((DType.Decimal) inner.dtype()).byteWidth();
            MemorySegment src = inner.materialize(arena);
            MemorySegment out = arena.allocate((long) rows.length * width, Math.min(width, 16));
            for (int i = 0; i < rows.length; i++) {
                MemorySegment.copy(src, rows[i] * width, out, (long) i * width, width);
            }
            return out;
        }
    }
}
