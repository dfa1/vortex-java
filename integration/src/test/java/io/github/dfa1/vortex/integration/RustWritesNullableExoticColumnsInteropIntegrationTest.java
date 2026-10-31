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
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongFunction;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// Nullable columns whose data pushes vortex-jni's compressor toward the encodings the plain
/// sweep never reaches (ALP-RD, ZigZag, RLE, Delta, FoR, Pco, Zstd...), read back by vortex-java.
/// Rust decides what to write, so the test reports the encodings it got and asserts them only as
/// a floor ([#rustWroteTheRarerEncodings]); every null must come back whichever it chose.
class RustWritesNullableExoticColumnsInteropIntegrationTest {

    private static final Session SESSION = Session.create();
    private static final BufferAllocator ALLOCATOR = ArrowAllocation.rootAllocator();
    private static final ColumnName VALUE = ColumnName.of("v");
    private static final int BATCHES = 3;
    private static final int BATCH_ROWS = 4_000;
    private static final int ROWS = BATCHES * BATCH_ROWS;

    private static final Set<String> SEEN = ConcurrentHashMap.newKeySet();

    static {
        NativeLoader.loadJni();
    }

    /// A value per row for one element type, shaped for an encoding.
    enum Exotic {
        // mantissas no decimal scaling reaches: ALP gives up, ALP-RD splits the bits
        DOUBLE_SINE(Element.DOUBLE, row -> Math.sin(row)),
        DOUBLE_DECIMALS(Element.DOUBLE, row -> row / 100.0),
        DOUBLE_RANDOM_BITS(Element.DOUBLE, row -> Double.longBitsToDouble(new Random(row).nextLong() & 0x7FEFFFFFFFFFFFFFL)),
        INT_SIGNED_SMALL(Element.INT, row -> (int) (row % 7) - 3),
        INT_LONG_RUNS(Element.INT, row -> (int) (row / 700)),
        INT_RAMP_WITH_STEP(Element.INT, row -> (int) (row * 3 - 5_000)),
        INT_RANDOM(Element.INT, row -> new Random(row).nextInt()),
        LONG_RANDOM(Element.LONG, row -> new Random(row).nextLong()),
        LONG_TIMESTAMP_LIKE(Element.LONG, row -> 1_700_000_000_000_000L + row * 1_000_000L + (row % 13)),
        LONG_SIGNED_SMALL(Element.LONG, row -> (row % 9) - 4),
        BOOL_LONG_RUNS(Element.BOOL, row -> (row / 600) % 2 == 0),
        STRING_SHARED_PREFIX(Element.UTF8, row -> "https://example.com/products/" + (row % 500) + "/detail"),
        STRING_LOW_CARDINALITY(Element.UTF8, row -> "category-" + (row % 6)),
        // an Arrow timestamp is a vortex.timestamp extension over i64, which Rust splits into
        // days, seconds and subseconds (vortex.datetimeparts)
        TIMESTAMP_MICROS(Element.LONG, row -> 1_700_000_000_000_000L + row * 1_000_000L + (row % 13) * 7,
                new ArrowType.Timestamp(org.apache.arrow.vector.types.TimeUnit.MICROSECOND, null)),
        TIMESTAMP_MICROS_SPARSE_DAYS(Element.LONG,
                row -> 1_700_000_000_000_000L + (row / 40) * 86_400_000_000L + (row % 40) * 1_000_000L,
                new ArrowType.Timestamp(org.apache.arrow.vector.types.TimeUnit.MICROSECOND, null));

        final Element element;
        final LongFunction<Object> value;
        final ArrowType arrowType;

        Exotic(Element element, LongFunction<Object> value) {
            this(element, value, element.arrowType);
        }

        Exotic(Element element, LongFunction<Object> value, ArrowType arrowType) {
            this.element = element;
            this.value = value;
            this.arrowType = arrowType;
        }
    }

    static Stream<Arguments> cases() {
        List<Arguments> cases = new ArrayList<>();
        for (Exotic exotic : Exotic.values()) {
            for (Nulls nulls : Nulls.values()) {
                cases.add(Arguments.of(exotic, nulls));
            }
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0}, {1}")
    @MethodSource("cases")
    void jniWriter_javaReader_keepsEveryNull(Exotic exotic, Nulls nulls, @TempDir Path tmp) throws IOException {
        // Given
        Path file = tmp.resolve("jni_exotic.vtx");
        writeWithJni(file, exotic, nulls);

        // When
        List<Object> result = exotic.element.readColumn(file, VALUE);

        // Then
        List<Object> expected = new ArrayList<>();
        for (long row = 0; row < ROWS; row++) {
            expected.add(nulls.isNull(row) ? null : exotic.value.apply(row));
        }
        String encodings = encodings(file);
        SEEN.addAll(List.of(encodings.replace("Used encodings: ", "").split(", ")));
        assertThat(result).as(() -> "encodings Rust chose: " + encodings).containsExactlyElementsOf(expected);
    }

    @AfterAll
    static void rustWroteTheRarerEncodings() {
        // Reported, not required: Rust's compressor decides. The floor below is what these shapes
        // reached when written; if a Rust upgrade stops choosing one, widen the shapes.
        System.out.println("Rust wrote: " + new TreeSet<>(SEEN));
        assertThat(SEEN).contains("fastlanes.bitpacked", "vortex.alp", "vortex.fsst");
    }

    private static void writeWithJni(Path file, Exotic exotic, Nulls nulls) throws IOException {
        Schema schema = new Schema(List.of(
                Field.notNullable("id", new ArrowType.Int(64, true)),
                Field.nullable("v", exotic.arrowType)));
        try (VortexWriter writer = VortexWriter.builder(SESSION, file.toAbsolutePath().toUri().toString(), schema,
                ALLOCATOR).build()) {
            for (int batch = 0; batch < BATCHES; batch++) {
                try (VectorSchemaRoot root = VectorSchemaRoot.create(schema, ALLOCATOR)) {
                    BigIntVector ids = (BigIntVector) root.getVector("id");
                    FieldVector values = root.getVector("v");
                    ids.allocateNew(BATCH_ROWS);
                    values.allocateNew();
                    for (int i = 0; i < BATCH_ROWS; i++) {
                        long row = (long) batch * BATCH_ROWS + i;
                        ids.setSafe(i, row);
                        Object value = nulls.isNull(row) ? null : exotic.value.apply(row);
                        if (values instanceof org.apache.arrow.vector.TimeStampMicroVector timestamps) {
                            if (value == null) {
                                timestamps.setNull(i);
                            } else {
                                timestamps.setSafe(i, (Long) value);
                            }
                        } else {
                            exotic.element.write(values, i, value);
                        }
                    }
                    root.setRowCount(BATCH_ROWS);
                    JniFiles.writeBatch(ALLOCATOR, writer, root);
                }
            }
        }
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
