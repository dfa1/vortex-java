package io.github.dfa1.vortex.integration;

import dev.vortex.api.Session;
import dev.vortex.api.VortexWriter;
import dev.vortex.arrow.ArrowAllocation;
import dev.vortex.jni.NativeLoader;
import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.inspect.VortexInspector;
import io.github.dfa1.vortex.integration.FixedSizeListInteropIntegrationTest.Element;
import io.github.dfa1.vortex.integration.FixedSizeListInteropIntegrationTest.Nulls;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.VortexReader;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.NullArray;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongFunction;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// Nullable top-level columns written by vortex-jni, read by vortex-java: every null must come
/// back as null. The shape of the data decides which encoding Rust picks (constant, dictionary,
/// run-end, sparse, FSST, bit-packed, ...), and a Java decoder that accepts a validity child and
/// ignores it reads each null as a zero or an empty string. That happened for `vortex.list`,
/// `vortex.varbin`, `vortex.fixed_size_list` and `vortex.fsst`; this sweeps the rest, whichever
/// encoding Rust chooses. The encodings Rust used are listed in the failure message.
class RustWritesNullableColumnsInteropIntegrationTest {

    private static final Session SESSION = Session.create();
    private static final BufferAllocator ALLOCATOR = ArrowAllocation.rootAllocator();
    private static final ColumnName VALUE = ColumnName.of("v");
    private static final int BATCHES = 3;
    private static final int BATCH_ROWS = 1_000;
    private static final int ROWS = BATCHES * BATCH_ROWS;

    static {
        NativeLoader.loadJni();
    }

    /// What the column holds, by row; the shape picks Rust's encoding.
    enum Shape {
        DISTINCT(row -> row),
        LOW_CARDINALITY(row -> row % 5),
        RUNS(row -> row / 50),
        CONSTANT(row -> 7L),
        MOSTLY_CONSTANT(row -> row % 50 == 0 ? row : 7L),
        // more distinct values than a small dictionary holds, but still repeating
        MODULO_97(row -> row % 97);

        final LongFunction<Long> key;

        Shape(LongFunction<Long> key) {
            this.key = key;
        }
    }

    static Stream<Arguments> cases() {
        List<Arguments> cases = new ArrayList<>();
        for (Element element : Element.values()) {
            for (Shape shape : Shape.values()) {
                for (Nulls nulls : Nulls.values()) {
                    cases.add(Arguments.of(element, shape, nulls));
                }
            }
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0}, {1}, {2}")
    @MethodSource("cases")
    void jniWriter_javaReader_keepsEveryNull(Element element, Shape shape, Nulls nulls, @TempDir Path tmp)
            throws IOException {
        // Given
        Path file = tmp.resolve("jni_nullable.vtx");
        writeWithJni(file, element, shape, nulls);

        // When
        List<Object> result = readJava(file, element);

        // Then
        List<Object> expected = new ArrayList<>();
        for (long row = 0; row < ROWS; row++) {
            expected.add(nulls.isNull(row) ? null : element.value(shape.key.apply(row), 0));
        }
        assertThat(result).as(() -> "encodings Rust chose: " + encodings(file)).containsExactlyElementsOf(expected);
    }

    private static void writeWithJni(Path file, Element element, Shape shape, Nulls nulls) throws IOException {
        Schema schema = new Schema(List.of(
                Field.notNullable("id", new ArrowType.Int(64, true)),
                Field.nullable("v", element.arrowType)));
        try (VortexWriter writer = VortexWriter.builder(SESSION, file.toAbsolutePath().toUri().toString(), schema,
                ALLOCATOR).build()) {
            for (int batch = 0; batch < BATCHES; batch++) {
                try (VectorSchemaRoot root = VectorSchemaRoot.create(schema, ALLOCATOR)) {
                    BigIntVector ids = (BigIntVector) root.getVector("id");
                    var values = root.getVector("v");
                    ids.allocateNew(BATCH_ROWS);
                    values.allocateNew();
                    for (int i = 0; i < BATCH_ROWS; i++) {
                        long row = (long) batch * BATCH_ROWS + i;
                        ids.setSafe(i, row);
                        element.write(values, i, nulls.isNull(row) ? null : element.value(shape.key.apply(row), 0));
                    }
                    root.setRowCount(BATCH_ROWS);
                    JniFiles.writeBatch(ALLOCATOR, writer, root);
                }
            }
        }
    }

    private static List<Object> readJava(Path file, Element element) throws IOException {
        List<Object> rows = new ArrayList<>();
        try (VortexReader reader = VortexReader.open(file, ReadRegistry.loadAll());
             Arena arena = Arena.ofConfined()) {
            reader.scan(io.github.dfa1.vortex.reader.ScanOptions.all()).forEachRemaining(chunk -> {
                MaskedArray.Unwrapped column = MaskedArray.unwrap(chunk.column(VALUE));
                Array inner = column.inner();
                boolean allNull = inner instanceof NullArray;
                MemorySegment lanes = allNull || !element.lanes() ? null : inner.materialize(arena);
                for (long i = 0; i < inner.length(); i++) {
                    boolean isNull = allNull || column.validity() != null && !column.validity().getBoolean(i);
                    rows.add(isNull ? null : element.get(inner, lanes, i));
                }
            });
        }
        return rows;
    }

    private static String encodings(Path file) {
        try (VortexReader reader = VortexReader.open(file, ReadRegistry.loadAll())) {
            return VortexInspector.inspect(reader).lines()
                    .filter(line -> line.startsWith("Used encodings")).findFirst().orElse("?");
        } catch (IOException e) {
            return "unreadable: " + e.getMessage();
        }
    }
}
