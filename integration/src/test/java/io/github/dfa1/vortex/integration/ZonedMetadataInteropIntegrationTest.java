package io.github.dfa1.vortex.integration;

import dev.vortex.api.Session;
import dev.vortex.api.VortexWriter;
import dev.vortex.arrow.ArrowAllocation;
import dev.vortex.jni.NativeLoader;
import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.DTypes;
import io.github.dfa1.vortex.core.model.LayoutId;
import io.github.dfa1.vortex.reader.VortexReader;
import io.github.dfa1.vortex.reader.layout.Layout;
import io.github.dfa1.vortex.writer.WriteOptions;
import org.apache.arrow.c.ArrowArray;
import org.apache.arrow.c.ArrowSchema;
import org.apache.arrow.c.Data;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.BitVector;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.FloatingPointPrecision;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.lang.foreign.ValueLayout;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// The zoned layout's metadata — its zone length and one aggregate spec per stat — byte for byte
/// as vortex-jni writes it, for each family of aggregate sets: Rust 0.95 writes every spec as a
/// `vortex.expr.AggregateFn` whose `optional bytes metadata` is always present, so a stat with no
/// options carries an empty field (`12 00`) that a spec written field-by-field would leave out.
class ZonedMetadataInteropIntegrationTest {

    private static final Session SESSION = Session.create();
    private static final BufferAllocator ALLOCATOR = ArrowAllocation.rootAllocator();
    private static final ColumnName V = ColumnName.of("v");
    private static final int ROWS = 20_000;

    static {
        NativeLoader.loadJni();
    }

    // Values vary row to row: a constant column has no zone map at all (Rust's ConstantStrategy).
    static Stream<Arguments> columns() {
        return Stream.of(
                Arguments.of("i64", new ArrowType.Int(64, true), DTypes.I64),
                Arguments.of("f64", new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE), DTypes.F64),
                Arguments.of("utf8", ArrowType.Utf8.INSTANCE, DTypes.UTF8),
                Arguments.of("bool", ArrowType.Bool.INSTANCE, DTypes.BOOL));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("columns")
    void javaWritesTheZonedMetadataJniWrites(String name, ArrowType arrow, DType dtype, @TempDir Path tmp)
            throws Exception {
        // Given
        Path jni = tmp.resolve("jni.vortex");
        Path java = tmp.resolve("java.vortex");
        writeWithJni(jni, arrow);

        // When
        writeWithJava(java, dtype);

        // Then
        assertThat(zonedMetadataHex(java)).isEqualTo(zonedMetadataHex(jni));
    }

    private static void writeWithJni(Path path, ArrowType arrow) throws Exception {
        Schema schema = new Schema(List.of(Field.notNullable("v", arrow)));
        try (VortexWriter writer = VortexWriter.builder(SESSION, path.toAbsolutePath().toUri().toString(), schema,
                ALLOCATOR).build();
             VectorSchemaRoot root = VectorSchemaRoot.create(schema, ALLOCATOR)) {
            FieldVector vector = root.getVector("v");
            vector.setInitialCapacity(ROWS);
            vector.allocateNew();
            for (int i = 0; i < ROWS; i++) {
                switch (vector) {
                    case BigIntVector longs -> longs.setSafe(i, i * 7L);
                    case Float8Vector doubles -> doubles.setSafe(i, i * 0.5);
                    case VarCharVector strings -> strings.setSafe(i, ("v" + i).getBytes(StandardCharsets.UTF_8));
                    case BitVector bits -> bits.setSafe(i, i % 3 == 0 ? 1 : 0);
                    default -> throw new IllegalStateException("unexpected vector " + vector);
                }
            }
            vector.setValueCount(ROWS);
            root.setRowCount(ROWS);
            try (ArrowArray array = ArrowArray.allocateNew(ALLOCATOR);
                 ArrowSchema arrowSchema = ArrowSchema.allocateNew(ALLOCATOR)) {
                Data.exportVectorSchemaRoot(ALLOCATOR, root, null, array, arrowSchema);
                writer.writeBatch(array.memoryAddress(), arrowSchema.memoryAddress());
            }
        }
    }

    private static void writeWithJava(Path path, DType dtype) throws IOException {
        Object data = switch (dtype) {
            case DType.Primitive p when p.equals(DTypes.I64) -> {
                long[] longs = new long[ROWS];
                for (int i = 0; i < ROWS; i++) {
                    longs[i] = i * 7L;
                }
                yield longs;
            }
            case DType.Primitive _ -> {
                double[] doubles = new double[ROWS];
                for (int i = 0; i < ROWS; i++) {
                    doubles[i] = i * 0.5;
                }
                yield doubles;
            }
            case DType.Utf8 _ -> {
                String[] strings = new String[ROWS];
                for (int i = 0; i < ROWS; i++) {
                    strings[i] = "v" + i;
                }
                yield strings;
            }
            default -> {
                boolean[] bools = new boolean[ROWS];
                for (int i = 0; i < ROWS; i++) {
                    bools[i] = i % 3 == 0;
                }
                yield bools;
            }
        };
        DType.Struct schema = new DType.Struct(List.of(V), List.of(dtype), false);
        try (var channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var writer = io.github.dfa1.vortex.writer.VortexWriter.create(channel, schema, WriteOptions.defaults())) {
            writer.writeChunk(Map.of(V, data));
        }
    }

    /// The metadata of the file's one zoned layout, as hex.
    private static String zonedMetadataHex(Path file) throws IOException {
        try (VortexReader reader = VortexReader.open(file)) {
            Layout zoned = findZoned(reader.layout());
            assertThat(zoned).as("a zoned layout in %s", file).isNotNull();
            return HexFormat.of().formatHex(zoned.metadata().toArray(ValueLayout.JAVA_BYTE));
        }
    }

    private static Layout findZoned(Layout layout) {
        if (layout.layoutId().equals(LayoutId.ZONED)) {
            return layout;
        }
        for (Layout child : layout.children()) {
            Layout found = findZoned(child);
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}
