package io.github.dfa1.vortex.integration;

import dev.vortex.api.DataSource;
import dev.vortex.api.Partition;
import dev.vortex.api.Scan;
import dev.vortex.api.ScanOptions;
import dev.vortex.api.Session;
import dev.vortex.arrow.ArrowAllocation;
import dev.vortex.jni.NativeLoader;
import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.reader.Chunk;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.VortexReader;
import io.github.dfa1.vortex.reader.array.DoubleArray;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.array.StructArray;
import io.github.dfa1.vortex.reader.array.VarBinArray;
import io.github.dfa1.vortex.writer.VortexWriter;
import io.github.dfa1.vortex.writer.WriteOptions;
import io.github.dfa1.vortex.writer.encode.StructData;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.complex.StructVector;
import org.apache.arrow.vector.ipc.ArrowReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// The extension ids the vortex-jni session registers by default beyond the temporal types and
/// uuid — `vortex.json` and the `vortex.st.*` geometries — open in Java without a decoder and read
/// as their storage. Before, a strict Java reader refused files vortex-jni reads. Java writes one
/// column of each storage shape (`Utf8`, `Binary`, a coordinate `Struct`) and both readers must
/// see the same values.
class WellKnownExtensionsInteropIntegrationTest {

    private static final Session SESSION = Session.create();
    private static final BufferAllocator ALLOCATOR = ArrowAllocation.rootAllocator();
    private static final MemorySegment NO_METADATA = MemorySegment.ofArray(new byte[0]);
    private static final DType POINT_STORAGE = new DType.Struct(
            List.of(ColumnName.of("x"), ColumnName.of("y")), List.of(DType.F64, DType.F64), false);
    private static final DType.Struct SCHEMA = new DType.Struct(
            List.of(ColumnName.of("id"), ColumnName.of("j"), ColumnName.of("g"), ColumnName.of("p")),
            List.of(DType.I64,
                    new DType.Extension("vortex.json", DType.UTF8, NO_METADATA, false),
                    new DType.Extension("vortex.st.wkb", DType.BINARY, NO_METADATA, false),
                    new DType.Extension("vortex.st.point", POINT_STORAGE, NO_METADATA, false)),
            false);
    private static final int BATCHES = 2;
    private static final int BATCH_ROWS = 500;

    static {
        NativeLoader.loadJni();
    }

    @Test
    void javaWrites_bothReadersSeeTheStorage(@TempDir Path tmp) throws IOException {
        // Given
        Path file = tmp.resolve("well_known.vortex");
        writeWithJava(file);

        // When
        List<Row> java = readJava(file);
        List<Row> jni = readJni(file);

        // Then
        assertThat(java).hasSize(BATCHES * BATCH_ROWS);
        for (Row row : java) {
            assertThat(row).as("row %d", row.id()).isEqualTo(expected(row.id()));
        }
        assertThat(jni).containsExactlyElementsOf(java);
    }

    /// One row as plain values; `wkb` is compared as a string so record equality works.
    record Row(long id, String json, String wkb, double x, double y) {
    }

    private static Row expected(long id) {
        // a two-byte WKB-like prefix plus the row id: distinct per row, not valid UTF-8 at byte 0
        String wkb = "\u0001" + id;
        return new Row(id, "{\"id\":" + id + ",\"tags\":[\"a\",\"é\"]}", wkb, id * 0.5, -id * 0.25);
    }

    private static void writeWithJava(Path file) throws IOException {
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var writer = VortexWriter.create(ch, SCHEMA, WriteOptions.defaults())) {
            for (int batch = 0; batch < BATCHES; batch++) {
                long[] ids = new long[BATCH_ROWS];
                String[] json = new String[BATCH_ROWS];
                byte[][] wkb = new byte[BATCH_ROWS][];
                double[] xs = new double[BATCH_ROWS];
                double[] ys = new double[BATCH_ROWS];
                for (int i = 0; i < BATCH_ROWS; i++) {
                    long id = (long) batch * BATCH_ROWS + i;
                    Row row = expected(id);
                    ids[i] = id;
                    json[i] = row.json();
                    wkb[i] = row.wkb().getBytes(StandardCharsets.ISO_8859_1);
                    xs[i] = row.x();
                    ys[i] = row.y();
                }
                writer.writeChunk(Map.of(ColumnName.of("id"), ids, ColumnName.of("j"), json,
                        ColumnName.of("g"), wkb, ColumnName.of("p"), new StructData(List.of(xs, ys))));
            }
        }
    }

    private static List<Row> readJava(Path file) throws IOException {
        List<Row> rows = new ArrayList<>();
        // the default registry: no decoder for these ids, and no allowUnknown
        try (VortexReader reader = VortexReader.open(file, ReadRegistry.loadAll());
             var iter = reader.scan(io.github.dfa1.vortex.reader.ScanOptions.all())) {
            while (iter.hasNext()) {
                try (Chunk chunk = iter.next()) {
                    LongArray ids = (LongArray) chunk.column(ColumnName.of("id"));
                    VarBinArray json = (VarBinArray) chunk.column(ColumnName.of("j"));
                    VarBinArray wkb = (VarBinArray) chunk.column(ColumnName.of("g"));
                    StructArray points = (StructArray) chunk.column(ColumnName.of("p"));
                    DoubleArray xs = (DoubleArray) points.field(0);
                    DoubleArray ys = (DoubleArray) points.field(1);
                    for (long i = 0; i < chunk.rowCount(); i++) {
                        rows.add(new Row(ids.getLong(i), json.getString(i),
                                new String(wkb.getBytes(i), StandardCharsets.ISO_8859_1),
                                xs.getDouble(i), ys.getDouble(i)));
                    }
                }
            }
        }
        return rows;
    }

    private static List<Row> readJni(Path file) throws IOException {
        List<Row> rows = new ArrayList<>();
        Scan scan = DataSource.open(SESSION, file.toAbsolutePath().toUri().toString()).scan(ScanOptions.of());
        while (scan.hasNext()) {
            Partition partition = scan.next();
            try (ArrowReader reader = partition.scanArrow(ALLOCATOR)) {
                while (reader.loadNextBatch()) {
                    VectorSchemaRoot root = reader.getVectorSchemaRoot();
                    BigIntVector ids = (BigIntVector) root.getVector("id");
                    FieldVector json = root.getVector("j");
                    FieldVector wkb = root.getVector("g");
                    StructVector points = (StructVector) root.getVector("p");
                    for (int i = 0; i < root.getRowCount(); i++) {
                        rows.add(new Row(ids.get(i), json.getObject(i).toString(),
                                new String((byte[]) wkb.getObject(i), StandardCharsets.ISO_8859_1),
                                (Double) points.getChild("x").getObject(i),
                                (Double) points.getChild("y").getObject(i)));
                    }
                }
            }
        }
        return rows;
    }
}
