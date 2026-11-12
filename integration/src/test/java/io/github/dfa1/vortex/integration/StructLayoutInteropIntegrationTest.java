package io.github.dfa1.vortex.integration;

import dev.vortex.api.DataSource;
import dev.vortex.api.Partition;
import dev.vortex.api.Scan;
import dev.vortex.api.ScanOptions;
import dev.vortex.api.Session;
import dev.vortex.api.VortexWriter;
import dev.vortex.arrow.ArrowAllocation;
import dev.vortex.jni.NativeLoader;
import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.DTypes;
import io.github.dfa1.vortex.reader.VortexReader;
import io.github.dfa1.vortex.reader.layout.Layout;
import io.github.dfa1.vortex.writer.WriteOptions;
import io.github.dfa1.vortex.writer.encode.NullableData;
import io.github.dfa1.vortex.writer.encode.StructData;
import org.apache.arrow.c.ArrowArray;
import org.apache.arrow.c.ArrowSchema;
import org.apache.arrow.c.Data;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.VarBinaryVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.complex.StructVector;
import org.apache.arrow.vector.ipc.ArrowReader;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// A struct column is Rust's `vortex.struct` layout: each field is its own chunked, compressed
/// column, under a validity child when the struct is nullable, and zoned only while no struct above
/// it is nullable. Java wrote one flat array (under `vortex.masked` when nullable): 2.2% bigger on
/// Raincloud `mnist`, and no field could be pruned. The same column through vortex-jni and
/// vortex-java must lay out the same tree, and vortex-jni must read Java's file.
class StructLayoutInteropIntegrationTest {

    private static final Session SESSION = Session.create();
    private static final BufferAllocator ALLOCATOR = ArrowAllocation.rootAllocator();
    private static final ColumnName STRUCT = ColumnName.of("s");
    // past one chunk of every field, so the layout is chunked, and past 8192 rows for zone maps
    private static final int ROWS = 300_000;

    static {
        NativeLoader.loadJni();
    }

    @ParameterizedTest(name = "nullable struct: {0}")
    @ValueSource(booleans = {true, false})
    void javaLaysOutTheStructAsJniDoes(boolean nullable, @TempDir Path tmp) throws Exception {
        // Given
        Path jniFile = tmp.resolve("jni.vortex");
        Path javaFile = tmp.resolve("java.vortex");
        writeWithJni(jniFile, nullable);

        // When
        writeWithJava(javaFile, nullable);

        // Then
        assertThat(shape(javaFile)).isEqualTo(shape(jniFile));
    }

    @ParameterizedTest(name = "nullable struct: {0}")
    @ValueSource(booleans = {true, false})
    void jniReadsTheStructJavaWrote(boolean nullable, @TempDir Path tmp) throws Exception {
        // Given
        Path javaFile = tmp.resolve("java.vortex");
        writeWithJava(javaFile, nullable);

        // When
        List<Long> result = jniReadsField(javaFile);

        // Then
        List<Long> expected = new ArrayList<>();
        for (int i = 0; i < ROWS; i++) {
            expected.add(nullable && isNull(i) ? null : (long) i);
        }
        assertThat(result).isEqualTo(expected);
    }

    private static boolean isNull(int row) {
        return row % 7 == 0;
    }

    /// The layout tree without segment indices or metadata: Rust's order of segments is no layout.
    private static String shape(Path file) throws IOException {
        try (VortexReader reader = VortexReader.open(file)) {
            StringBuilder out = new StringBuilder();
            shape(reader.layout(), 0, out);
            return out.toString();
        }
    }

    private static void shape(Layout layout, int depth, StringBuilder out) {
        out.append("  ".repeat(depth)).append(layout.layoutId()).append(' ').append(layout.rowCount()).append('\n');
        for (Layout child : layout.children()) {
            shape(child, depth + 1, out);
        }
    }

    private static void writeWithJava(Path path, boolean nullable) throws IOException {
        DType.Struct struct = new DType.Struct(
                List.of(ColumnName.of("bytes"), ColumnName.of("path"), ColumnName.of("n")),
                List.of(new DType.Binary(true), new DType.Utf8(true), DTypes.I64), nullable);
        DType.Struct schema = new DType.Struct(List.of(STRUCT), List.of(struct), false);
        byte[][] bytes = new byte[ROWS][];
        String[] paths = new String[ROWS];
        long[] n = new long[ROWS];
        boolean[] valid = new boolean[ROWS];
        for (int i = 0; i < ROWS; i++) {
            bytes[i] = ("bytes" + i * 31L).getBytes(StandardCharsets.UTF_8);
            paths[i] = "p" + i % 10;
            n[i] = i;
            valid[i] = !(nullable && isNull(i));
        }
        boolean[] allValid = new boolean[ROWS];
        Arrays.fill(allValid, true);
        StructData fields = new StructData(List.of(
                new NullableData(bytes, allValid), new NullableData(paths, allValid.clone()), n));
        Object column = fields;
        if (nullable) {
            column = new NullableData(fields, valid);
        }
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             io.github.dfa1.vortex.writer.VortexWriter writer =
                     io.github.dfa1.vortex.writer.VortexWriter.create(channel, schema, WriteOptions.defaults())) {
            writer.writeChunk(Map.of(STRUCT, column));
        }
    }

    private static void writeWithJni(Path path, boolean nullable) throws Exception {
        Field struct = new Field("s", nullable ? FieldType.nullable(ArrowType.Struct.INSTANCE)
                : FieldType.notNullable(ArrowType.Struct.INSTANCE),
                List.of(Field.nullable("bytes", ArrowType.Binary.INSTANCE), Field.nullable("path", ArrowType.Utf8.INSTANCE),
                        Field.notNullable("n", new ArrowType.Int(64, true))));
        Schema schema = new Schema(List.of(struct));
        try (VortexWriter writer = VortexWriter.builder(SESSION, path.toAbsolutePath().toUri().toString(), schema,
                ALLOCATOR).build();
             VectorSchemaRoot root = VectorSchemaRoot.create(schema, ALLOCATOR)) {
            StructVector s = (StructVector) root.getVector("s");
            s.allocateNew();
            VarBinaryVector bytes = (VarBinaryVector) s.getChild("bytes");
            VarCharVector paths = (VarCharVector) s.getChild("path");
            BigIntVector n = (BigIntVector) s.getChild("n");
            for (int i = 0; i < ROWS; i++) {
                bytes.setSafe(i, ("bytes" + i * 31L).getBytes(StandardCharsets.UTF_8));
                paths.setSafe(i, ("p" + i % 10).getBytes(StandardCharsets.UTF_8));
                n.setSafe(i, i);
                if (nullable && isNull(i)) {
                    s.setNull(i);
                } else {
                    s.setIndexDefined(i);
                }
            }
            bytes.setValueCount(ROWS);
            paths.setValueCount(ROWS);
            n.setValueCount(ROWS);
            s.setValueCount(ROWS);
            root.setRowCount(ROWS);
            try (ArrowArray array = ArrowArray.allocateNew(ALLOCATOR);
                 ArrowSchema arrowSchema = ArrowSchema.allocateNew(ALLOCATOR)) {
                Data.exportVectorSchemaRoot(ALLOCATOR, root, null, array, arrowSchema);
                writer.writeBatch(array.memoryAddress(), arrowSchema.memoryAddress());
            }
        }
    }

    /// Field `n` of every row through vortex-jni, `null` where the struct is null.
    private static List<Long> jniReadsField(Path file) throws IOException {
        List<Long> out = new ArrayList<>();
        DataSource ds = DataSource.open(SESSION, file.toAbsolutePath().toUri().toString());
        Scan scan = ds.scan(ScanOptions.of());
        while (scan.hasNext()) {
            Partition partition = scan.next();
            try (ArrowReader reader = partition.scanArrow(ALLOCATOR)) {
                while (reader.loadNextBatch()) {
                    StructVector s = (StructVector) reader.getVectorSchemaRoot().getVector("s");
                    BigIntVector n = (BigIntVector) s.getChild("n");
                    for (int i = 0; i < s.getValueCount(); i++) {
                        out.add(s.isNull(i) ? null : n.get(i));
                    }
                }
            }
        }
        return out;
    }
}
