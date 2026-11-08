package io.github.dfa1.vortex.integration;

import dev.vortex.api.Session;
import dev.vortex.api.VortexWriter;
import dev.vortex.arrow.ArrowAllocation;
import dev.vortex.jni.NativeLoader;
import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.writer.WriteOptions;
import org.apache.arrow.c.ArrowArray;
import org.apache.arrow.c.ArrowSchema;
import org.apache.arrow.c.Data;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.FloatingPointPrecision;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/// Rust's integer and float dictionary schemes order a dictionary's values as their
/// distinct-values hash map iterates, and Java's cascade orders them the same way (#651).
///
/// Ordered first-seen, the dictionary held the same values under other codes: the codes' bit
/// widths differed, so did the cascade's choice for them (bit-packed with patches where Rust packs
/// plainly, or keeps the codes primitive), and the zone tables of real columns came out a different
/// tree. Every buffer of the file is compared byte for byte, so the values pool and the codes must
/// both be in Rust's order. Each shape takes one of Rust's three ways to collect distinct values.
class DictValueOrderInteropIntegrationTest {

    private static final Session SESSION = Session.create();
    private static final BufferAllocator ALLOCATOR = ArrowAllocation.rootAllocator();
    private static final ColumnName V = ColumnName.of("v");
    private static final int ZONE = 8192;
    private static final int ZONES = 64;

    static {
        NativeLoader.loadJni();
    }

    /// The zone mins span a few values, so Rust counts them in a dense array and collects them
    /// into its map in ascending order. Mostly 0, as `Disco`'s are, with a few rarer values: in
    /// first-seen order the rare ones took the high codes and bit-packed with patches.
    @Test
    void zoneMinsInANarrowRange(@TempDir Path tmp) throws Exception {
        assertSameBuffers(intColumn(new int[]{0, 0, 0, 0, 0, 0, 5, 5, 1, 1, 4, 6, 3, 9}), tmp);
    }

    /// Mins a million apart are too wide a range to count densely, so Rust inserts them into a
    /// hash map as they come.
    @Test
    void zoneMinsInAWideRange(@TempDir Path tmp) throws Exception {
        assertSameBuffers(intColumn(new int[]{0, 0, 0, 0, 1_000_000, 2_000_000, 2_000_000, 3_000_000, 4_000_000,
                5_000_000, 6_000_000, 7_000_000, 8_000_000}), tmp);
    }

    /// Float zone mins, which Rust collects into a hash set. Every other row is distinct, so the
    /// column gets no dictionary layout (a separate, first-seen builder in both writers) and the
    /// only dictionary is the zone table's.
    @Test
    void floatZoneMins(@TempDir Path tmp) throws Exception {
        // Given — six mins ALP cannot shorten, at uneven frequencies
        Random random = new Random(11);
        double[] pool = new double[6];
        for (int i = 0; i < pool.length; i++) {
            pool[i] = random.nextDouble() * 1e6;
        }
        double[] values = new double[ZONES * ZONE];
        for (int z = 0; z < ZONES; z++) {
            values[z * ZONE] = pool[Math.min(random.nextInt(12), pool.length - 1)];
            for (int i = 1; i < ZONE; i++) {
                values[z * ZONE + i] = 1e7 + random.nextDouble() * 1e6;
            }
        }
        Path jniFile = tmp.resolve("jni.vortex");
        Path javaFile = tmp.resolve("java.vortex");
        writeWithJni(jniFile, values);
        List<List<String>> expected = EncodingTrees.flatBuffersHex(jniFile);
        // When
        writeWithJava(javaFile, new DType.Primitive(PType.F64, false), values);

        // Then
        assertThat(EncodingTrees.of(javaFile)).isEqualTo(EncodingTrees.of(jniFile));
        assertThat(EncodingTrees.flatBuffersHex(javaFile)).isEqualTo(expected);
    }

    /// [#ZONES] zones whose mins cycle through `mins` in a seeded shuffle, the other rows above
    /// every min, so the zone table's min column is a low-cardinality integer column.
    private static int[] intColumn(int[] mins) {
        Random random = new Random(5);
        int[] values = new int[ZONES * ZONE];
        for (int z = 0; z < ZONES; z++) {
            int min = mins[random.nextInt(mins.length)];
            values[z * ZONE] = min;
            for (int i = 1; i < ZONE; i++) {
                values[z * ZONE + i] = 10_000_000 + random.nextInt(1000);
            }
        }
        return values;
    }

    private static void assertSameBuffers(int[] values, Path tmp) throws Exception {
        // Given
        Path jniFile = tmp.resolve("jni.vortex");
        Path javaFile = tmp.resolve("java.vortex");
        writeWithJni(jniFile, values);
        List<List<String>> expected = EncodingTrees.flatBuffersHex(jniFile);

        // When
        writeWithJava(javaFile, new DType.Primitive(PType.I32, false), values);

        // Then
        assertThat(EncodingTrees.of(javaFile)).isEqualTo(EncodingTrees.of(jniFile));
        assertThat(EncodingTrees.flatBuffersHex(javaFile)).isEqualTo(expected);
    }

    private static void writeWithJni(Path path, Object values) throws Exception {
        boolean ints = values instanceof int[];
        ArrowType type = ints ? new ArrowType.Int(32, true) : new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE);
        Schema schema = new Schema(List.of(Field.notNullable("v", type)));
        int rows = ints ? ((int[]) values).length : ((double[]) values).length;
        try (VortexWriter writer = VortexWriter.builder(SESSION, path.toAbsolutePath().toUri().toString(), schema,
                ALLOCATOR).build();
             VectorSchemaRoot root = VectorSchemaRoot.create(schema, ALLOCATOR)) {
            if (ints) {
                IntVector vector = (IntVector) root.getVector("v");
                vector.allocateNew(rows);
                for (int i = 0; i < rows; i++) {
                    vector.set(i, ((int[]) values)[i]);
                }
                vector.setValueCount(rows);
            } else {
                Float8Vector vector = (Float8Vector) root.getVector("v");
                vector.allocateNew(rows);
                for (int i = 0; i < rows; i++) {
                    vector.set(i, ((double[]) values)[i]);
                }
                vector.setValueCount(rows);
            }
            root.setRowCount(rows);
            try (ArrowArray array = ArrowArray.allocateNew(ALLOCATOR);
                 ArrowSchema arrowSchema = ArrowSchema.allocateNew(ALLOCATOR)) {
                Data.exportVectorSchemaRoot(ALLOCATOR, root, null, array, arrowSchema);
                writer.writeBatch(array.memoryAddress(), arrowSchema.memoryAddress());
            }
        }
    }

    private static void writeWithJava(Path path, DType dtype, Object values) throws IOException {
        DType.Struct schema = new DType.Struct(List.of(V), List.of(dtype), false);
        try (var channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var writer = io.github.dfa1.vortex.writer.VortexWriter.create(channel, schema, WriteOptions.defaults())) {
            writer.writeChunk(Map.of(V, values));
        }
    }
}
