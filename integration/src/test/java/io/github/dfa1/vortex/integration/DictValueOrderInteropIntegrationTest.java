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
import org.apache.arrow.vector.UInt2Vector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.FloatingPointPrecision;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Array;
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
/// both be in Rust's order. Each shape is a zone table's min column, a low-cardinality column of
/// one row per zone, and takes one corner of the map Rust builds.
class DictValueOrderInteropIntegrationTest {

    private static final Session SESSION = Session.create();
    private static final BufferAllocator ALLOCATOR = ArrowAllocation.rootAllocator();
    private static final ColumnName V = ColumnName.of("v");
    private static final int ZONE = 8192;

    static {
        NativeLoader.loadJni();
    }

    /// The zone mins span a few values, so Rust counts them in a dense array and collects them
    /// into its map in ascending order. Mostly 0, as `Disco`'s are, with a few rarer values: in
    /// first-seen order the rare ones took the high codes and bit-packed with patches.
    @Test
    void zoneMinsInANarrowRange(@TempDir Path tmp) throws Exception {
        assertSameBuffers(PType.I32, intColumn(64, new int[]{0, 0, 0, 0, 0, 0, 5, 5, 1, 1, 4, 6, 3, 9}), tmp);
    }

    /// Mins a million apart are too wide a range to count densely, so Rust inserts them into a
    /// hash map as they come.
    @Test
    void zoneMinsInAWideRange(@TempDir Path tmp) throws Exception {
        assertSameBuffers(PType.I32, intColumn(64, new int[]{0, 0, 0, 0, 1_000_000, 2_000_000, 2_000_000, 3_000_000,
                4_000_000, 5_000_000, 6_000_000, 7_000_000, 8_000_000}), tmp);
    }

    /// 24 mins whose hashes fall in buckets 0 to 3 of the 64 a 64-row map has: they fill a run
    /// past 16 buckets, where `hashbrown` probes differently by group width (16 on x86, 8 on arm).
    /// Each CI host checks its own width against its own vortex-jni.
    @Test
    void zoneMinsPilingUpPastAGroup(@TempDir Path tmp) throws Exception {
        assertSameBuffers(PType.I32, intColumn(64, collidingInts(24, 64)), tmp);
    }

    /// `U16` mins on both sides of 32,768, within 256 of each other, so Rust counts them densely.
    /// Java holds a `U16` sign-extended: the span from the min once read as negative, that is huge,
    /// and sent the column to the hashed counter. The other rows spread over 40,000 and up, too
    /// many distinct values for a dictionary layout.
    @Test
    void unsignedShortZoneMinsAcrossTheSignBit(@TempDir Path tmp) throws Exception {
        // Given
        Random random = new Random(19);
        int[] mins = {32_700, 32_700, 32_700, 32_700, 32_710, 32_710, 32_750, 32_770, 32_771, 32_800, 32_801, 32_900};
        short[] values = new short[64 * ZONE];
        for (int z = 0; z < 64; z++) {
            values[z * ZONE] = (short) mins[random.nextInt(mins.length)];
            for (int i = 1; i < ZONE; i++) {
                values[z * ZONE + i] = (short) (40_000 + random.nextInt(25_000));
            }
        }

        // When / Then
        assertSameBuffers(PType.U16, values, tmp);
    }

    /// Float zone mins, which Rust collects into a hash set. Every other row is distinct, so the
    /// column gets no dictionary layout (a separate, first-seen builder in both writers) and the
    /// only dictionary is the zone table's.
    @Test
    void floatZoneMins(@TempDir Path tmp) throws Exception {
        // Given — six mins ALP cannot shorten, at uneven frequencies
        Random random = new Random(11);
        double[] pool = randomDoubles(random, 6);
        double[] mins = new double[64];
        for (int z = 0; z < mins.length; z++) {
            mins[z] = pool[Math.min(random.nextInt(12), pool.length - 1)];
        }

        // When / Then
        assertSameBuffers(PType.F64, doubleColumn(random, mins), tmp);
    }

    /// 56 zones: the float set is sized for 28, and the first 28 mins are distinct, so it is full;
    /// a later duplicate grows it, as Rust's set reserves on every insert, and Rust iterates the
    /// grown table.
    @Test
    void floatZoneMinsFillingTheSet(@TempDir Path tmp) throws Exception {
        // Given
        Random random = new Random(23);
        double[] pool = randomDoubles(random, 28);
        double[] mins = new double[56];
        for (int z = 0; z < mins.length; z++) {
            mins[z] = pool[z < pool.length ? z : random.nextInt(pool.length)];
        }

        // When / Then
        assertSameBuffers(PType.F64, doubleColumn(random, mins), tmp);
    }

    private static double[] randomDoubles(Random random, int count) {
        double[] out = new double[count];
        for (int i = 0; i < count; i++) {
            out[i] = random.nextDouble() * 1e6;
        }
        return out;
    }

    /// One zone per min, its other rows distinct and above every min.
    private static double[] doubleColumn(Random random, double[] mins) {
        double[] values = new double[mins.length * ZONE];
        for (int z = 0; z < mins.length; z++) {
            values[z * ZONE] = mins[z];
            for (int i = 1; i < ZONE; i++) {
                values[z * ZONE + i] = 1e7 + random.nextDouble() * 1e6;
            }
        }
        return values;
    }

    /// `zones` zones whose mins are drawn from `mins` with a seeded random, the other rows above
    /// every min.
    private static int[] intColumn(int zones, int[] mins) {
        Random random = new Random(5);
        int[] values = new int[zones * ZONE];
        for (int z = 0; z < zones; z++) {
            values[z * ZONE] = mins[random.nextInt(mins.length)];
            for (int i = 1; i < ZONE; i++) {
                values[z * ZONE + i] = 1_500_000_000 + random.nextInt(1000);
            }
        }
        return values;
    }

    /// `count` wide `I32` values below 2^29 whose `FxHasher` hash (Rust's `rustc-hash`: value times
    /// `K`, rotated left by 26) falls in buckets 0 to 3 of a table of `buckets`.
    private static int[] collidingInts(int count, int buckets) {
        Random random = new Random(17);
        int[] out = new int[count];
        int n = 0;
        while (n < count) {
            int v = random.nextInt(1 << 29);
            long hash = Long.rotateLeft((v & 0xFFFFFFFFL) * 0xf1357aea2e62a9c5L, 26);
            if ((hash & (buckets - 1)) < 4) {
                out[n++] = v;
            }
        }
        return out;
    }

    private static void assertSameBuffers(PType ptype, Object values, Path tmp) throws Exception {
        // Given
        Path jniFile = tmp.resolve("jni.vortex");
        Path javaFile = tmp.resolve("java.vortex");
        writeWithJni(jniFile, ptype, values);
        List<List<String>> expected = EncodingTrees.flatBuffersHex(jniFile);

        // When
        writeWithJava(javaFile, new DType.Primitive(ptype, false), values);

        // Then
        assertThat(EncodingTrees.of(javaFile)).isEqualTo(EncodingTrees.of(jniFile));
        assertThat(EncodingTrees.flatBuffersHex(javaFile)).isEqualTo(expected);
    }

    private static void writeWithJni(Path path, PType ptype, Object values) throws Exception {
        ArrowType type = switch (ptype) {
            case I32 -> new ArrowType.Int(32, true);
            case U16 -> new ArrowType.Int(16, false);
            case F64 -> new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE);
            default -> throw new IllegalArgumentException(ptype.toString());
        };
        Schema schema = new Schema(List.of(Field.notNullable("v", type)));
        int rows = Array.getLength(values);
        try (VortexWriter writer = VortexWriter.builder(SESSION, path.toAbsolutePath().toUri().toString(), schema,
                ALLOCATOR).build();
             VectorSchemaRoot root = VectorSchemaRoot.create(schema, ALLOCATOR)) {
            switch (root.getVector("v")) {
                case IntVector vector -> {
                    vector.allocateNew(rows);
                    for (int i = 0; i < rows; i++) {
                        vector.set(i, ((int[]) values)[i]);
                    }
                }
                case UInt2Vector vector -> {
                    vector.allocateNew(rows);
                    for (int i = 0; i < rows; i++) {
                        vector.set(i, (char) ((short[]) values)[i]);
                    }
                }
                case Float8Vector vector -> {
                    vector.allocateNew(rows);
                    for (int i = 0; i < rows; i++) {
                        vector.set(i, ((double[]) values)[i]);
                    }
                }
                default -> throw new IllegalArgumentException(ptype.toString());
            }
            root.getVector("v").setValueCount(rows);
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
