package io.github.dfa1.vortex.writer;

import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.writer.encode.NullableData;

import java.util.LinkedHashMap;
import java.util.Map;

/// Package-private [Chunk] implementation. Validates each `.put` against the
/// writer's schema and converts boxed nullable arrays into the internal
/// [NullableData] carrier so the rest of the writer keeps its existing entry points.
final class ChunkImpl implements Chunk {

    private final DType.Struct schema;
    private final Map<ColumnName, Object> data = new LinkedHashMap<>();

    ChunkImpl(DType.Struct schema) {
        this.schema = schema;
    }

    @Override
    public Chunk put(ColumnName column, Object value) {
        int idx = schema.fieldNames().indexOf(column);
        if (idx < 0) {
            throw new IllegalArgumentException("unknown column: " + column);
        }
        if (data.containsKey(column)) {
            throw new IllegalArgumentException("duplicate put for column: " + column);
        }
        DType dtype = schema.fieldTypes().get(idx);
        data.put(column, validateAndAdapt(column.value(), dtype, value));
        return this;
    }

    Map<ColumnName, Object> finish() {
        for (ColumnName name : schema.fieldNames()) {
            if (!data.containsKey(name)) {
                throw new IllegalStateException("missing column: " + name);
            }
        }
        return data;
    }

    /// Validates a column's raw data against its schema dtype and adapts boxed nullable arrays
    /// (`Long[]`, `Integer[]`, `Boolean[]`, …) into the internal [NullableData] carrier. Shared by
    /// the builder ([#put]) and the map-based [VortexWriter#writeChunk(Map)] entry points so both
    /// accept the same shapes.
    ///
    /// @param column the column name, for error messages
    /// @param dtype  the column's declared schema type
    /// @param value  the raw column data
    /// @return the adapted data: the original array for non-nullable primitives, a [NullableData]
    ///         for boxed nullable arrays, or `value` unchanged for non-primitive carriers
    static Object validateAndAdapt(String column, DType dtype, Object value) {
        if (value == null) {
            throw new IllegalArgumentException("null array for column: " + column);
        }
        // Idempotent: an already-adapted NullableData (e.g. from the builder, which adapts before
        // delegating to VortexWriter.writeChunk(Map)) passes through so a second call is a no-op.
        if (value instanceof NullableData) {
            return value;
        }
        return switch (dtype) {
            case DType.Primitive p -> adaptPrimitive(column, p, value);
            case DType.Utf8 u -> adaptUtf8(column, u, value);
            case DType.Bool b -> adaptBool(column, b, value);
            // Other dtypes (Struct, List, Extension, …) still accept their existing carriers
            // without typed adaptation; the writer's per-encoding path validates downstream.
            default -> value;
        };
    }

    private static Object adaptPrimitive(String column, DType.Primitive dtype, Object value) {
        PType ptype = dtype.ptype();
        boolean nullable = dtype.nullable();
        return switch (ptype) {
            case I8, U8 -> switch (value) {
                case byte[] a -> a;
                case Byte[] a -> nullable ? boxedToNullableBytes(a) : rejectNullable(column, ptype);
                default -> throw typeMismatch(column, ptype, value);
            };
            case I16, U16 -> switch (value) {
                case short[] a -> a;
                case Short[] a -> nullable ? boxedToNullableShorts(a) : rejectNullable(column, ptype);
                default -> throw typeMismatch(column, ptype, value);
            };
            case I32, U32 -> switch (value) {
                case int[] a -> a;
                case Integer[] a -> nullable ? boxedToNullableInts(a) : rejectNullable(column, ptype);
                default -> throw typeMismatch(column, ptype, value);
            };
            case I64, U64 -> switch (value) {
                case long[] a -> a;
                case Long[] a -> nullable ? boxedToNullableLongs(a) : rejectNullable(column, ptype);
                default -> throw typeMismatch(column, ptype, value);
            };
            case F32 -> switch (value) {
                case float[] a -> a;
                case Float[] a -> nullable ? boxedToNullableFloats(a) : rejectNullable(column, ptype);
                default -> throw typeMismatch(column, ptype, value);
            };
            case F64 -> switch (value) {
                case double[] a -> a;
                case Double[] a -> nullable ? boxedToNullableDoubles(a) : rejectNullable(column, ptype);
                default -> throw typeMismatch(column, ptype, value);
            };
            case F16 -> switch (value) {
                case short[] a -> a;
                default -> throw typeMismatch(column, ptype, value);
            };
        };
    }

    private static Object adaptUtf8(String column, DType.Utf8 dtype, Object value) {
        if (!(value instanceof String[] arr)) {
            throw new IllegalArgumentException(
                    "column '" + column + "' expects String[] for Utf8; got " + value.getClass().getSimpleName());
        }
        if (!dtype.nullable()) {
            for (int i = 0; i < arr.length; i++) {
                if (arr[i] == null) {
                    throw new IllegalArgumentException(
                            "non-nullable column '" + column + "' received null at row " + i);
                }
            }
            return arr;
        }
        // Nullable utf8/binary unifies on the NullableData carrier like the primitive paths: the
        // raw String[] (null elements preserved) plus a derived validity bitmap. The writer then
        // routes it through MaskedEncoding (or a nullable-capable encoder such as vortex.zstd).
        boolean[] validity = new boolean[arr.length];
        for (int i = 0; i < arr.length; i++) {
            validity[i] = arr[i] != null;
        }
        return new NullableData(arr, validity);
    }

    private static Object adaptBool(String column, DType.Bool dtype, Object value) {
        return switch (value) {
            case boolean[] a -> a;
            case Boolean[] a -> {
                if (!dtype.nullable()) {
                    throw new IllegalArgumentException(
                            "non-nullable column '" + column + "' rejects Boolean[] (use boolean[])");
                }
                boolean[] values = new boolean[a.length];
                boolean[] validity = new boolean[a.length];
                for (int i = 0; i < a.length; i++) {
                    if (a[i] != null) {
                        values[i] = a[i];
                        validity[i] = true;
                    }
                }
                yield new NullableData(values, validity);
            }
            default -> throw new IllegalArgumentException(
                    "column '" + column + "' expects boolean[] for Bool; got " + value.getClass().getSimpleName());
        };
    }

    private static NullableData boxedToNullableBytes(Byte[] a) {
        byte[] values = new byte[a.length];
        boolean[] validity = new boolean[a.length];
        for (int i = 0; i < a.length; i++) {
            if (a[i] != null) {
                values[i] = a[i];
                validity[i] = true;
            }
        }
        return new NullableData(values, validity);
    }

    private static NullableData boxedToNullableShorts(Short[] a) {
        short[] values = new short[a.length];
        boolean[] validity = new boolean[a.length];
        for (int i = 0; i < a.length; i++) {
            if (a[i] != null) {
                values[i] = a[i];
                validity[i] = true;
            }
        }
        return new NullableData(values, validity);
    }

    private static NullableData boxedToNullableInts(Integer[] a) {
        int[] values = new int[a.length];
        boolean[] validity = new boolean[a.length];
        for (int i = 0; i < a.length; i++) {
            if (a[i] != null) {
                values[i] = a[i];
                validity[i] = true;
            }
        }
        return new NullableData(values, validity);
    }

    private static NullableData boxedToNullableLongs(Long[] a) {
        long[] values = new long[a.length];
        boolean[] validity = new boolean[a.length];
        for (int i = 0; i < a.length; i++) {
            if (a[i] != null) {
                values[i] = a[i];
                validity[i] = true;
            }
        }
        return new NullableData(values, validity);
    }

    private static NullableData boxedToNullableFloats(Float[] a) {
        float[] values = new float[a.length];
        boolean[] validity = new boolean[a.length];
        for (int i = 0; i < a.length; i++) {
            if (a[i] != null) {
                values[i] = a[i];
                validity[i] = true;
            }
        }
        return new NullableData(values, validity);
    }

    private static NullableData boxedToNullableDoubles(Double[] a) {
        double[] values = new double[a.length];
        boolean[] validity = new boolean[a.length];
        for (int i = 0; i < a.length; i++) {
            if (a[i] != null) {
                values[i] = a[i];
                validity[i] = true;
            }
        }
        return new NullableData(values, validity);
    }

    private static Object rejectNullable(String column, PType ptype) {
        throw new IllegalArgumentException(
                "non-nullable column '" + column + "' (" + ptype + ") rejects boxed array");
    }

    private static IllegalArgumentException typeMismatch(String column, PType ptype, Object value) {
        return new IllegalArgumentException(
                "column '" + column + "' expects " + ptype + " array; got "
                        + value.getClass().getSimpleName());
    }
}
