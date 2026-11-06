package io.github.dfa1.vortex.integration;

import dev.vortex.api.Session;
import dev.vortex.api.VortexWriter;
import dev.vortex.arrow.ArrowAllocation;
import dev.vortex.jni.NativeLoader;
import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.DTypes;
import io.github.dfa1.vortex.writer.WriteOptions;
import io.github.dfa1.vortex.writer.encode.NullableData;
import org.apache.arrow.c.ArrowArray;
import org.apache.arrow.c.ArrowSchema;
import org.apache.arrow.c.Data;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.BitVector;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.FloatingPointPrecision;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/// The array stats on the root node of every flat segment, against what vortex-jni writes for the same
/// data (#583). Rust's flat layout keeps only the stats the compressor had cached on the array, and each
/// encoding passes on some of them: integers keep min, max, `is_constant`, `null_count` and the
/// uncompressed size under `vortex.primitive` and `fastlanes.bitpacked`; floats and strings keep none
/// under ALP, FSST or onpair. A sequence carries its sorted flags, and the validity bitmap of a
/// nullable integer its true count.
class NodeStatsInteropIntegrationTest {

    private static final Session SESSION = Session.create();
    private static final BufferAllocator ALLOCATOR = ArrowAllocation.rootAllocator();
    private static final int N = 20_000;

    static {
        NativeLoader.loadJni();
    }

    @Test
    void javaWritesTheRootStatsJniWrites(@TempDir Path tmp) throws Exception {
        // Given — the same rows through both writers: sorted, random and small-range integers, floats,
        // low- and high-cardinality strings, bools, and a nullable integer and float
        Random r = new Random(7);
        long[] sorted = new long[N];
        long[] random = new long[N];
        int[] small = new int[N];
        double[] rf = new double[N];
        double[] sf = new double[N];
        String[] low = new String[N];
        String[] high = new String[N];
        boolean[] bool = new boolean[N];
        int[] nullable = new int[N];
        boolean[] valid = new boolean[N];
        double[] patched = new double[N];
        long[] descending = new long[N];
        double[] nullableFloat = new double[N];
        boolean[] validFloat = new boolean[N];
        for (int i = 0; i < N; i++) {
            sorted[i] = i;
            random[i] = r.nextLong();
            small[i] = r.nextInt(100);
            rf[i] = r.nextDouble() * 1000;
            sf[i] = i * 0.5;
            patched[i] = i % 100 == 0 ? r.nextDouble() * 1e9 : i * 0.01;
            descending[i] = 100_000L - i * 3L;
            low[i] = "k" + r.nextInt(10);
            high[i] = "value-" + Long.toString(r.nextLong() & 0xffffffffL, 36);
            bool[i] = r.nextBoolean();
            valid[i] = i % 7 != 0;
            validFloat[i] = i % 5 != 0;
            nullableFloat[i] = validFloat[i] ? r.nextDouble() * 100 : 0;
            nullable[i] = valid[i] ? r.nextInt(5_000) : 0;
        }
        Schema arrow = new Schema(List.of(
                Field.notNullable("sorted", new ArrowType.Int(64, true)), Field.notNullable("random", new ArrowType.Int(64, true)),
                Field.notNullable("small", new ArrowType.Int(32, true)),
                Field.notNullable("rf", new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE)),
                Field.notNullable("sf", new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE)),
                Field.notNullable("low", ArrowType.Utf8.INSTANCE), Field.notNullable("high", ArrowType.Utf8.INSTANCE),
                Field.notNullable("bool", ArrowType.Bool.INSTANCE),
                Field.nullable("nullable", new ArrowType.Int(32, true)),
                Field.notNullable("patched", new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE)),
                Field.notNullable("descending", new ArrowType.Int(64, true)),
                Field.nullable("nullf", new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE))));
        Path jni = tmp.resolve("jni.vortex");
        try (VortexWriter w = VortexWriter.builder(SESSION, jni.toAbsolutePath().toUri().toString(), arrow, ALLOCATOR).build();
             VectorSchemaRoot root = VectorSchemaRoot.create(arrow, ALLOCATOR)) {
            BigIntVector a = (BigIntVector) root.getVector("sorted");
            BigIntVector b = (BigIntVector) root.getVector("random");
            IntVector c = (IntVector) root.getVector("small");
            Float8Vector d = (Float8Vector) root.getVector("rf");
            Float8Vector e = (Float8Vector) root.getVector("sf");
            VarCharVector f = (VarCharVector) root.getVector("low");
            VarCharVector g = (VarCharVector) root.getVector("high");
            BitVector h = (BitVector) root.getVector("bool");
            IntVector nv = (IntVector) root.getVector("nullable");
            Float8Vector pv = (Float8Vector) root.getVector("patched");
            BigIntVector dv = (BigIntVector) root.getVector("descending");
            Float8Vector nf = (Float8Vector) root.getVector("nullf");
            root.allocateNew();
            for (int i = 0; i < N; i++) {
                a.setSafe(i, sorted[i]);
                b.setSafe(i, random[i]);
                c.setSafe(i, small[i]);
                d.setSafe(i, rf[i]);
                e.setSafe(i, sf[i]);
                f.setSafe(i, low[i].getBytes(StandardCharsets.UTF_8));
                g.setSafe(i, high[i].getBytes(StandardCharsets.UTF_8));
                h.setSafe(i, bool[i] ? 1 : 0);
                pv.setSafe(i, patched[i]);
                dv.setSafe(i, descending[i]);
                if (validFloat[i]) {
                    nf.setSafe(i, nullableFloat[i]);
                } else {
                    nf.setNull(i);
                }
                if (valid[i]) {
                    nv.setSafe(i, nullable[i]);
                } else {
                    nv.setNull(i);
                }
            }
            root.setRowCount(N);
            try (ArrowArray array = ArrowArray.allocateNew(ALLOCATOR); ArrowSchema as = ArrowSchema.allocateNew(ALLOCATOR)) {
                Data.exportVectorSchemaRoot(ALLOCATOR, root, null, array, as);
                w.writeBatch(array.memoryAddress(), as.memoryAddress());
            }
        }
        Path java = tmp.resolve("java.vortex");
        DType.Struct schema = new DType.Struct(
                List.of("sorted", "random", "small", "rf", "sf", "low", "high", "bool", "nullable", "patched", "descending", "nullf").stream().map(ColumnName::of).toList(),
                List.of(DTypes.I64, DTypes.I64, DTypes.I32, DTypes.F64, DTypes.F64, DTypes.UTF8, DTypes.UTF8, DTypes.BOOL, DTypes.I32.asNullable(), DTypes.F64, DTypes.I64, DTypes.F64.asNullable()), false);
        try (FileChannel ch = FileChannel.open(java, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var w = io.github.dfa1.vortex.writer.VortexWriter.create(ch, schema, WriteOptions.defaults())) {
            Map<ColumnName, Object> chunk = new java.util.LinkedHashMap<>();
            chunk.put(ColumnName.of("sorted"), sorted);
            chunk.put(ColumnName.of("random"), random);
            chunk.put(ColumnName.of("small"), small);
            chunk.put(ColumnName.of("rf"), rf);
            chunk.put(ColumnName.of("sf"), sf);
            chunk.put(ColumnName.of("low"), low);
            chunk.put(ColumnName.of("high"), high);
            chunk.put(ColumnName.of("bool"), bool);
            chunk.put(ColumnName.of("nullable"), new NullableData(nullable, valid));
            chunk.put(ColumnName.of("patched"), patched);
            chunk.put(ColumnName.of("descending"), descending);
            chunk.put(ColumnName.of("nullf"), new NullableData(nullableFloat, validFloat));
            w.writeChunk(chunk);
        }
        List<String> expected = EncodingTrees.withStats(jni);

        // When
        List<String> result = EncodingTrees.withStats(java);

        // Then — every flat segment's root, and the whole tree (children and zone table) of the columns
        // whose encodings do not depend on how each writer trains its string symbol table
        assertThat(flatRoots(result)).isEqualTo(flatRoots(expected));
        List<List<String>> expectedColumns = columns(expected);
        List<List<String>> resultColumns = columns(result);
        assertThat(resultColumns).hasSameSizeAs(expectedColumns);
        for (int c : new int[]{0, 1, 2, 3, 4, 8, 9, 10, 11}) {
            assertThat(resultColumns.get(c)).as("column %d", c).isEqualTo(expectedColumns.get(c));
        }
    }

    /// The tree lines of each top-level column, in file order.
    private static List<List<String>> columns(List<String> tree) {
        List<List<String>> columns = new java.util.ArrayList<>();
        for (String line : tree.subList(1, tree.size())) {
            if (!line.startsWith("   ")) {
                columns.add(new java.util.ArrayList<>());
            }
            columns.getLast().add(line);
        }
        return columns;
    }

    /// The root node line of every flat segment, in file order.
    private static List<String> flatRoots(List<String> tree) {
        List<String> roots = new java.util.ArrayList<>();
        for (int i = 0; i + 1 < tree.size(); i++) {
            if (tree.get(i).strip().equals("vortex.flat")) {
                roots.add(tree.get(i + 1).strip());
            }
        }
        return roots;
    }
}
