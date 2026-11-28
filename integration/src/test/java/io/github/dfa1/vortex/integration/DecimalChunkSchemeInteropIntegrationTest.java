package io.github.dfa1.vortex.integration;

import dev.vortex.api.Session;
import dev.vortex.api.VortexWriter;
import dev.vortex.arrow.ArrowAllocation;
import dev.vortex.jni.NativeLoader;
import io.github.dfa1.vortex.core.model.ColumnName;
import org.apache.arrow.c.ArrowArray;
import org.apache.arrow.c.ArrowSchema;
import org.apache.arrow.c.Data;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.DecimalVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.IntToLongFunction;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// Decimal columns whose chunks or zone tables Rust 0.95 may dictionary-encode: its
/// `DecimalDictScheme` competes with byte parts, so a dictionary shows wherever few values repeat,
/// as in a zone table whose zones share bounds.
class DecimalChunkSchemeInteropIntegrationTest {

    private static final Session SESSION = Session.create();
    private static final BufferAllocator ALLOCATOR = ArrowAllocation.rootAllocator();
    private static final ColumnName D = ColumnName.of("d");
    private static final int ROWS = 4 * 8192;
    private static final long[] RANDOM = new Random(7).longs(ROWS).toArray();

    static {
        NativeLoader.loadJni();
    }

    static Stream<Arguments> shapes() {
        List<Arguments> cases = new ArrayList<>();
        for (int precision : new int[]{5, 10, 20}) {
            for (boolean nullable : new boolean[]{false, true}) {
                // unique, then 50 values: a dictionary beats byte parts' estimate
                cases.add(shape("unique head, 50 distinct", precision, nullable, distinctAfterHead(50)));
                // unique, then 2 values: byte parts, its most significant part a dictionary
                cases.add(shape("unique head, 2 distinct", precision, nullable, distinctAfterHead(2)));
                // unique throughout: byte parts
                // (i * 3 stays within precision 5 for every row)
                cases.add(shape("unique", precision, nullable, i -> i * 3L));
                // 50 values throughout: the probe admits the column to a dict layout
                cases.add(shape("50 distinct", precision, nullable, i -> Math.floorMod(RANDOM[i], 50) * 1000L + 7));
            }
        }
        return cases.stream();
    }

    /// A filter reads the zone table, whose decimal bounds Rust 0.95 dictionary-encodes when a
    /// few zones share them: Java's reader rejected a `vortex.dict` over decimals.
    @ParameterizedTest(name = "{0} p{1} nullable={2}")
    @MethodSource("shapes")
    void javaFiltersJniFile(String name, int precision, boolean nullable, IntToLongFunction unscaled,
                            @TempDir Path tmp) throws Exception {
        // Given
        Path jniFile = tmp.resolve("jni.vortex");
        writeWithJni(jniFile, precision, nullable, unscaled);
        BigDecimal threshold = BigDecimal.valueOf(unscaled.applyAsLong(ROWS / 2 + 1), 2);
        List<BigDecimal> expected = new ArrayList<>();
        for (int i = 0; i < ROWS; i++) {
            BigDecimal value = BigDecimal.valueOf(unscaled.applyAsLong(i), 2);
            if (!isNull(nullable, i) && value.compareTo(threshold) >= 0) {
                expected.add(value);
            }
        }

        // When
        List<BigDecimal> result = new ArrayList<>();
        try (var reader = io.github.dfa1.vortex.reader.VortexReader.open(jniFile);
             var scan = reader.scan(io.github.dfa1.vortex.reader.ScanOptions.all()
                     .withFilter(io.github.dfa1.vortex.reader.ScanFilter.gte(D, threshold)))) {
            while (scan.hasNext()) {
                try (var chunk = scan.next()) {
                    var column = chunk.column(D);
                    for (int i = 0; i < chunk.rowCount(); i++) {
                        if (column instanceof io.github.dfa1.vortex.reader.array.MaskedArray masked) {
                            result.add(((io.github.dfa1.vortex.reader.array.DecimalArray) masked.inner()).getDecimal(i));
                        } else {
                            result.add(((io.github.dfa1.vortex.reader.array.DecimalArray) column).getDecimal(i));
                        }
                    }
                }
            }
        }

        // Then
        assertThat(result).usingElementComparator(BigDecimal::compareTo).isEqualTo(expected);
    }

    private static IntToLongFunction distinctAfterHead(int distinct) {
        return i -> i < 8192 ? i * 7L : Math.floorMod(RANDOM[i], distinct) * 1000L + 7;
    }

    private static Arguments shape(String name, int precision, boolean nullable, IntToLongFunction unscaled) {
        return Arguments.of(name, precision, nullable, unscaled);
    }

    private static boolean isNull(boolean nullable, int i) {
        return nullable && i % 7 == 0;
    }

    private static void writeWithJni(Path path, int precision, boolean nullable, IntToLongFunction unscaled)
            throws Exception {
        ArrowType decimal = new ArrowType.Decimal(precision, 2, 128);
        Schema schema = new Schema(List.of(nullable ? Field.nullable("d", decimal) : Field.notNullable("d", decimal)));
        try (VortexWriter writer = VortexWriter.builder(SESSION, path.toAbsolutePath().toUri().toString(), schema,
                ALLOCATOR).build();
             VectorSchemaRoot root = VectorSchemaRoot.create(schema, ALLOCATOR)) {
            DecimalVector vector = (DecimalVector) root.getVector("d");
            vector.setInitialCapacity(ROWS);
            vector.allocateNew();
            for (int i = 0; i < ROWS; i++) {
                if (isNull(nullable, i)) {
                    vector.setNull(i);
                } else {
                    vector.setSafe(i, BigDecimal.valueOf(unscaled.applyAsLong(i), 2));
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
}
