package io.github.dfa1.vortex.integration;

import dev.vortex.api.Session;
import dev.vortex.arrow.ArrowAllocation;
import dev.vortex.jni.NativeLoader;
import io.github.dfa1.vortex.core.fbs.FbsDType;
import io.github.dfa1.vortex.core.fbs.FbsFileStatistics;
import io.github.dfa1.vortex.core.fbs.FbsFooter;
import io.github.dfa1.vortex.core.fbs.FbsLayout;
import io.github.dfa1.vortex.core.fbs.FbsPostscript;
import io.github.dfa1.vortex.core.fbs.FbsPostscriptSegment;
import io.github.dfa1.vortex.core.io.VortexFormat;
import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.DTypes;
import io.github.dfa1.vortex.writer.VortexWriter;
import io.github.dfa1.vortex.writer.WriteOptions;
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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.foreign.MemorySegment;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/// A fitness function for the file's metadata (#664): every optional field of the postscript and the
/// blobs it points to (footer, dtype, layout, file statistics) that vortex-jni sets for a file, the
/// Java writer sets for the same rows, and the other way round.
///
/// The interop tests compare what a file holds, never which of these fields it carries, and every
/// field is optional on the wire: a reader treats a missing one as "not written". So the footer's
/// file statistics went unwritten for releases without any test failing. This walks the generated
/// FlatBuffer readers by reflection rather than naming the fields, so a field added to a schema is
/// checked too. Nested layout fields give the tree's depth away as well: it first caught a
/// `vortex.chunked` node Java wrote around a single chunk, which Rust writes as the chunk itself.
class FooterFieldParityIntegrationTest {

    private static final Session SESSION = Session.create();
    private static final BufferAllocator ALLOCATOR = ArrowAllocation.rootAllocator();
    // past one 8192-row zone, so the zone maps hold more than one row
    private static final int ROWS = 20_000;

    @BeforeAll
    static void loadJni() {
        NativeLoader.loadJni();
    }

    @Test
    void javaSetsTheMetadataFieldsJniSets(@TempDir Path tmp) throws Exception {
        // Given — the same rows through both writers
        Path jni = tmp.resolve("jni.vortex");
        writeJni(jni);
        Path java = tmp.resolve("java.vortex");
        writeJava(java);

        // When
        TreeSet<String> result = presentFields(java);

        // Then
        assertThat(result).isEqualTo(presentFields(jni));
    }

    private static long i64(int row) {
        return row * 3L - 100;
    }

    private static boolean valid(int row) {
        return row % 7 != 0;
    }

    private static String text(int row) {
        return "k" + row % 50;
    }

    private static void writeJni(Path file) throws Exception {
        Schema arrow = new Schema(List.of(
                Field.notNullable("i64", new ArrowType.Int(64, true)),
                Field.nullable("i32n", new ArrowType.Int(32, true)),
                Field.notNullable("f64", new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE)),
                Field.notNullable("text", ArrowType.Utf8.INSTANCE),
                Field.notNullable("flag", ArrowType.Bool.INSTANCE)));
        try (dev.vortex.api.VortexWriter w = dev.vortex.api.VortexWriter.builder(SESSION,
                file.toAbsolutePath().toUri().toString(), arrow, ALLOCATOR).build();
             VectorSchemaRoot root = VectorSchemaRoot.create(arrow, ALLOCATOR)) {
            root.allocateNew();
            for (int i = 0; i < ROWS; i++) {
                ((BigIntVector) root.getVector("i64")).setSafe(i, i64(i));
                IntVector i32n = (IntVector) root.getVector("i32n");
                if (valid(i)) {
                    i32n.setSafe(i, i % 1000);
                } else {
                    i32n.setNull(i);
                }
                ((Float8Vector) root.getVector("f64")).setSafe(i, i * 0.5);
                ((VarCharVector) root.getVector("text")).setSafe(i, text(i).getBytes(StandardCharsets.UTF_8));
                ((BitVector) root.getVector("flag")).setSafe(i, i % 2);
            }
            root.setRowCount(ROWS);
            try (ArrowArray array = ArrowArray.allocateNew(ALLOCATOR); ArrowSchema schema = ArrowSchema.allocateNew(ALLOCATOR)) {
                Data.exportVectorSchemaRoot(ALLOCATOR, root, null, array, schema);
                w.writeBatch(array.memoryAddress(), schema.memoryAddress());
            }
        }
    }

    private static void writeJava(Path file) throws Exception {
        List<ColumnName> names = List.of("i64", "i32n", "f64", "text", "flag").stream().map(ColumnName::of).toList();
        DType.Struct schema = new DType.Struct(names,
                List.of(DTypes.I64, DTypes.I32.asNullable(), DTypes.F64, DTypes.UTF8, DTypes.BOOL), false);
        long[] i64 = new long[ROWS];
        Integer[] i32n = new Integer[ROWS];
        double[] f64 = new double[ROWS];
        String[] text = new String[ROWS];
        boolean[] flag = new boolean[ROWS];
        for (int i = 0; i < ROWS; i++) {
            i64[i] = i64(i);
            i32n[i] = valid(i) ? i % 1000 : null;
            f64[i] = i * 0.5;
            text[i] = text(i);
            flag[i] = i % 2 == 1;
        }
        Map<ColumnName, Object> chunk = new LinkedHashMap<>();
        List<Object> values = List.of(i64, i32n, f64, text, flag);
        for (int c = 0; c < names.size(); c++) {
            chunk.put(names.get(c), values.get(c));
        }
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             VortexWriter w = VortexWriter.create(ch, schema, WriteOptions.defaults())) {
            w.writeChunk(chunk);
        }
    }

    /// The path of every field set in the postscript and the blobs it points to; a vector of tables
    /// contributes the union of its elements' fields.
    private static TreeSet<String> presentFields(Path file) throws Exception {
        MemorySegment seg = MemorySegment.ofArray(Files.readAllBytes(file));
        long size = seg.byteSize();
        int psLen = Short.toUnsignedInt(seg.get(VortexFormat.LE_SHORT, size - VortexFormat.TRAILER_SIZE + 2));
        FbsPostscript ps = FbsPostscript.getRootAsFbsPostscript(seg.asSlice(size - VortexFormat.TRAILER_SIZE - psLen, psLen));
        TreeSet<String> fields = new TreeSet<>();
        collect(ps, "postscript", fields);
        blob(seg, ps.footer(), "footer", FbsFooter::getRootAsFbsFooter, fields);
        blob(seg, ps.dtype(), "dtype", FbsDType::getRootAsFbsDType, fields);
        blob(seg, ps.layout(), "layout", FbsLayout::getRootAsFbsLayout, fields);
        blob(seg, ps.statistics(), "statistics", FbsFileStatistics::getRootAsFbsFileStatistics, fields);
        return fields;
    }

    private static void blob(MemorySegment file, FbsPostscriptSegment at, String name,
                             Function<MemorySegment, Object> root, TreeSet<String> fields) throws Exception {
        if (at != null) {
            collect(root.apply(file.asSlice(at.offset(), at.length())), name, fields);
        }
    }

    /// Adds the fields `table` has set: a `hasX()` that is true (vectors, optional scalars), or a
    /// table-typed `x()` that is not `null`, recursing into tables and vectors of tables.
    private static void collect(Object table, String path, TreeSet<String> fields) throws Exception {
        for (Method m : table.getClass().getMethods()) {
            if (Modifier.isStatic(m.getModifiers()) || m.getDeclaringClass() != table.getClass()) {
                continue;
            }
            String name = m.getName();
            if (m.getParameterCount() == 0 && name.startsWith("has") && m.getReturnType() == boolean.class) {
                if ((boolean) m.invoke(table)) {
                    fields.add(path + "." + Character.toLowerCase(name.charAt(3)) + name.substring(4));
                }
            } else if (m.getParameterCount() == 0 && isGenerated(m.getReturnType())) {
                Object child = m.invoke(table);
                if (child != null) {
                    fields.add(path + "." + name);
                    collect(child, path + "." + name, fields);
                }
            } else if (m.getParameterCount() == 1 && m.getParameterTypes()[0] == int.class && isGenerated(m.getReturnType())) {
                Method length = table.getClass().getMethod(name + "Length");
                int n = (int) length.invoke(table);
                for (int j = 0; j < n; j++) {
                    collect(m.invoke(table, j), path + "." + name + "[]", fields);
                }
            }
        }
    }

    private static boolean isGenerated(Class<?> type) {
        return type.getPackage() == FbsFooter.class.getPackage() && type.getSimpleName().startsWith("Fbs");
    }
}
