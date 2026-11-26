package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.io.VortexFormat;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.DTypes;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.proto.ProtoListValue;
import io.github.dfa1.vortex.core.proto.ProtoNullValue;
import io.github.dfa1.vortex.core.proto.ProtoScalarValue;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.BoolArray;
import io.github.dfa1.vortex.reader.array.ByteArray;
import io.github.dfa1.vortex.reader.array.DecimalArray;
import io.github.dfa1.vortex.reader.array.DoubleArray;
import io.github.dfa1.vortex.reader.array.FixedSizeListArray;
import io.github.dfa1.vortex.reader.array.Float16Array;
import io.github.dfa1.vortex.reader.array.FloatArray;
import io.github.dfa1.vortex.reader.array.IntArray;
import io.github.dfa1.vortex.reader.array.LazyDecimalArray;
import io.github.dfa1.vortex.reader.array.ListArray;
import io.github.dfa1.vortex.reader.array.ListViewArray;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.array.MapArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.MaterializedBoolArray;
import io.github.dfa1.vortex.reader.array.MaterializedByteArray;
import io.github.dfa1.vortex.reader.array.MaterializedDoubleArray;
import io.github.dfa1.vortex.reader.array.MaterializedFloat16Array;
import io.github.dfa1.vortex.reader.array.MaterializedFloatArray;
import io.github.dfa1.vortex.reader.array.MaterializedIntArray;
import io.github.dfa1.vortex.reader.array.MaterializedLongArray;
import io.github.dfa1.vortex.reader.array.MaterializedShortArray;
import io.github.dfa1.vortex.reader.array.NullArray;
import io.github.dfa1.vortex.reader.array.ShortArray;
import io.github.dfa1.vortex.reader.array.StructArray;
import io.github.dfa1.vortex.reader.array.VarBinArray;
import io.github.dfa1.vortex.reader.array.VarBinOffsetArray;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.ValueLayout;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/// Arrays of nested dtypes (list, fixed-size list, map, struct) built row by row from scalars,
/// and the scalar of any array row: how `vortex.constant` and `vortex.sparse` decode the nested
/// columns Rust 0.95 writes them for. A nested scalar is a `list_value`: of the elements for a
/// list, of the entries for a map, of the fields for a struct. A scalar with no value is an empty
/// list, or zeros for a fixed-size list or a struct, the placeholder under a null row.
///
/// ponytail: one boxed scalar per element, fine for the constant and sparse chunks it serves;
/// a direct array-to-array gather if a profile ever shows it.
final class ScalarArrays {

    /// A scalar with no value set: `0`, `false`, empty bytes, an empty list.
    static final ProtoScalarValue NO_VALUE = ProtoScalarValue.ofNullValue(null);

    private static final ProtoScalarValue NULL = ProtoScalarValue.ofNullValue(ProtoNullValue.NULL_VALUE);

    private ScalarArrays() {
    }

    /// Whether `dtype` is nested, built here rather than by an encoding's own flat path.
    static boolean isNested(DType dtype) {
        return dtype instanceof DType.List || dtype instanceof DType.FixedSizeList
                || dtype instanceof DType.Map || dtype instanceof DType.Struct;
    }

    /// One row per scalar, of `dtype`; masked when any scalar is null.
    static Array build(SegmentAllocator arena, DType dtype, ProtoScalarValue[] rows) {
        int n = rows.length;
        boolean anyNull = false;
        for (ProtoScalarValue row : rows) {
            anyNull |= row.null_value() != null;
        }
        if (!anyNull) {
            return values(arena, dtype, rows);
        }
        MemorySegment bits = arena.allocate((n + 7L) / 8);
        for (int i = 0; i < n; i++) {
            if (rows[i].null_value() == null) {
                setBit(bits, i);
            }
        }
        return new MaskedArray(values(arena, dtype.withNullable(false), rows),
                new MaterializedBoolArray(DTypes.BOOL, n, bits));
    }

    /// The scalar of row `i`, as Rust serializes it: null for an invalid row.
    static ProtoScalarValue scalarAt(Array array, long i) {
        if (array instanceof MaskedArray masked) {
            if (masked.validity() != null && !masked.validity().getBoolean(i)) {
                return NULL;
            }
            return scalarAt(masked.inner(), i);
        }
        return switch (array) {
            case NullArray _ -> NULL;
            case LongArray a -> ProtoScalarValue.ofInt64Value(a.getLong(i));
            case IntArray a -> ProtoScalarValue.ofInt64Value((long) a.getInt(i));
            case ShortArray a -> ProtoScalarValue.ofInt64Value((long) a.getShort(i));
            case ByteArray a -> ProtoScalarValue.ofInt64Value((long) a.getByte(i));
            case DoubleArray a -> ProtoScalarValue.ofF64Value(a.getDouble(i));
            case FloatArray a -> ProtoScalarValue.ofF32Value(a.getFloat(i));
            case Float16Array a -> ProtoScalarValue.ofF16Value((long) Short.toUnsignedInt(Float.floatToFloat16(a.getFloat(i))));
            case BoolArray a -> ProtoScalarValue.ofBoolValue(a.getBoolean(i));
            case DecimalArray a -> ProtoScalarValue.ofBytesValue(littleEndian(a.getDecimal(i)));
            case VarBinArray a -> ProtoScalarValue.ofBytesValue(a.getBytes(i));
            case FixedSizeListArray a -> list(a.elements(), i * a.fixedSize(), a.fixedSize());
            case ListArray a -> {
                long start = index(a.offsets(), i);
                yield list(a.elements(), start, index(a.offsets(), i + 1) - start);
            }
            case ListViewArray a -> list(a.elements(), index(a.offsets(), i), index(a.sizes(), i));
            case MapArray a -> scalarAt(a.entries(), i);
            case StructArray a -> {
                List<ProtoScalarValue> fields = new ArrayList<>(a.fieldCount());
                for (int f = 0; f < a.fieldCount(); f++) {
                    fields.add(scalarAt(a.field(f), i));
                }
                yield ProtoScalarValue.ofListValue(new ProtoListValue(fields));
            }
            default -> throw new VortexException("no scalar for " + array.getClass().getSimpleName());
        };
    }

    /// An unsigned position read from an offsets, sizes or patch-indices array of any integer width.
    static long index(Array array, long i) {
        return switch (MaskedArray.innerOrSelf(array)) {
            case LongArray a -> a.getLong(i);
            case IntArray a -> Integer.toUnsignedLong(a.getInt(i));
            case ShortArray a -> Short.toUnsignedLong(a.getShort(i));
            case ByteArray a -> Byte.toUnsignedLong(a.getByte(i));
            default -> throw new VortexException("not an index array: " + array.getClass().getSimpleName());
        };
    }

    private static ProtoScalarValue list(Array elements, long start, long count) {
        List<ProtoScalarValue> items = new ArrayList<>(Math.toIntExact(count));
        for (long e = start; e < start + count; e++) {
            items.add(scalarAt(elements, e));
        }
        return ProtoScalarValue.ofListValue(new ProtoListValue(items));
    }

    private static byte[] littleEndian(BigDecimal value) {
        byte[] bigEndian = value.unscaledValue().toByteArray();
        byte[] out = new byte[bigEndian.length];
        for (int b = 0; b < out.length; b++) {
            out[b] = bigEndian[bigEndian.length - 1 - b];
        }
        return out;
    }

    private static void setBit(MemorySegment bits, int i) {
        bits.set(ValueLayout.JAVA_BYTE, i >>> 3, (byte) (bits.get(ValueLayout.JAVA_BYTE, i >>> 3) | 1 << (i & 7)));
    }

    private static Array values(SegmentAllocator arena, DType dtype, ProtoScalarValue[] rows) {
        int n = rows.length;
        return switch (dtype) {
            case DType.Null _ -> new NullArray(dtype, n);
            case DType.Bool _ -> {
                MemorySegment bits = arena.allocate((n + 7L) / 8);
                for (int i = 0; i < n; i++) {
                    if (Boolean.TRUE.equals(rows[i].bool_value())) {
                        setBit(bits, i);
                    }
                }
                yield new MaterializedBoolArray(dtype, n, bits);
            }
            case DType.Primitive p -> primitive(arena, dtype, p.ptype(), rows);
            case DType.Extension ext when ext.storageDType() instanceof DType.Primitive p ->
                    primitive(arena, dtype, p.ptype(), rows);
            case DType.Extension ext -> build(arena, ext.storageDType(), rows);
            case DType.Decimal d -> decimal(arena, d, rows);
            case DType.Utf8 _, DType.Binary _ -> varBin(arena, dtype, rows);
            case DType.Struct s -> struct(arena, s, rows);
            case DType.FixedSizeList f -> {
                int width = f.fixedSize();
                ProtoScalarValue[] elements = new ProtoScalarValue[Math.multiplyExact(n, width)];
                for (int i = 0; i < n; i++) {
                    for (int k = 0; k < width; k++) {
                        elements[i * width + k] = item(rows[i], k);
                    }
                }
                yield new FixedSizeListArray(f, n, build(arena, f.elementType(), elements));
            }
            case DType.List l -> {
                MemorySegment offsets = arena.allocate(ValueLayout.JAVA_LONG, n + 1L);
                ProtoScalarValue[] elements = flatten(rows, offsets);
                yield new ListArray(l, n, build(arena, l.elementType(), elements),
                        new MaterializedLongArray(DTypes.I64, n + 1L, offsets));
            }
            case DType.Map m -> map(arena, m, rows);
            default -> throw new VortexException("no array from scalars of " + dtype);
        };
    }

    private static Array struct(SegmentAllocator arena, DType.Struct dtype, ProtoScalarValue[] rows) {
        List<Array> fields = new ArrayList<>(dtype.fieldTypes().size());
        for (int f = 0; f < dtype.fieldTypes().size(); f++) {
            ProtoScalarValue[] column = new ProtoScalarValue[rows.length];
            for (int i = 0; i < rows.length; i++) {
                column[i] = item(rows[i], f);
            }
            fields.add(build(arena, dtype.fieldTypes().get(f), column));
        }
        return new StructArray(dtype, rows.length, fields);
    }

    /// A map's entries are a list view of `{key, value}` structs, as [MapEncodingDecoder] reads them.
    private static Array map(SegmentAllocator arena, DType.Map dtype, ProtoScalarValue[] rows) {
        int n = rows.length;
        MemorySegment bounds = arena.allocate(ValueLayout.JAVA_LONG, n + 1L);
        ProtoScalarValue[] entries = flatten(rows, bounds);
        MemorySegment sizes = arena.allocate(ValueLayout.JAVA_LONG, n);
        for (int i = 0; i < n; i++) {
            sizes.setAtIndex(VortexFormat.LE_LONG, i,
                    bounds.getAtIndex(VortexFormat.LE_LONG, i + 1L) - bounds.getAtIndex(VortexFormat.LE_LONG, i));
        }
        Array view = new ListViewArray(new DType.List(dtype.entriesDtype(), false), n,
                build(arena, dtype.entriesDtype(), entries),
                new MaterializedLongArray(DTypes.I64, n, bounds.asSlice(0, n * 8L)),
                new MaterializedLongArray(DTypes.I64, n, sizes));
        return new MapArray(dtype, n, view);
    }

    /// The items of every row's list, in order; `offsets` gets each row's start, then the end.
    private static ProtoScalarValue[] flatten(ProtoScalarValue[] rows, MemorySegment offsets) {
        List<ProtoScalarValue> items = new ArrayList<>();
        for (int i = 0; i < rows.length; i++) {
            offsets.setAtIndex(VortexFormat.LE_LONG, i, items.size());
            if (rows[i].list_value() != null) {
                items.addAll(rows[i].list_value().values());
            }
        }
        offsets.setAtIndex(VortexFormat.LE_LONG, rows.length, items.size());
        return items.toArray(ProtoScalarValue[]::new);
    }

    /// Item `k` of a list scalar, or a scalar with no value past its end.
    private static ProtoScalarValue item(ProtoScalarValue row, int k) {
        if (row.list_value() == null || k >= row.list_value().values().size()) {
            return NO_VALUE;
        }
        return row.list_value().values().get(k);
    }

    private static Array primitive(SegmentAllocator arena, DType dtype, PType ptype, ProtoScalarValue[] rows) {
        int n = rows.length;
        int width = ptype.byteSize();
        MemorySegment out = arena.allocate((long) n * width, width);
        for (int i = 0; i < n; i++) {
            long bits = ConstantEncodingDecoder.scalarToRawBits(rows[i]);
            switch (width) {
                case 1 -> out.set(ValueLayout.JAVA_BYTE, i, (byte) bits);
                case 2 -> out.setAtIndex(VortexFormat.LE_SHORT, i, (short) bits);
                case 4 -> out.setAtIndex(VortexFormat.LE_INT, i, (int) bits);
                default -> out.setAtIndex(VortexFormat.LE_LONG, i, bits);
            }
        }
        return switch (ptype) {
            case I8, U8 -> new MaterializedByteArray(dtype, n, out);
            case I16, U16 -> new MaterializedShortArray(dtype, n, out);
            case F16 -> new MaterializedFloat16Array(dtype, n, out);
            case I32, U32 -> new MaterializedIntArray(dtype, n, out);
            case F32 -> new MaterializedFloatArray(dtype, n, out);
            case I64, U64 -> new MaterializedLongArray(dtype, n, out);
            case F64 -> new MaterializedDoubleArray(dtype, n, out);
        };
    }

    /// Each scalar holds its value at its own width; it is sign-extended to the column's.
    private static Array decimal(SegmentAllocator arena, DType.Decimal dtype, ProtoScalarValue[] rows) {
        int n = rows.length;
        int width = dtype.byteWidth();
        MemorySegment out = arena.allocate((long) n * width);
        for (int i = 0; i < n; i++) {
            byte[] value = rows[i].bytes_value();
            if (value == null) {
                continue;
            }
            BigDecimal decimal = new LazyDecimalArray(dtype, 1, MemorySegment.ofArray(value), value.length)
                    .getDecimal(0);
            byte[] bigEndian = decimal.unscaledValue().toByteArray();
            byte fill = (byte) (decimal.signum() < 0 ? -1 : 0);
            for (int b = 0; b < width; b++) {
                int from = bigEndian.length - 1 - b;
                byte octet = fill;
                if (from >= 0) {
                    octet = bigEndian[from];
                }
                out.set(ValueLayout.JAVA_BYTE, (long) i * width + b, octet);
            }
        }
        return new LazyDecimalArray(dtype, n, out, width);
    }

    private static Array varBin(SegmentAllocator arena, DType dtype, ProtoScalarValue[] rows) {
        int n = rows.length;
        byte[][] values = new byte[n][];
        long total = 0;
        for (int i = 0; i < n; i++) {
            values[i] = bytes(rows[i]);
            total += values[i].length;
        }
        MemorySegment data = arena.allocate(Math.max(total, 1));
        MemorySegment offsets = arena.allocate(ValueLayout.JAVA_LONG, n + 1L);
        long at = 0;
        for (int i = 0; i < n; i++) {
            offsets.setAtIndex(VortexFormat.LE_LONG, i, at);
            MemorySegment.copy(values[i], 0, data, ValueLayout.JAVA_BYTE, at, values[i].length);
            at += values[i].length;
        }
        offsets.setAtIndex(VortexFormat.LE_LONG, n, at);
        return new VarBinOffsetArray(dtype, n, data.asSlice(0, total), offsets, PType.I64);
    }

    /// The bytes of a string or binary scalar, empty when it has no value.
    static byte[] bytes(ProtoScalarValue scalar) {
        if (scalar.string_value() != null) {
            return scalar.string_value().getBytes(StandardCharsets.UTF_8);
        }
        if (scalar.bytes_value() != null) {
            return scalar.bytes_value();
        }
        return new byte[0];
    }
}
