package io.github.dfa1.vortex.parquet;

import dev.hardwood.row.PqList;
import dev.hardwood.row.PqStruct;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.writer.encode.ListData;
import io.github.dfa1.vortex.writer.encode.NullableData;
import io.github.dfa1.vortex.writer.encode.StructData;

import java.util.ArrayList;
import java.util.List;

/// Recursively builds Vortex column data (`StructData` / `ListData` / typed arrays, each
/// optionally wrapped in [NullableData]) from Parquet's row-major nested access
/// ([PqStruct] / [PqList]), for LIST and STRUCT columns encountered while importing a nested
/// Parquet schema.
///
/// Only used for the two container shapes (List/Struct); flat leaf columns keep
/// [ParquetImporter]'s existing typed-array fast path — a [ColumnBuilder] here always
/// accumulates through a boxed `Object` per row/element, which is fine for the comparatively
/// rare nested columns but not for the hot flat-scalar-column loop.
sealed interface ColumnBuilder {

    /// Appends one logical value: for a top-level column, one row; for a list's element
    /// builder, one element; for a struct field builder, one field value. `null` marks the
    /// value itself as null, not one of its children.
    ///
    /// @param value a decoded leaf (`Integer`/`Long`/.../`String`/`byte[]`), a [PqStruct],
    ///              a [PqList], or `null`
    void append(Object value);

    /// Builds the accumulated data, shaped for whatever encoder this column's dtype resolves
    /// to, wrapped in [NullableData] when the dtype is nullable.
    ///
    /// @return a typed array, `StructData`, or `ListData`, optionally wrapped in `NullableData`
    Object build();

    /// Creates the builder for `type` — [DType.Struct] and [DType.List] recurse; every other
    /// dtype uses [LeafColumnBuilder].
    ///
    /// @param type the column's (possibly nested) dtype
    /// @return a builder that accumulates values for `type`
    static ColumnBuilder forType(DType type) {
        if (type instanceof DType.Struct s) {
            return new StructColumnBuilder(s);
        }
        if (type instanceof DType.List l) {
            return new ListColumnBuilder(l);
        }
        return new LeafColumnBuilder(type);
    }
}

/// Builds a `StructData` (or `NullableData`-wrapped `StructData`) from a sequence of
/// [PqStruct] values (or `null`), one per row/element.
final class StructColumnBuilder implements ColumnBuilder {

    private final DType.Struct dtype;
    private final List<ColumnBuilder> fieldBuilders;
    private final List<Boolean> validity;

    StructColumnBuilder(DType.Struct dtype) {
        this.dtype = dtype;
        this.fieldBuilders = new ArrayList<>(dtype.fieldTypes().size());
        for (DType fieldType : dtype.fieldTypes()) {
            fieldBuilders.add(ColumnBuilder.forType(fieldType));
        }
        this.validity = dtype.nullable() ? new ArrayList<>() : null;
    }

    @Override
    public void append(Object value) {
        if (value == null) {
            if (validity == null) {
                throw new IllegalArgumentException("null value for non-nullable struct column");
            }
            for (ColumnBuilder fieldBuilder : fieldBuilders) {
                fieldBuilder.append(null);
            }
            validity.add(false);
            return;
        }
        PqStruct struct = (PqStruct) value;
        for (int i = 0; i < fieldBuilders.size(); i++) {
            fieldBuilders.get(i).append(struct.getValue(i));
        }
        if (validity != null) {
            validity.add(true);
        }
    }

    @Override
    public Object build() {
        List<Object> fieldArrays = new ArrayList<>(fieldBuilders.size());
        for (ColumnBuilder fieldBuilder : fieldBuilders) {
            fieldArrays.add(fieldBuilder.build());
        }
        StructData data = new StructData(fieldArrays);
        return validity == null ? data : new NullableData(data, BooleanArrays.toArray(validity));
    }
}

/// Builds a `ListData` (or `NullableData`-wrapped `ListData`) from a sequence of [PqList]
/// values (or `null`), one per row/element. Elements across every appended list are flattened
/// into one child [ColumnBuilder], offset-delimited per the `vortex.list` wire shape.
final class ListColumnBuilder implements ColumnBuilder {

    private final DType.List dtype;
    private final ColumnBuilder elementBuilder;
    private final List<Long> offsets = new ArrayList<>();
    private final List<Boolean> validity;
    private long runningCount;

    ListColumnBuilder(DType.List dtype) {
        this.dtype = dtype;
        this.elementBuilder = ColumnBuilder.forType(dtype.elementType());
        this.validity = dtype.nullable() ? new ArrayList<>() : null;
        offsets.add(0L);
    }

    @Override
    public void append(Object value) {
        if (value == null) {
            if (validity == null) {
                throw new IllegalArgumentException("null value for non-nullable list column");
            }
            offsets.add(runningCount);
            validity.add(false);
            return;
        }
        PqList list = (PqList) value;
        for (Object element : list.values()) {
            elementBuilder.append(element);
            runningCount++;
        }
        offsets.add(runningCount);
        if (validity != null) {
            validity.add(true);
        }
    }

    @Override
    public Object build() {
        long[] offsetsArray = new long[offsets.size()];
        for (int i = 0; i < offsetsArray.length; i++) {
            offsetsArray[i] = offsets.get(i);
        }
        ListData data = new ListData(elementBuilder.build(), offsetsArray, offsetsArray.length - 1L);
        return validity == null ? data : new NullableData(data, BooleanArrays.toArray(validity));
    }
}

/// Builds a typed array (or `NullableData`-wrapped typed array) from a sequence of decoded
/// leaf values (`Boolean`/`String`/`byte[]`/`Number`, or `null`), one per row/element.
/// Nested `Extension` (timestamp) columns are not supported — none of the corpus schemas this
/// builder targets nest a timestamp inside a LIST/STRUCT, and converting Hardwood's decoded
/// `Instant`/`LocalDateTime` back into extension storage needs a target `TimeUnit` this builder
/// has no way to infer generically.
final class LeafColumnBuilder implements ColumnBuilder {

    private final DType dtype;
    private final List<Object> values = new ArrayList<>();

    LeafColumnBuilder(DType dtype) {
        if (dtype instanceof DType.Extension) {
            throw new UnsupportedOperationException(
                    "nested Extension (timestamp) columns are not supported: " + dtype);
        }
        this.dtype = dtype;
    }

    // Unlike StructColumnBuilder/ListColumnBuilder, append() never rejects a null value even
    // when dtype.nullable() is false: a REQUIRED field nested inside an OPTIONAL struct is only
    // "required whenever the struct is present" — StructColumnBuilder.append(null) pushes null
    // into every field builder unconditionally when the struct itself is absent, non-nullable
    // fields included. That row's placeholder is masked out by the struct's own validity bit
    // (see NullableData), so no separate validity tracking is needed here for that case.
    @Override
    public void append(Object value) {
        values.add(value);
    }

    @Override
    public Object build() {
        int n = values.size();
        boolean nullable = dtype.nullable();
        boolean[] validity = nullable ? new boolean[n] : null;
        Object result = switch (dtype) {
            case DType.Bool _ -> buildBoolean(n, validity);
            case DType.Utf8 _ -> buildUtf8(n, validity);
            case DType.Binary _ -> buildBinary(n, validity);
            case DType.Primitive p -> buildPrimitive(p.ptype(), n, validity);
            default -> throw new UnsupportedOperationException("unsupported nested leaf dtype: " + dtype);
        };
        return nullable ? new NullableData(result, validity) : result;
    }

    private boolean[] buildBoolean(int n, boolean[] validity) {
        boolean[] arr = new boolean[n];
        for (int i = 0; i < n; i++) {
            Boolean v = (Boolean) values.get(i);
            if (v != null) {
                arr[i] = v;
                markValid(validity, i);
            }
        }
        return arr;
    }

    private String[] buildUtf8(int n, boolean[] validity) {
        String[] arr = new String[n];
        for (int i = 0; i < n; i++) {
            arr[i] = (String) values.get(i);
            if (arr[i] != null) {
                markValid(validity, i);
            }
        }
        return arr;
    }

    private byte[][] buildBinary(int n, boolean[] validity) {
        byte[][] arr = new byte[n][];
        for (int i = 0; i < n; i++) {
            arr[i] = (byte[]) values.get(i);
            if (arr[i] != null) {
                markValid(validity, i);
            }
        }
        return arr;
    }

    private Object buildPrimitive(PType ptype, int n, boolean[] validity) {
        return switch (ptype) {
            case I8, U8 -> {
                byte[] arr = new byte[n];
                for (int i = 0; i < n; i++) {
                    Number v = (Number) values.get(i);
                    if (v != null) {
                        arr[i] = v.byteValue();
                        markValid(validity, i);
                    }
                }
                yield arr;
            }
            case I16, U16 -> {
                short[] arr = new short[n];
                for (int i = 0; i < n; i++) {
                    Number v = (Number) values.get(i);
                    if (v != null) {
                        arr[i] = v.shortValue();
                        markValid(validity, i);
                    }
                }
                yield arr;
            }
            case I32, U32 -> {
                int[] arr = new int[n];
                for (int i = 0; i < n; i++) {
                    Number v = (Number) values.get(i);
                    if (v != null) {
                        arr[i] = v.intValue();
                        markValid(validity, i);
                    }
                }
                yield arr;
            }
            case I64, U64 -> {
                long[] arr = new long[n];
                for (int i = 0; i < n; i++) {
                    Number v = (Number) values.get(i);
                    if (v != null) {
                        arr[i] = v.longValue();
                        markValid(validity, i);
                    }
                }
                yield arr;
            }
            case F32 -> {
                float[] arr = new float[n];
                for (int i = 0; i < n; i++) {
                    Number v = (Number) values.get(i);
                    if (v != null) {
                        arr[i] = v.floatValue();
                        markValid(validity, i);
                    }
                }
                yield arr;
            }
            case F64 -> {
                double[] arr = new double[n];
                for (int i = 0; i < n; i++) {
                    Number v = (Number) values.get(i);
                    if (v != null) {
                        arr[i] = v.doubleValue();
                        markValid(validity, i);
                    }
                }
                yield arr;
            }
            case F16 -> throw new UnsupportedOperationException("nested F16 columns are not supported");
        };
    }

    private static void markValid(boolean[] validity, int i) {
        if (validity != null) {
            validity[i] = true;
        }
    }
}

/// Tiny helper converting an accumulated `List<Boolean>` validity list into a `boolean[]`.
final class BooleanArrays {

    private BooleanArrays() {
    }

    static boolean[] toArray(List<Boolean> list) {
        boolean[] arr = new boolean[list.size()];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = list.get(i);
        }
        return arr;
    }
}
