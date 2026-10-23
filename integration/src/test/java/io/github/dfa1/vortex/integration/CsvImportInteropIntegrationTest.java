package io.github.dfa1.vortex.integration;

import dev.vortex.api.DataSource;
import dev.vortex.api.Expression;
import dev.vortex.api.Partition;
import dev.vortex.api.Scan;
import dev.vortex.api.ScanOptions;
import dev.vortex.api.Session;
import dev.vortex.arrow.ArrowAllocation;
import dev.vortex.jni.NativeLoader;
import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.csv.CsvImporter;
import io.github.dfa1.vortex.csv.ImportOptions;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.SmallIntVector;
import org.apache.arrow.vector.TinyIntVector;
import org.apache.arrow.vector.UInt1Vector;
import org.apache.arrow.vector.UInt2Vector;
import org.apache.arrow.vector.UInt4Vector;
import org.apache.arrow.vector.UInt8Vector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.ipc.ArrowReader;
import org.apache.arrow.vector.types.FloatingPointPrecision;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.util.Text;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// CSV imports read back by vortex-jni. An empty cell in a numeric or boolean column is a missing
/// value, so vortex-jni must read it back as null (#592): the import stored `0`, `0.0` or `false`
/// instead, indistinguishable from a real zero. An inferred text column keeps its empty cells as
/// `""`, as Arrow's CSV reader does. A schema's narrow and unsigned integer types and `F32` are
/// written as those types, which vortex-jni reads with the same values.
class CsvImportInteropIntegrationTest {

    private static final Session SESSION = Session.create();
    private static final BufferAllocator ALLOCATOR = ArrowAllocation.rootAllocator();
    private static final int ROWS = 3_000;

    static {
        NativeLoader.loadJni();
    }

    @TempDir
    Path tmp;

    @Test
    void emptyCells_readAsNullByVortexJni() throws IOException {
        // Given: every 7th row empty in each typed column, `bc` empty in every row, and enough rows
        // for several zones, so the filtered read below prunes and slices
        StringBuilder csv = new StringBuilder("id,qty,price,active,name,bc\n");
        for (int i = 0; i < ROWS; i++) {
            boolean empty = i % 7 == 3;
            csv.append(i).append(',')
               .append(empty ? "" : String.valueOf(i % 50)).append(',')
               .append(empty ? "" : (i % 50) + ".5").append(',')
               .append(empty ? "" : String.valueOf(i % 2 == 0)).append(',')
               .append(empty ? "" : "n" + i).append(',')
               .append('\n');
        }
        Path csvFile = tmp.resolve("empties.csv");
        Files.writeString(csvFile, csv);
        Path vortex = tmp.resolve("empties.vortex");
        String[] columns = {"id", "qty", "price", "active", "name", "bc"};

        // When
        CsvImporter.importCsv(csvFile, vortex);
        List<List<Object>> result = readJni(vortex, columns, null);
        List<List<Object>> filtered = readJni(vortex, columns, Expression.binary(Expression.BinaryOp.GTE,
                Expression.column("id"), Expression.literal(2_000L)));
        // a filter on the nullable columns themselves goes through our zone stats for zones with
        // nulls: a wrong min/max or null count prunes rows Rust should return, or keeps a null
        List<List<Object>> byQty = readJni(vortex, columns, Expression.binary(Expression.BinaryOp.GTE,
                Expression.column("qty"), Expression.literal(25L)));
        List<List<Object>> byAllNull = readJni(vortex, columns, Expression.binary(Expression.BinaryOp.GTE,
                Expression.column("bc"), Expression.literal(0L)));

        // Then
        List<List<Object>> expected = new ArrayList<>();
        for (int i = 0; i < ROWS; i++) {
            boolean empty = i % 7 == 3;
            expected.add(Arrays.asList((long) i,
                    empty ? null : (long) (i % 50),
                    empty ? null : (i % 50) + 0.5,
                    empty ? null : i % 2 == 0,
                    empty ? "" : "n" + i,
                    null));
        }
        assertThat(result).isEqualTo(expected);
        assertThat(filtered).isEqualTo(expected.subList(2_000, ROWS));
        // a null qty never satisfies the comparison
        assertThat(byQty).isEqualTo(expected.stream()
                .filter(row -> row.get(1) instanceof Long qty && qty >= 25)
                .toList());
        assertThat(byAllNull).isEmpty();
    }

    @Test
    void schemaTypes_readByVortexJniWithTheirValues() throws IOException {
        // Given: each integer width at both ends of its range, unsigned values above the signed
        // maximum (a sign mix-up reads them negative), an F32 and a nullable U8 with empty cells
        StringBuilder csv = new StringBuilder("id,i8,u8,i16,u16,u32,u64,f32\n");
        List<List<Object>> expected = new ArrayList<>();
        for (int i = 0; i < ROWS; i++) {
            boolean empty = i % 7 == 3;
            long i8 = i % 256 - 128;
            long u8 = i % 256;
            long i16 = i * 20L - 32_768;
            long u16 = 65_535 - i;
            long u32 = 4_294_967_295L - i;
            String u64 = Long.toUnsignedString(-1L - i);
            float f32 = i + 0.25f;
            csv.append(i).append(',').append(i8).append(',').append(empty ? "" : String.valueOf(u8)).append(',')
               .append(i16).append(',').append(u16).append(',').append(u32).append(',').append(u64).append(',')
               .append(f32).append('\n');
            expected.add(Arrays.asList((long) i, i8, empty ? null : u8, i16, u16, u32, u64, f32));
        }
        Path csvFile = tmp.resolve("types.csv");
        Files.writeString(csvFile, csv);
        Path vortex = tmp.resolve("types.vortex");
        DType.Struct schema = DType.structBuilder()
                .field(ColumnName.of("id"), DType.I64)
                .field(ColumnName.of("i8"), DType.I8)
                .field(ColumnName.of("u8"), DType.U8.asNullable())
                .field(ColumnName.of("i16"), DType.I16)
                .field(ColumnName.of("u16"), DType.U16)
                .field(ColumnName.of("u32"), DType.U32)
                .field(ColumnName.of("u64"), DType.U64)
                .field(ColumnName.of("f32"), DType.F32)
                .build();
        String[] columns = {"id", "i8", "u8", "i16", "u16", "u32", "u64", "f32"};

        // When
        CsvImporter.importCsv(csvFile, vortex, ImportOptions.defaults().withSchema(schema));
        List<ArrowType> types = DataSource.open(SESSION, vortex.toAbsolutePath().toUri().toString())
                .arrowSchema(ALLOCATOR).getFields().stream().map(Field::getType).toList();
        List<List<Object>> result = readJni(vortex, columns, null);
        // a filter on a narrow column, so its zone stats prune and the read slices the others
        List<List<Object>> filtered = readJni(vortex, columns, Expression.binary(Expression.BinaryOp.GTE,
                Expression.column("i16"), Expression.literal((short) 0)));

        // Then
        assertThat(types).isEqualTo(List.of(new ArrowType.Int(64, true), new ArrowType.Int(8, true),
                new ArrowType.Int(8, false), new ArrowType.Int(16, true), new ArrowType.Int(16, false),
                new ArrowType.Int(32, false), new ArrowType.Int(64, false),
                new ArrowType.FloatingPoint(FloatingPointPrecision.SINGLE)));
        assertThat(result).isEqualTo(expected);
        assertThat(filtered).isEqualTo(expected.stream().filter(row -> (long) row.get(3) >= 0).toList());
    }

    private static List<List<Object>> readJni(Path file, String[] columns, Expression filter) throws IOException {
        var options = ScanOptions.builder()
                .projection(Expression.select(columns, Expression.root()));
        if (filter != null) {
            options.filter(filter);
        }
        List<List<Object>> rows = new ArrayList<>();
        Scan scan = DataSource.open(SESSION, file.toAbsolutePath().toUri().toString()).scan(options.build());
        while (scan.hasNext()) {
            Partition partition = scan.next();
            try (ArrowReader reader = partition.scanArrow(ALLOCATOR)) {
                while (reader.loadNextBatch()) {
                    VectorSchemaRoot root = reader.getVectorSchemaRoot();
                    for (int i = 0; i < root.getRowCount(); i++) {
                        List<Object> row = new ArrayList<>();
                        for (String column : columns) {
                            row.add(value(root.getVector(column), i));
                        }
                        rows.add(row);
                    }
                }
            }
        }
        return rows;
    }

    /// The cell as a `long` for every integer width (a `U64` as its unsigned decimal text, since
    /// it may not fit), a `String` for text, else Arrow's own object.
    private static Object value(FieldVector vector, int i) {
        if (vector.isNull(i)) {
            return null;
        }
        return switch (vector) {
            case TinyIntVector v -> (long) v.get(i);
            case SmallIntVector v -> (long) v.get(i);
            case IntVector v -> (long) v.get(i);
            case UInt1Vector v -> (long) Byte.toUnsignedInt(v.get(i));
            case UInt2Vector v -> (long) v.get(i);
            case UInt4Vector v -> Integer.toUnsignedLong(v.get(i));
            case UInt8Vector v -> Long.toUnsignedString(v.get(i));
            default -> {
                Object value = vector.getObject(i);
                if (value instanceof Text text) {
                    value = text.toString();
                }
                yield value;
            }
        };
    }
}
