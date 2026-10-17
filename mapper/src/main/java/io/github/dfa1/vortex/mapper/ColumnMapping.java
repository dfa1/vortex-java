package io.github.dfa1.vortex.mapper;

import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.writer.VortexWriter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;
import java.util.function.ToIntFunction;
import java.util.function.ToLongFunction;

/// Writes objects of type `T` as rows of a Vortex file: each column is declared once, with its name,
/// its Vortex type and the getter that reads it from a `T`, and the file schema is that list in
/// declaration order.
///
/// ```java
/// ColumnMapping<Trade> trades = ColumnMapping.<Trade>builder()
///         .i64("ts", Trade::ts)
///         .utf8("symbol", Trade::symbol)
///         .f64("price", Trade::price)
///         .u32("volume", Trade::volume)
///         .nullableF64("fee", Trade::fee)
///         .build();
/// try (var writer = VortexWriter.create(channel, trades.schema(), WriteOptions.defaults())) {
///     trades.write(writer, rows);
/// }
/// ```
///
/// No reflection and no annotations: any class works, and the column type is the method that
/// declares it. Unsigned columns hold the raw bits of the same-width Java type, as the writer's
/// array API does (`u32` reads an `int`). A non-nullable column rejects a `null` from its getter;
/// the `nullableXxx` variants write it as a null cell. Encoding overrides stay on
/// [io.github.dfa1.vortex.writer.WriteOptions#withColumnEncoding].
///
/// @param <T> the row type
public final class ColumnMapping<T> {

    private final DType.Struct schema;
    private final List<Column<T>> columns;

    private ColumnMapping(DType.Struct schema, List<Column<T>> columns) {
        this.schema = schema;
        this.columns = List.copyOf(columns);
    }

    /// Starts an empty mapping.
    ///
    /// @param <T> the row type
    /// @return a new builder
    public static <T> Builder<T> builder() {
        return new Builder<>();
    }

    /// The file schema: one field per declared column, in declaration order. Pass it to
    /// [VortexWriter#create(java.nio.channels.WritableByteChannel, DType.Struct, io.github.dfa1.vortex.writer.WriteOptions)].
    ///
    /// @return the struct schema
    public DType.Struct schema() {
        return schema;
    }

    /// Writes `rows` as one batch: each column's values are gathered from every row through its
    /// getter, then handed to [VortexWriter#writeChunk(Map)], which validates them against the
    /// writer's schema. Chunk and zone sizes stay the writer's choice; call this repeatedly to
    /// stream a large source. An empty list writes nothing.
    ///
    /// @param writer a writer created with [#schema()]
    /// @param rows   the rows to write
    /// @throws IOException              if the writer fails to write
    /// @throws IllegalArgumentException if a non-nullable column's getter returns `null`
    public void write(VortexWriter writer, List<? extends T> rows) throws IOException {
        if (rows.isEmpty()) {
            return;
        }
        Map<ColumnName, Object> chunk = LinkedHashMap.newLinkedHashMap(columns.size());
        for (Column<T> column : columns) {
            chunk.put(column.name(), column.values().gather(rows, column.name()));
        }
        writer.writeChunk(chunk);
    }

    private record Column<T>(ColumnName name, Values<T> values) {
    }

    /// Gathers one column's values from all rows into the array shape the writer takes for it.
    @FunctionalInterface
    private interface Values<T> {
        Object gather(List<? extends T> rows, ColumnName column);
    }

    /// Declares the columns of a [ColumnMapping], in file order. Each method takes the column
    /// name (validated by [ColumnName#of(String)]) and the getter for its value.
    ///
    /// @param <T> the row type
    public static final class Builder<T> {

        private final DType.StructBuilder schema = DType.structBuilder();
        private final List<Column<T>> columns = new ArrayList<>();

        private Builder() {
        }

        /// A non-nullable `Bool` column.
        ///
        /// @param name   the column name
        /// @param getter reads the value from a row
        /// @return this builder
        public Builder<T> bool(String name, Predicate<? super T> getter) {
            return add(name, DType.BOOL, (rows, _) -> booleans(rows, getter));
        }

        /// A nullable `Bool` column.
        ///
        /// @param name   the column name
        /// @param getter reads the value from a row; `null` is a null cell
        /// @return this builder
        public Builder<T> nullableBool(String name, Function<? super T, Boolean> getter) {
            return add(name, DType.BOOL.asNullable(), (rows, _) -> boxed(rows, getter, Boolean[]::new));
        }

        /// A non-nullable `I8` column.
        ///
        /// @param name   the column name
        /// @param getter reads the value from a row
        /// @return this builder
        public Builder<T> i8(String name, Function<? super T, Byte> getter) {
            return add(name, DType.I8, (rows, column) -> bytes(rows, getter, column));
        }

        /// A nullable `I8` column.
        ///
        /// @param name   the column name
        /// @param getter reads the value from a row; `null` is a null cell
        /// @return this builder
        public Builder<T> nullableI8(String name, Function<? super T, Byte> getter) {
            return add(name, DType.I8.asNullable(), (rows, _) -> boxed(rows, getter, Byte[]::new));
        }

        /// A non-nullable `U8` column, from the raw bits of a `byte`.
        ///
        /// @param name   the column name
        /// @param getter reads the value from a row
        /// @return this builder
        public Builder<T> u8(String name, Function<? super T, Byte> getter) {
            return add(name, DType.U8, (rows, column) -> bytes(rows, getter, column));
        }

        /// A nullable `U8` column, from the raw bits of a `byte`.
        ///
        /// @param name   the column name
        /// @param getter reads the value from a row; `null` is a null cell
        /// @return this builder
        public Builder<T> nullableU8(String name, Function<? super T, Byte> getter) {
            return add(name, DType.U8.asNullable(), (rows, _) -> boxed(rows, getter, Byte[]::new));
        }

        /// A non-nullable `I16` column.
        ///
        /// @param name   the column name
        /// @param getter reads the value from a row
        /// @return this builder
        public Builder<T> i16(String name, Function<? super T, Short> getter) {
            return add(name, DType.I16, (rows, column) -> shorts(rows, getter, column));
        }

        /// A nullable `I16` column.
        ///
        /// @param name   the column name
        /// @param getter reads the value from a row; `null` is a null cell
        /// @return this builder
        public Builder<T> nullableI16(String name, Function<? super T, Short> getter) {
            return add(name, DType.I16.asNullable(), (rows, _) -> boxed(rows, getter, Short[]::new));
        }

        /// A non-nullable `U16` column, from the raw bits of a `short`.
        ///
        /// @param name   the column name
        /// @param getter reads the value from a row
        /// @return this builder
        public Builder<T> u16(String name, Function<? super T, Short> getter) {
            return add(name, DType.U16, (rows, column) -> shorts(rows, getter, column));
        }

        /// A nullable `U16` column, from the raw bits of a `short`.
        ///
        /// @param name   the column name
        /// @param getter reads the value from a row; `null` is a null cell
        /// @return this builder
        public Builder<T> nullableU16(String name, Function<? super T, Short> getter) {
            return add(name, DType.U16.asNullable(), (rows, _) -> boxed(rows, getter, Short[]::new));
        }

        /// A non-nullable `I32` column.
        ///
        /// @param name   the column name
        /// @param getter reads the value from a row
        /// @return this builder
        public Builder<T> i32(String name, ToIntFunction<? super T> getter) {
            return add(name, DType.I32, (rows, _) -> ints(rows, getter));
        }

        /// A nullable `I32` column.
        ///
        /// @param name   the column name
        /// @param getter reads the value from a row; `null` is a null cell
        /// @return this builder
        public Builder<T> nullableI32(String name, Function<? super T, Integer> getter) {
            return add(name, DType.I32.asNullable(), (rows, _) -> boxed(rows, getter, Integer[]::new));
        }

        /// A non-nullable `U32` column, from the raw bits of an `int`.
        ///
        /// @param name   the column name
        /// @param getter reads the value from a row
        /// @return this builder
        public Builder<T> u32(String name, ToIntFunction<? super T> getter) {
            return add(name, DType.U32, (rows, _) -> ints(rows, getter));
        }

        /// A nullable `U32` column, from the raw bits of an `int`.
        ///
        /// @param name   the column name
        /// @param getter reads the value from a row; `null` is a null cell
        /// @return this builder
        public Builder<T> nullableU32(String name, Function<? super T, Integer> getter) {
            return add(name, DType.U32.asNullable(), (rows, _) -> boxed(rows, getter, Integer[]::new));
        }

        /// A non-nullable `I64` column.
        ///
        /// @param name   the column name
        /// @param getter reads the value from a row
        /// @return this builder
        public Builder<T> i64(String name, ToLongFunction<? super T> getter) {
            return add(name, DType.I64, (rows, _) -> longs(rows, getter));
        }

        /// A nullable `I64` column.
        ///
        /// @param name   the column name
        /// @param getter reads the value from a row; `null` is a null cell
        /// @return this builder
        public Builder<T> nullableI64(String name, Function<? super T, Long> getter) {
            return add(name, DType.I64.asNullable(), (rows, _) -> boxed(rows, getter, Long[]::new));
        }

        /// A non-nullable `U64` column, from the raw bits of a `long`.
        ///
        /// @param name   the column name
        /// @param getter reads the value from a row
        /// @return this builder
        public Builder<T> u64(String name, ToLongFunction<? super T> getter) {
            return add(name, DType.U64, (rows, _) -> longs(rows, getter));
        }

        /// A nullable `U64` column, from the raw bits of a `long`.
        ///
        /// @param name   the column name
        /// @param getter reads the value from a row; `null` is a null cell
        /// @return this builder
        public Builder<T> nullableU64(String name, Function<? super T, Long> getter) {
            return add(name, DType.U64.asNullable(), (rows, _) -> boxed(rows, getter, Long[]::new));
        }

        /// A non-nullable `F32` column.
        ///
        /// @param name   the column name
        /// @param getter reads the value from a row
        /// @return this builder
        public Builder<T> f32(String name, Function<? super T, Float> getter) {
            return add(name, DType.F32, (rows, column) -> floats(rows, getter, column));
        }

        /// A nullable `F32` column.
        ///
        /// @param name   the column name
        /// @param getter reads the value from a row; `null` is a null cell
        /// @return this builder
        public Builder<T> nullableF32(String name, Function<? super T, Float> getter) {
            return add(name, DType.F32.asNullable(), (rows, _) -> boxed(rows, getter, Float[]::new));
        }

        /// A non-nullable `F64` column.
        ///
        /// @param name   the column name
        /// @param getter reads the value from a row
        /// @return this builder
        public Builder<T> f64(String name, ToDoubleFunction<? super T> getter) {
            return add(name, DType.F64, (rows, _) -> doubles(rows, getter));
        }

        /// A nullable `F64` column.
        ///
        /// @param name   the column name
        /// @param getter reads the value from a row; `null` is a null cell
        /// @return this builder
        public Builder<T> nullableF64(String name, Function<? super T, Double> getter) {
            return add(name, DType.F64.asNullable(), (rows, _) -> boxed(rows, getter, Double[]::new));
        }

        /// A non-nullable `Utf8` column; a `null` from the getter fails the write.
        ///
        /// @param name   the column name
        /// @param getter reads the value from a row
        /// @return this builder
        public Builder<T> utf8(String name, Function<? super T, String> getter) {
            return add(name, DType.UTF8, (rows, _) -> boxed(rows, getter, String[]::new));
        }

        /// A nullable `Utf8` column.
        ///
        /// @param name   the column name
        /// @param getter reads the value from a row; `null` is a null cell
        /// @return this builder
        public Builder<T> nullableUtf8(String name, Function<? super T, String> getter) {
            return add(name, DType.UTF8.asNullable(), (rows, _) -> boxed(rows, getter, String[]::new));
        }

        /// A non-nullable `Binary` column; a `null` from the getter fails the write.
        ///
        /// @param name   the column name
        /// @param getter reads the value from a row
        /// @return this builder
        public Builder<T> binary(String name, Function<? super T, byte[]> getter) {
            return add(name, DType.BINARY, (rows, _) -> boxed(rows, getter, byte[][]::new));
        }

        /// A nullable `Binary` column.
        ///
        /// @param name   the column name
        /// @param getter reads the value from a row; `null` is a null cell
        /// @return this builder
        public Builder<T> nullableBinary(String name, Function<? super T, byte[]> getter) {
            return add(name, DType.BINARY.asNullable(), (rows, _) -> boxed(rows, getter, byte[][]::new));
        }

        /// Builds the mapping.
        ///
        /// @return the mapping, with its columns in declaration order
        /// @throws IllegalStateException if no column was declared
        public ColumnMapping<T> build() {
            if (columns.isEmpty()) {
                throw new IllegalStateException("a mapping needs at least one column");
            }
            return new ColumnMapping<>(schema.build(), columns);
        }

        private Builder<T> add(String name, DType dtype, Values<T> values) {
            ColumnName column = ColumnName.of(name);
            schema.field(column, dtype);
            columns.add(new Column<>(column, values));
            return this;
        }
    }

    // The writer takes primitive arrays for non-nullable columns and boxed arrays (null = null cell)
    // for nullable ones; these gather one column into the matching shape. Rows are iterated, not
    // indexed, so a LinkedList input stays linear.

    private static <T> boolean[] booleans(List<? extends T> rows, Predicate<? super T> getter) {
        boolean[] out = new boolean[rows.size()];
        int i = 0;
        for (T row : rows) {
            out[i++] = getter.test(row);
        }
        return out;
    }

    private static <T> int[] ints(List<? extends T> rows, ToIntFunction<? super T> getter) {
        int[] out = new int[rows.size()];
        int i = 0;
        for (T row : rows) {
            out[i++] = getter.applyAsInt(row);
        }
        return out;
    }

    private static <T> long[] longs(List<? extends T> rows, ToLongFunction<? super T> getter) {
        long[] out = new long[rows.size()];
        int i = 0;
        for (T row : rows) {
            out[i++] = getter.applyAsLong(row);
        }
        return out;
    }

    private static <T> double[] doubles(List<? extends T> rows, ToDoubleFunction<? super T> getter) {
        double[] out = new double[rows.size()];
        int i = 0;
        for (T row : rows) {
            out[i++] = getter.applyAsDouble(row);
        }
        return out;
    }

    // byte, short and float have no primitive functional interface in the JDK, so their getters
    // return the box; Byte is always cached and the rest are scalar-replaced once inlined.

    private static <T> byte[] bytes(List<? extends T> rows, Function<? super T, Byte> getter, ColumnName column) {
        byte[] out = new byte[rows.size()];
        int i = 0;
        for (T row : rows) {
            out[i] = present(getter.apply(row), column, i);
            i++;
        }
        return out;
    }

    private static <T> short[] shorts(List<? extends T> rows, Function<? super T, Short> getter, ColumnName column) {
        short[] out = new short[rows.size()];
        int i = 0;
        for (T row : rows) {
            out[i] = present(getter.apply(row), column, i);
            i++;
        }
        return out;
    }

    private static <T> float[] floats(List<? extends T> rows, Function<? super T, Float> getter, ColumnName column) {
        float[] out = new float[rows.size()];
        int i = 0;
        for (T row : rows) {
            out[i] = present(getter.apply(row), column, i);
            i++;
        }
        return out;
    }

    private static <T, V> V[] boxed(List<? extends T> rows, Function<? super T, ? extends V> getter,
            IntFunction<V[]> newArray) {
        V[] out = newArray.apply(rows.size());
        int i = 0;
        for (T row : rows) {
            out[i++] = getter.apply(row);
        }
        return out;
    }

    private static <V> V present(V value, ColumnName column, int row) {
        if (value == null) {
            throw new IllegalArgumentException("non-nullable column '" + column + "' received null at row " + row);
        }
        return value;
    }
}
