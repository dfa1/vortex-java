package io.github.dfa1.vortex.integration;

import java.util.ArrayList;
import org.apache.arrow.vector.util.Text;
import org.apache.arrow.vector.FieldVector;
import dev.vortex.api.Expression;
import dev.vortex.api.DataSource;
import dev.vortex.api.Partition;
import dev.vortex.api.Scan;
import dev.vortex.api.ScanOptions;
import dev.vortex.api.Session;
import dev.vortex.api.VortexWriter;
import dev.vortex.arrow.ArrowAllocation;
import dev.vortex.jni.NativeLoader;
import org.apache.arrow.c.ArrowArray;
import org.apache.arrow.c.ArrowSchema;
import org.apache.arrow.c.Data;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.FloatingPointPrecision;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.ipc.ArrowReader;
import org.apache.arrow.vector.types.pojo.Schema;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;

/// Writes files with vortex-jni for the interop tests.
final class JniFiles {

    private static final Session SESSION = Session.create();
    private static final BufferAllocator ALLOCATOR = ArrowAllocation.rootAllocator();
    private static final Schema ID_VALUE = new Schema(List.of(
            Field.notNullable("id", new ArrowType.Int(64, true)),
            Field.notNullable("value", new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE))));

    static {
        NativeLoader.loadJni();
    }

    private JniFiles() {
    }

    /// Hands one Arrow batch to a vortex-jni writer through the Arrow C data interface.
    static void writeBatch(BufferAllocator allocator, VortexWriter writer, VectorSchemaRoot root)
            throws IOException {
        try (ArrowArray array = ArrowArray.allocateNew(allocator);
             ArrowSchema schema = ArrowSchema.allocateNew(allocator)) {
            Data.exportVectorSchemaRoot(allocator, root, null, array, schema);
            writer.writeBatch(array.memoryAddress(), schema.memoryAddress());
        }
    }

    /// Scans a file with vortex-jni and hands each Arrow batch of every partition, in order, to
    /// `batch`. The batch's vectors are valid only during the call.
    static void forEachBatch(String uri, ScanOptions options, Consumer<VectorSchemaRoot> batch)
            throws IOException {
        Scan scan = DataSource.open(SESSION, uri).scan(options);
        while (scan.hasNext()) {
            Partition partition = scan.next();
            try (ArrowReader reader = partition.scanArrow(ALLOCATOR)) {
                while (reader.loadNextBatch()) {
                    batch.accept(reader.getVectorSchemaRoot());
                }
            }
        }
    }

    /// Reads `column` over rows `[begin, end)` with vortex-jni, keeping the rows `filter` selects
    /// (every row when `null`), in order. Each value is the vector's own Java object made plain:
    /// lists at any depth copied into [ArrayList]s and Arrow `Text` turned into [String], so a
    /// row compares equal to the lists a test builds; a null row or element stays `null`.
    @SuppressWarnings("unchecked")
    static <T> List<T> readColumn(Path file, String column, long begin, long end, Expression filter)
            throws IOException {
        var options = ScanOptions.builder()
                .projection(Expression.select(new String[]{column}, Expression.root()))
                .rowRangeBegin(begin)
                .rowRangeEnd(end);
        if (filter != null) {
            options = options.filter(filter);
        }
        List<T> rows = new ArrayList<>();
        forEachBatch(file.toAbsolutePath().toUri().toString(), options.build(), root -> {
            FieldVector vector = root.getVector(column);
            for (int i = 0; i < root.getRowCount(); i++) {
                rows.add((T) plain(vector.getObject(i)));
            }
        });
        return rows;
    }

    private static Object plain(Object value) {
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) {
                out.add(plain(item));
            }
            return out;
        }
        if (value instanceof Text text) {
            return text.toString();
        }
        return value;
    }

    /// Writes `rows` rows in one batch: `id` (i64) counting from 0, and `value` (f64) seeded random
    /// in `[0, 1000)`.
    static void writeIdsAndRandomValues(Path file, int rows) throws IOException {
        String uri = file.toAbsolutePath().toUri().toString();
        var rng = new Random(42L);
        try (VortexWriter writer = VortexWriter.builder(SESSION, uri, ID_VALUE, ALLOCATOR).build();
             VectorSchemaRoot root = VectorSchemaRoot.create(ID_VALUE, ALLOCATOR)) {
            BigIntVector idVec = (BigIntVector) root.getVector("id");
            Float8Vector valVec = (Float8Vector) root.getVector("value");
            idVec.allocateNew(rows);
            valVec.allocateNew(rows);
            for (int i = 0; i < rows; i++) {
                idVec.setSafe(i, i);
                valVec.setSafe(i, rng.nextDouble() * 1000.0);
            }
            root.setRowCount(rows);
            writeBatch(ALLOCATOR, writer, root);
        }
    }
}
