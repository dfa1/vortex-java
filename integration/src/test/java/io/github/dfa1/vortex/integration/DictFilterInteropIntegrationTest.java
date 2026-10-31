package io.github.dfa1.vortex.integration;

import dev.vortex.api.Session;
import dev.vortex.api.VortexWriter;
import dev.vortex.arrow.ArrowAllocation;
import dev.vortex.jni.NativeLoader;
import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.reader.Chunk;
import io.github.dfa1.vortex.reader.ScanFilter;
import io.github.dfa1.vortex.reader.ScanIterator;
import io.github.dfa1.vortex.reader.ScanOptions;
import io.github.dfa1.vortex.reader.VortexReader;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.VarBinDictArray;
import io.github.dfa1.vortex.reader.array.VarBinSlicedArray;
import io.github.dfa1.vortex.reader.compute.Compute;
import io.github.dfa1.vortex.reader.compute.Predicate;
import io.github.dfa1.vortex.reader.compute.RowSelection;
import io.github.dfa1.vortex.reader.layout.Layout;
import io.github.dfa1.vortex.writer.WriteOptions;
import io.github.dfa1.vortex.writer.encode.NullableData;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Named.named;

/// A filtered scan of a dictionary-encoded string column: the writer stores it as a `vortex.dict`
/// layout, which the scan prunes from the dictionary values alone and filters through a bitmap
/// built from the codes. Both must select exactly the rows the plain row-by-row test selects, for
/// a vortex-jni-written file with `u8` codes, one with `u16` codes, and a vortex-java-written one.
class DictFilterInteropIntegrationTest {

    private static final Session SESSION = Session.create();
    private static final BufferAllocator ALLOCATOR = ArrowAllocation.rootAllocator();
    private static final ColumnName S = ColumnName.of("s");
    private static final ColumnName ID = ColumnName.of("id");
    private static final int BATCHES = 3;
    private static final int BATCH_ROWS = 1_000;

    @TempDir
    static Path tmp;
    private static List<Fixture> fixtures;

    static {
        NativeLoader.loadJni();
    }

    /// A written file and what the filters need to know about it.
    ///
    /// @param name    how the test reports it
    /// @param file    the file
    /// @param written `s` by row, `null` for a null row
    /// @param present a value some rows hold
    /// @param missing a value no row holds, between the column's min and max so that the zone map
    ///                cannot prune it and only the dictionary can tell
    /// @param codes   the code width the file's dictionary must decode with
    /// @param layout  whether the dictionary is a `vortex.dict` layout, which the scan can prune
    record Fixture(String name, Path file, List<String> written, String present, String missing, PType codes,
                   boolean layout) {
        @Override
        public String toString() {
            return name;
        }
    }

    @BeforeAll
    static void writeFixtures() throws Exception {
        String[] few = {"AAPL", "MSFT", "NVDA", null};
        String[] many = new String[301];
        for (int i = 0; i < 300; i++) {
            many[i] = "S%03d".formatted(i);
        }
        Path rustFew = tmp.resolve("jni_dict_u8.vortex");
        Path rustMany = tmp.resolve("jni_dict_u16.vortex");
        Path javaFew = tmp.resolve("java_dict.vortex");
        Path javaChunks = tmp.resolve("java_chunk_dict.vortex");
        fixtures = List.of(
                // both writers give a dictionary layout u16 codes, however few its values
                new Fixture("vortex-jni, 4 values", rustFew, writeWithJni(rustFew, few), "MSFT", "BBBB", PType.U16, true),
                new Fixture("vortex-jni, 300 values", rustMany, writeWithJni(rustMany, many), "S123", "S1000", PType.U16,
                        true),
                new Fixture("vortex-java", javaFew, writeWithJava(javaFew, few, WriteOptions.defaults()), "MSFT", "BBBB",
                        PType.U16, true),
                // without the global dictionary each chunk is dictionary-encoded on its own, with u8 codes
                new Fixture("vortex-java, per-chunk dictionary", javaChunks,
                        writeWithJava(javaChunks, few, WriteOptions.defaults().withGlobalDict(false)), "MSFT", "BBBB",
                        PType.U8, false));
    }

    static Stream<Arguments> filters() {
        List<Function<Fixture, ScanFilter>> filters = List.of(
                f -> ScanFilter.eq(S, f.present()),
                f -> ScanFilter.eq(S, f.missing()),
                f -> ScanFilter.neq(S, f.missing()),
                // a null row satisfies IS NULL whatever the values, so the dictionary cannot prune
                f -> new ScanFilter.Column(S, new Predicate.Or(new Predicate.Eq(f.missing()), new Predicate.IsNull())),
                f -> ScanFilter.and(ScanFilter.eq(S, f.missing()), ScanFilter.lt(ID, 10L)),
                f -> ScanFilter.and(ScanFilter.eq(S, f.present()), ScanFilter.gte(ID, 1500L)));
        List<String> names = List.of("eq present", "eq missing", "neq missing", "missing or null",
                "missing and id", "present and id");
        return Stream.of(0, 1, 2, 3).flatMap(fixture -> Stream.iterate(0, i -> i < filters.size(), i -> i + 1)
                .map(i -> Arguments.of(fixture, named(names.get(i), filters.get(i)))));
    }

    @ParameterizedTest(name = "{1} over fixture {0}")
    @MethodSource("filters")
    void filteredScan_selectsTheRowsTheRowTestSelects(int index, Function<Fixture, ScanFilter> filterOf)
            throws Exception {
        // Given
        Fixture fixture = fixtures.get(index);
        assertThat(codesOf(fixture.file())).as("%s decodes s as a dictionary", fixture).isEqualTo(fixture.codes());
        ScanFilter filter = filterOf.apply(fixture);
        List<Long> expected = new ArrayList<>();
        for (int id = 0; id < fixture.written().size(); id++) {
            if (matches(filter, fixture.written().get(id), id)) {
                expected.add((long) id);
            }
        }

        // When
        List<Long> result = scanIds(fixture.file(), filter);

        // Then
        assertThat(result).as(fixture.name()).isEqualTo(expected);
    }

    // the fixtures written as a vortex.dict layout: a per-chunk dictionary encoding is not pruned
    @ParameterizedTest(name = "fixture {0}")
    @ValueSource(ints = {0, 1, 2})
    void valueMissingFromTheDictionary_skipsEveryChunk(int index) throws Exception {
        // Given
        Fixture fixture = fixtures.get(index);
        try (VortexReader reader = VortexReader.open(fixture.file())) {
            assertThat(fixture.layout() && anyDict(reader.layout())).as(fixture.name()).isTrue();
        }
        assertThat(countChunks(fixture.file(), ScanOptions.columns(S, ID))).isPositive();

        // When
        long result = countChunks(fixture.file(), ScanOptions.columns(S, ID).withFilter(ScanFilter.eq(S, fixture.missing())));

        // Then
        assertThat(result).as(fixture.name()).isZero();
    }

    private static long countChunks(Path file, ScanOptions options) throws Exception {
        long chunks = 0;
        try (VortexReader reader = VortexReader.open(file);
             ScanIterator scan = reader.scan(options)) {
            while (scan.hasNext()) {
                scan.next().close();
                chunks++;
            }
        }
        return chunks;
    }

    private static List<Long> scanIds(Path file, ScanFilter filter) throws Exception {
        List<Long> ids = new ArrayList<>();
        Function<Chunk, RowSelection> matcher = Compute.matcher(filter);
        try (VortexReader reader = VortexReader.open(file);
             ScanIterator scan = reader.scan(ScanOptions.columns(S, ID).withFilter(filter))) {
            while (scan.hasNext()) {
                try (Chunk chunk = scan.next()) {
                    RowSelection selected = matcher.apply(chunk);
                    LongArray idColumn = chunk.column(ID);
                    // walked as the SQL scan walks it, jumping to each selected row
                    for (long r = selected.nextSelected(0); r >= 0; r = selected.nextSelected(r + 1)) {
                        ids.add(idColumn.getLong(r));
                    }
                }
            }
        }
        return ids;
    }

    /// The code width `s` decodes with, or `null` when it does not decode as a dictionary.
    private static PType codesOf(Path file) throws Exception {
        try (VortexReader reader = VortexReader.open(file);
             ScanIterator scan = reader.scan(ScanOptions.columns(S))) {
            try (Chunk chunk = scan.next()) {
                Array s = chunk.column(S);
                if (s instanceof MaskedArray masked) {
                    s = masked.inner();
                }
                if (s instanceof VarBinSlicedArray sliced) {
                    s = sliced.inner();
                }
                return s instanceof VarBinDictArray dict ? dict.dictCodesPType() : null;
            }
        }
    }

    private static boolean anyDict(Layout layout) {
        return layout.isDict() || layout.children().stream().anyMatch(DictFilterInteropIntegrationTest::anyDict);
    }

    /// The oracle: `filter` worked out on the written values with plain Java.
    private static boolean matches(ScanFilter filter, String s, long id) {
        return switch (filter) {
            case ScanFilter.And(var parts) -> parts.stream().allMatch(part -> matches(part, s, id));
            case ScanFilter.Column(var column, var predicate) -> holds(predicate, column.equals(ID) ? id : s);
        };
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static boolean holds(Predicate predicate, Object value) {
        return switch (predicate) {
            case Predicate.IsNull _ -> value == null;
            case Predicate.Or(var left, var right) -> holds(left, value) || holds(right, value);
            case Predicate.Eq(var v) -> value != null && value.equals(v);
            case Predicate.Neq(var v) -> value != null && !value.equals(v);
            case Predicate.Lt(var v) -> value != null && ((Comparable) value).compareTo(v) < 0;
            case Predicate.Gte(var v) -> value != null && ((Comparable) value).compareTo(v) >= 0;
            default -> throw new IllegalArgumentException("no oracle for " + predicate);
        };
    }

    /// The `s` of each row: a seeded random pick of `symbols`, nulls included.
    private static List<String> symbolsByRow(String[] symbols) {
        java.util.Random random = new java.util.Random(11);
        List<String> values = new ArrayList<>();
        for (int i = 0; i < BATCHES * BATCH_ROWS; i++) {
            values.add(symbols[random.nextInt(symbols.length)]);
        }
        return values;
    }

    /// Writes `s` and `id` (the row number) with vortex-jni in [#BATCHES] batches.
    private static List<String> writeWithJni(Path path, String[] symbols) throws Exception {
        List<String> values = symbolsByRow(symbols);
        Schema schema = new Schema(List.of(
                Field.nullable("s", new ArrowType.Utf8()),
                Field.notNullable("id", new ArrowType.Int(64, true))));
        try (VortexWriter writer = VortexWriter.builder(SESSION, path.toAbsolutePath().toUri().toString(), schema,
                ALLOCATOR).build()) {
            for (int batch = 0; batch < BATCHES; batch++) {
                try (VectorSchemaRoot root = VectorSchemaRoot.create(schema, ALLOCATOR)) {
                    VarCharVector s = (VarCharVector) root.getVector("s");
                    BigIntVector ids = (BigIntVector) root.getVector("id");
                    s.allocateNew(BATCH_ROWS);
                    ids.allocateNew(BATCH_ROWS);
                    for (int i = 0; i < BATCH_ROWS; i++) {
                        int row = batch * BATCH_ROWS + i;
                        String symbol = values.get(row);
                        if (symbol == null) {
                            s.setNull(i);
                        } else {
                            s.setSafe(i, symbol.getBytes(StandardCharsets.UTF_8));
                        }
                        ids.set(i, row);
                    }
                    root.setRowCount(BATCH_ROWS);
                    JniFiles.writeBatch(ALLOCATOR, writer, root);
                }
            }
        }
        return values;
    }

    /// Writes `s` and `id` (the row number) with vortex-java in [#BATCHES] chunks.
    private static List<String> writeWithJava(Path path, String[] symbols, WriteOptions options) throws IOException {
        List<String> values = symbolsByRow(symbols);
        DType.Struct schema = new DType.Struct(List.of(S, ID), List.of(new DType.Utf8(true), DType.I64), false);
        try (var channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var writer = io.github.dfa1.vortex.writer.VortexWriter.create(channel, schema, options)) {
            for (int batch = 0; batch < BATCHES; batch++) {
                String[] s = new String[BATCH_ROWS];
                boolean[] valid = new boolean[BATCH_ROWS];
                long[] ids = new long[BATCH_ROWS];
                for (int i = 0; i < BATCH_ROWS; i++) {
                    int row = batch * BATCH_ROWS + i;
                    String symbol = values.get(row);
                    s[i] = symbol == null ? "" : symbol;
                    valid[i] = symbol != null;
                    ids[i] = row;
                }
                writer.writeChunk(Map.of(S, new NullableData(s, valid), ID, ids));
            }
        }
        return values;
    }
}
