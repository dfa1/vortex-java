package io.github.dfa1.vortex.cli;

import dev.vortex.api.DataSource;
import dev.vortex.api.Expression;
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
import org.apache.arrow.vector.BitVector;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.ipc.ArrowReader;
import org.apache.arrow.vector.types.FloatingPointPrecision;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
import org.apache.arrow.vector.util.Text;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// `vortex rewrite` of a vortex-jni file: vortex-jni reads the rewritten file with the same rows,
/// nulls included, whole and under a filter that prunes zones and slices the other columns. A
/// Java-only test shares the writer's own assumptions; this is what says the rewritten file is
/// still a file Rust reads as the source (#743 follow-up).
class RewriteInteropIntegrationTest {

    private static final Session SESSION = Session.create();
    private static final BufferAllocator ALLOCATOR = ArrowAllocation.rootAllocator();
    private static final int ROWS = 30_000;
    private static final String[] COLUMNS = {"id", "qty", "price", "name", "flag"};

    static {
        NativeLoader.loadJni();
    }

    @TempDir
    Path tmp;

    @Test
    void rewrittenFile_readsBackTheSameThroughVortexJni() throws Exception {
        // Given — a file written by vortex-jni, with nulls in the nullable columns and enough rows
        // for several zones
        Path source = writeWithJni();
        Path output = tmp.resolve("rewritten.vortex");

        // When
        int status = RewriteCommand.run(new String[]{"rewrite", source.toString(), output.toString()});
        int compactStatus = RewriteCommand.run(new String[]{"rewrite", "--compact", source.toString(),
                tmp.resolve("compact.vortex").toString()});

        // Then
        assertThat(status).isEqualTo(ExitStatus.OK);
        assertThat(compactStatus).isEqualTo(ExitStatus.OK);
        List<List<Object>> expected = readJni(source, null);
        assertThat(expected).hasSize(ROWS);
        assertThat(readJni(output, null)).isEqualTo(expected);
        assertThat(readJni(tmp.resolve("compact.vortex"), null)).isEqualTo(expected);
        Expression late = Expression.binary(Expression.BinaryOp.GTE, Expression.column("id"),
                Expression.literal(20_000L));
        assertThat(readJni(output, late)).isEqualTo(readJni(source, late));
        Expression byQty = Expression.binary(Expression.BinaryOp.GTE, Expression.column("qty"),
                Expression.literal(60));
        assertThat(readJni(output, byQty)).isEqualTo(readJni(source, byQty));
    }

    private Path writeWithJni() throws IOException {
        Schema arrow = new Schema(List.of(
                Field.notNullable("id", new ArrowType.Int(64, true)),
                Field.nullable("qty", new ArrowType.Int(32, true)),
                Field.notNullable("price", new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE)),
                Field.nullable("name", ArrowType.Utf8.INSTANCE),
                Field.notNullable("flag", ArrowType.Bool.INSTANCE)));
        Path file = tmp.resolve("source.vortex");
        try (VortexWriter writer = VortexWriter.builder(SESSION, file.toAbsolutePath().toUri().toString(), arrow,
                ALLOCATOR).build();
             VectorSchemaRoot root = VectorSchemaRoot.create(arrow, ALLOCATOR)) {
            root.allocateNew();
            BigIntVector id = (BigIntVector) root.getVector("id");
            IntVector qty = (IntVector) root.getVector("qty");
            Float8Vector price = (Float8Vector) root.getVector("price");
            VarCharVector name = (VarCharVector) root.getVector("name");
            BitVector flag = (BitVector) root.getVector("flag");
            for (int i = 0; i < ROWS; i++) {
                id.setSafe(i, i);
                if (i % 7 == 0) {
                    qty.setNull(i);
                } else {
                    qty.setSafe(i, i % 100);
                }
                price.setSafe(i, i / 4.0);
                if (i % 5 == 0) {
                    name.setNull(i);
                } else {
                    name.setSafe(i, ("name-" + i % 50).getBytes(StandardCharsets.UTF_8));
                }
                flag.setSafe(i, i % 3 == 0 ? 1 : 0);
            }
            root.setRowCount(ROWS);
            try (ArrowArray array = ArrowArray.allocateNew(ALLOCATOR); ArrowSchema as = ArrowSchema.allocateNew(ALLOCATOR)) {
                Data.exportVectorSchemaRoot(ALLOCATOR, root, null, array, as);
                writer.writeBatch(array.memoryAddress(), as.memoryAddress());
            }
        }
        return file;
    }

    private static List<List<Object>> readJni(Path file, Expression filter) throws IOException {
        var options = ScanOptions.builder().projection(Expression.select(COLUMNS, Expression.root()));
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
                        for (String column : COLUMNS) {
                            row.add(value(root.getVector(column), i));
                        }
                        rows.add(row);
                    }
                }
            }
        }
        return rows;
    }

    private static Object value(FieldVector vector, int i) {
        if (vector.isNull(i)) {
            return null;
        }
        Object value = vector.getObject(i);
        if (value instanceof Text text) {
            return text.toString();
        }
        return value;
    }
}
