package io.github.dfa1.vortex.integration;

import dev.vortex.api.DataSource;
import dev.vortex.api.Expression;
import dev.vortex.api.Partition;
import dev.vortex.api.Scan;
import dev.vortex.api.ScanOptions;
import dev.vortex.api.Session;
import dev.vortex.arrow.ArrowAllocation;
import dev.vortex.jni.NativeLoader;
import io.github.dfa1.vortex.csv.CsvImporter;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.ipc.ArrowReader;
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

/// An empty CSV cell in a numeric or boolean column is a missing value, so vortex-jni must read it
/// back as null (#592): the import stored `0`, `0.0` or `false` instead, indistinguishable from a
/// real zero. An inferred text column keeps its empty cells as `""`, as Arrow's CSV reader does.
class CsvEmptyCellsInteropIntegrationTest {

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

        // When
        CsvImporter.importCsv(csvFile, vortex);
        List<List<Object>> result = readJni(vortex, null);
        List<List<Object>> filtered = readJni(vortex, Expression.binary(Expression.BinaryOp.GTE,
                Expression.column("id"), Expression.literal(2_000L)));
        // a filter on the nullable columns themselves goes through our zone stats for zones with
        // nulls: a wrong min/max or null count prunes rows Rust should return, or keeps a null
        List<List<Object>> byQty = readJni(vortex, Expression.binary(Expression.BinaryOp.GTE,
                Expression.column("qty"), Expression.literal(25L)));
        List<List<Object>> byAllNull = readJni(vortex, Expression.binary(Expression.BinaryOp.GTE,
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

    private static List<List<Object>> readJni(Path file, Expression filter) throws IOException {
        String[] columns = {"id", "qty", "price", "active", "name", "bc"};
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
                            FieldVector vector = root.getVector(column);
                            Object value = vector.isNull(i) ? null : vector.getObject(i);
                            row.add(value instanceof Text text ? text.toString() : value);
                        }
                        rows.add(row);
                    }
                }
            }
        }
        return rows;
    }
}
