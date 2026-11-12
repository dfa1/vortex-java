package io.github.dfa1.vortex.integration;

import dev.vortex.api.Session;
import dev.vortex.api.VortexWriter;
import dev.vortex.arrow.ArrowAllocation;
import dev.vortex.jni.NativeLoader;
import io.github.dfa1.vortex.csv.CsvExporter;
import io.github.dfa1.vortex.csv.ExportOptions;
import io.github.dfa1.vortex.inspect.InspectorTree;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.VortexReader;
import org.apache.arrow.c.ArrowArray;
import org.apache.arrow.c.ArrowSchema;
import org.apache.arrow.c.Data;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.BitVector;
import org.apache.arrow.vector.DateDayVector;
import org.apache.arrow.vector.DecimalVector;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.Float4Vector;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.SmallIntVector;
import org.apache.arrow.vector.TimeMicroVector;
import org.apache.arrow.vector.TimeStampMicroVector;
import org.apache.arrow.vector.TinyIntVector;
import org.apache.arrow.vector.UInt1Vector;
import org.apache.arrow.vector.UInt2Vector;
import org.apache.arrow.vector.UInt4Vector;
import org.apache.arrow.vector.UInt8Vector;
import org.apache.arrow.vector.UuidVector;
import org.apache.arrow.vector.VarBinaryVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.complex.FixedSizeListVector;
import org.apache.arrow.vector.complex.ListVector;
import org.apache.arrow.vector.complex.StructVector;
import org.apache.arrow.vector.extension.UuidType;
import org.apache.arrow.vector.types.DateUnit;
import org.apache.arrow.vector.types.FloatingPointPrecision;
import org.apache.arrow.vector.types.TimeUnit;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// vortex-jni writes a low-cardinality column of every Arrow type it accepts, dict-encoding the ones
/// Rust supports; vortex-java must read each one back and rebuild the exact values.
///
/// The Java reader's `vortex.dict` support grew per type as bugs surfaced (Utf8, then primitives,
/// then I16, then VarBin extensions), leaving shapes like dict-encoded `vortex.uuid` (a
/// FixedSizeList pool) or `vortex.date` (an extension over a primitive pool) rejected with
/// "unsupported dict values shape". This sweeps every type at once so a missing pool shape shows
/// up as one failing case instead of a Raincloud surprise.
///
/// Values are compared through [CsvExporter]'s per-type rendering so one generic comparison
/// covers primitive, nested and extension columns alike (extensions render their storage value).
class DictAllTypesInteropIntegrationTest {

    private static final Session SESSION = Session.create();
    private static final BufferAllocator ALLOCATOR = ArrowAllocation.rootAllocator();
    /// Far more rows than distinct values so the Rust compressor's dict scheme wins wherever it applies.
    private static final int ROWS = 20_000;

    static {
        NativeLoader.loadJni();
    }

    /// One Arrow column type: how to write distinct value `j` at row `i`, how each distinct value
    /// renders after the Java read (an empty string is a null row), and whether Rust dict-encodes
    /// it at all.
    ///
    /// Rust's dict layout only admits `Primitive | Utf8 | Binary` (`dict_layout_supported` in
    /// `vortex-layout/src/layouts/dict/writer.rs`) and its compressor's dict schemes are
    /// integer/float/string only, so the other types never reach a dictionary. The flag is asserted
    /// both ways: a vortex-jni bump that starts dict-encoding, say, dates fails here and points at
    /// the reader's still-missing dict-over-extension pool path.
    private record Case(String name, Field field, Setter setter, List<String> expected, boolean rustDicts) {

        Case notDictedByRust() {
            return new Case(name, field, setter, expected, false);
        }

        @Override
        public String toString() {
            return name;
        }
    }

    @FunctionalInterface
    private interface Setter {
        void set(FieldVector vector, int row, int distinct);
    }

    private static final UUID[] UUIDS = {
        UUID.fromString("00000000-0000-0000-0000-000000000001"),
        UUID.fromString("123e4567-e89b-12d3-a456-426614174000"),
        UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"),
        UUID.fromString("a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11"),
    };

    static Stream<Case> cases() {
        return Stream.of(
                primitive("i8", new ArrowType.Int(8, true),
                        (v, i, j) -> ((TinyIntVector) v).setSafe(i, new byte[]{-128, 0, 7, 127}[j]),
                        "-128", "0", "7", "127"),
                primitive("u8", new ArrowType.Int(8, false),
                        (v, i, j) -> ((UInt1Vector) v).setSafe(i, new int[]{0, 1, 128, 255}[j]),
                        "0", "1", "128", "255"),
                primitive("i16", new ArrowType.Int(16, true),
                        (v, i, j) -> ((SmallIntVector) v).setSafe(i, new short[]{-32768, 0, 300, 32767}[j]),
                        "-32768", "0", "300", "32767"),
                primitive("u16", new ArrowType.Int(16, false),
                        (v, i, j) -> ((UInt2Vector) v).setSafe(i, new int[]{0, 1, 40_000, 65_535}[j]),
                        "0", "1", "40000", "65535"),
                primitive("i32", new ArrowType.Int(32, true),
                        (v, i, j) -> ((IntVector) v).setSafe(i, new int[]{Integer.MIN_VALUE, 0, 42, Integer.MAX_VALUE}[j]),
                        "-2147483648", "0", "42", "2147483647"),
                primitive("u32", new ArrowType.Int(32, false),
                        (v, i, j) -> ((UInt4Vector) v).setSafe(i, new int[]{0, 1, 0x8000_0000, -1}[j]),
                        "0", "1", "2147483648", "4294967295"),
                primitive("i64", new ArrowType.Int(64, true),
                        (v, i, j) -> ((BigIntVector) v).setSafe(i, new long[]{Long.MIN_VALUE, 0, 1L << 40, Long.MAX_VALUE}[j]),
                        "-9223372036854775808", "0", "1099511627776", "9223372036854775807"),
                primitive("u64", new ArrowType.Int(64, false),
                        (v, i, j) -> ((UInt8Vector) v).setSafe(i, new long[]{0, 1, Long.MIN_VALUE, -1}[j]),
                        "0", "1", "9223372036854775808", "18446744073709551615"),
                primitive("f32", new ArrowType.FloatingPoint(FloatingPointPrecision.SINGLE),
                        (v, i, j) -> ((Float4Vector) v).setSafe(i, new float[]{-1.5f, 0f, 3.25f, 1e30f}[j]),
                        "-1.5", "0.0", "3.25", "1.0E30"),
                primitive("f64", new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE),
                        (v, i, j) -> ((Float8Vector) v).setSafe(i, new double[]{-1.1, 0.0, 2.2, 1e300}[j]),
                        "-1.1", "0.0", "2.2", "1.0E300"),
                primitive("bool", ArrowType.Bool.INSTANCE,
                        (v, i, j) -> ((BitVector) v).setSafe(i, j & 1),
                        "false", "true", "false", "true").notDictedByRust(),
                primitive("utf8", ArrowType.Utf8.INSTANCE,
                        (v, i, j) -> ((VarCharVector) v).setSafe(i, utf8(new String[]{"", "alpha", "βeta", "gamma gamma"}[j])),
                        "", "alpha", "βeta", "gamma gamma"),
                primitive("binary", ArrowType.Binary.INSTANCE,
                        (v, i, j) -> ((VarBinaryVector) v).setSafe(i, utf8(new String[]{"x", "yy", "zzz", "wwww"}[j])),
                        "x", "yy", "zzz", "wwww"),
                primitive("decimal(10,2)", new ArrowType.Decimal(10, 2, 128),
                        (v, i, j) -> ((DecimalVector) v).setSafe(i, new BigDecimal(new String[]{"-99999999.99", "0.00", "12.34", "99999999.99"}[j])),
                        "-99999999.99", "0.00", "12.34", "99999999.99").notDictedByRust(),
                // Extensions render their storage value: days / micros since epoch / midnight.
                primitive("date", new ArrowType.Date(DateUnit.DAY),
                        (v, i, j) -> ((DateDayVector) v).setSafe(i, new int[]{-1, 0, 19_000, 20_000}[j]),
                        "-1", "0", "19000", "20000").notDictedByRust(),
                primitive("time", new ArrowType.Time(TimeUnit.MICROSECOND, 64),
                        (v, i, j) -> ((TimeMicroVector) v).setSafe(i, new long[]{0, 1, 3_600_000_000L, 86_399_999_999L}[j]),
                        "0", "1", "3600000000", "86399999999").notDictedByRust(),
                primitive("timestamp", new ArrowType.Timestamp(TimeUnit.MICROSECOND, null),
                        (v, i, j) -> ((TimeStampMicroVector) v).setSafe(i, new long[]{-1, 0, 1_700_000_000_000_000L, 1_800_000_000_000_000L}[j]),
                        "-1", "0", "1700000000000000", "1800000000000000").notDictedByRust(),
                primitive("uuid", UuidType.INSTANCE,
                        (v, i, j) -> ((UuidVector) v).setSafe(i, UUIDS[j]),
                        uuidCells()).notDictedByRust(),
                nested("fixed_size_list<i32,2>",
                        new Field("v", FieldType.notNullable(new ArrowType.FixedSizeList(2)),
                                List.of(Field.notNullable("item", new ArrowType.Int(32, true)))),
                        (v, i, j) -> {
                            var list = (FixedSizeListVector) v;
                            var items = (IntVector) list.getDataVector();
                            list.setNotNull(i);
                            items.setSafe(i * 2, j);
                            items.setSafe(i * 2 + 1, -j);
                        },
                        "[0,0]", "[1,-1]", "[2,-2]", "[3,-3]").notDictedByRust(),
                nested("list<i32>",
                        new Field("v", FieldType.notNullable(ArrowType.List.INSTANCE),
                                List.of(Field.notNullable("item", new ArrowType.Int(32, true)))),
                        (v, i, j) -> {
                            var list = (ListVector) v;
                            var items = (IntVector) list.getDataVector();
                            int start = list.startNewValue(i);
                            for (int k = 0; k < j; k++) {
                                items.setSafe(start + k, k);
                            }
                            list.endValue(i, j);
                        },
                        "[]", "[0]", "[0,1]", "[0,1,2]").notDictedByRust(),
                nested("struct{a:i32}",
                        new Field("v", FieldType.notNullable(ArrowType.Struct.INSTANCE),
                                List.of(Field.notNullable("a", new ArrowType.Int(32, true)))),
                        (v, i, j) -> {
                            var struct = (StructVector) v;
                            struct.setIndexDefined(i);
                            ((IntVector) struct.getChild("a")).setSafe(i, j * 10);
                        },
                        "{\"a\":0}", "{\"a\":10}", "{\"a\":20}", "{\"a\":30}"),
                // Nullable: a null row rides on the codes, the pool itself holds no nulls.
                nested("nullable i32",
                        Field.nullable("v", new ArrowType.Int(32, true)),
                        (v, i, j) -> {
                            if (j == 0) {
                                ((IntVector) v).setNull(i);
                            } else {
                                ((IntVector) v).setSafe(i, j);
                            }
                        },
                        "", "1", "2", "3"),
                nested("nullable utf8",
                        Field.nullable("v", ArrowType.Utf8.INSTANCE),
                        (v, i, j) -> {
                            if (j == 0) {
                                ((VarCharVector) v).setNull(i);
                            } else {
                                ((VarCharVector) v).setSafe(i, utf8("s" + j));
                            }
                        },
                        "", "s1", "s2", "s3"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void jniDictColumn_javaRebuildsValues(Case c, @TempDir Path tmp) throws IOException {
        // Given — ROWS rows cycling through 4 distinct values, written by vortex-jni
        Path file = tmp.resolve("dict.vortex");
        writeJni(file, c);
        List<String> expected = new ArrayList<>(ROWS);
        for (int i = 0; i < ROWS; i++) {
            expected.add(c.expected().get(i % c.expected().size()));
        }

        // When
        List<String> result = readColumnAsCsv(file);

        // Then — Rust dict-encoded exactly the types it supports, and every row matches
        try (var vf = VortexReader.open(file, ReadRegistry.loadAll())) {
            InspectorTree tree = InspectorTree.build(vf);
            assertThat(hasDictLayout(tree.root()) || tree.usedEncodings().contains("vortex.dict"))
                    .as("vortex-jni dict-encoded %s (encodings %s)", c.field().getType(), tree.usedEncodings())
                    .isEqualTo(c.rustDicts());
        }
        assertThat(result).isEqualTo(expected);
    }

    private static void writeJni(Path file, Case c) throws IOException {
        Schema schema = new Schema(List.of(c.field()));
        String uri = file.toAbsolutePath().toUri().toString();
        try (VortexWriter writer = VortexWriter.builder(SESSION, uri, schema, ALLOCATOR).build();
             VectorSchemaRoot root = VectorSchemaRoot.create(schema, ALLOCATOR)) {
            FieldVector vector = root.getVector("v");
            vector.setInitialCapacity(ROWS);
            vector.allocateNew();
            int distinct = c.expected().size();
            for (int i = 0; i < ROWS; i++) {
                c.setter().set(vector, i, i % distinct);
            }
            vector.setValueCount(ROWS);
            root.setRowCount(ROWS);
            try (ArrowArray arr = ArrowArray.allocateNew(ALLOCATOR);
                 ArrowSchema arrowSchema = ArrowSchema.allocateNew(ALLOCATOR)) {
                Data.exportVectorSchemaRoot(ALLOCATOR, root, null, arr, arrowSchema);
                writer.writeBatch(arr.memoryAddress(), arrowSchema.memoryAddress());
            }
        }
    }

    private static List<String> readColumnAsCsv(Path file) throws IOException {
        var csv = new StringWriter();
        CsvExporter.exportCsv(file, csv, ExportOptions.defaults());
        var out = new ArrayList<String>(ROWS);
        try (var reader = de.siegmar.fastcsv.reader.CsvReader.builder().skipEmptyLines(false).ofCsvRecord(new StringReader(csv.toString()))) {
            var rows = reader.iterator();
            rows.next(); // header
            rows.forEachRemaining(row -> out.add(row.getField(0)));
        }
        return out;
    }

    private static boolean hasDictLayout(InspectorTree.Node node) {
        if (node.layout().isDict()) {
            return true;
        }
        for (InspectorTree.Node child : node.children()) {
            if (hasDictLayout(child)) {
                return true;
            }
        }
        return false;
    }

    private static Case primitive(String name, ArrowType type, Setter setter, String... expected) {
        return nested(name, Field.notNullable("v", type), setter, expected);
    }

    private static Case nested(String name, Field field, Setter setter, String... expected) {
        return new Case(name, field, setter, List.of(expected), true);
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /// `vortex.uuid` stores 16 big-endian bytes as a FixedSizeList of u8, which the CSV exporter
    /// renders as a JSON array of the unsigned byte values.
    private static String[] uuidCells() {
        String[] cells = new String[UUIDS.length];
        for (int u = 0; u < UUIDS.length; u++) {
            var sb = new StringBuilder("[");
            long[] halves = {UUIDS[u].getMostSignificantBits(), UUIDS[u].getLeastSignificantBits()};
            for (int b = 0; b < 16; b++) {
                if (b > 0) {
                    sb.append(',');
                }
                sb.append((halves[b / 8] >>> (56 - 8 * (b % 8))) & 0xFF);
            }
            cells[u] = sb.append(']').toString();
        }
        return cells;
    }
}
