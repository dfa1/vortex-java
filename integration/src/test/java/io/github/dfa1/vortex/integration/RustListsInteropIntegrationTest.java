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
import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.complex.BaseListVector;
import org.apache.arrow.vector.complex.ListVector;
import org.apache.arrow.vector.complex.ListViewVector;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// vortex-jni writes nullable list columns as `vortex.list` (Arrow `List`) and `vortex.listview`
/// (Arrow `ListView`); vortex-java must read every row back. Replaces the S3 `list.vortex` /
/// `listview.vortex` fixtures no test downloaded: writing the file through vortex-jni needs no
/// network and lets the data cover nulls, empty lists and varying lengths on purpose.
///
/// The list-view case caught `CsvExporter` failing on any list-view column ("unsupported array
/// type for CSV export: ListViewArray"), which `vortex export` shares.
class RustListsInteropIntegrationTest {

    private static final Session SESSION = Session.create();
    private static final BufferAllocator ALLOCATOR = ArrowAllocation.rootAllocator();
    private static final int ROWS = 5_000;

    static {
        NativeLoader.loadJni();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"vortex.list", "vortex.listview"})
    void jniWritesNullableLists_javaReadsEveryRow(String encoding, @TempDir Path tmp) throws IOException {
        // Given — every 11th row null, lengths cycling 0..3 so empty lists sit between null and
        // non-empty rows, each element distinct so a shifted offset changes the output
        Path file = tmp.resolve("lists.vortex");
        writeJni(file, encoding.equals("vortex.listview"));

        // When
        List<String> result = readColumnAsCsv(file);

        // Then — vortex-jni chose the encoding under test, and every row renders as written
        try (var vf = VortexReader.open(file, ReadRegistry.loadAll())) {
            assertThat(InspectorTree.build(vf).usedEncodings()).contains(encoding);
        }
        assertThat(result).isEqualTo(expectedRows());
    }

    private static void writeJni(Path file, boolean listView) throws IOException {
        Field item = Field.nullable("item", new ArrowType.Int(32, true));
        ArrowType type = listView ? ArrowType.ListView.INSTANCE : ArrowType.List.INSTANCE;
        Schema schema = new Schema(List.of(new Field("v", FieldType.nullable(type), List.of(item))));
        try (VortexWriter writer = VortexWriter.builder(SESSION, file.toUri().toString(), schema, ALLOCATOR).build();
             VectorSchemaRoot root = VectorSchemaRoot.create(schema, ALLOCATOR)) {
            var vector = (BaseListVector) root.getVector("v");
            vector.allocateNew();
            for (int i = 0; i < ROWS; i++) {
                if (i % 11 == 0) {
                    vector.setNull(i);
                    continue;
                }
                int start = listView ? ((ListViewVector) vector).startNewValue(i) : ((ListVector) vector).startNewValue(i);
                IntVector items = (IntVector) (listView
                        ? ((ListViewVector) vector).getDataVector()
                        : ((ListVector) vector).getDataVector());
                for (int k = 0; k < i % 4; k++) {
                    items.setSafe(start + k, i * 7 + k);
                }
                if (listView) {
                    ((ListViewVector) vector).endValue(i, i % 4);
                } else {
                    ((ListVector) vector).endValue(i, i % 4);
                }
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

    /// Row `i` as `CsvExporter` renders it: an empty cell for null, else a JSON array.
    private static List<String> expectedRows() {
        List<String> rows = new ArrayList<>(ROWS);
        for (int i = 0; i < ROWS; i++) {
            if (i % 11 == 0) {
                rows.add("");
                continue;
            }
            var sb = new StringBuilder("[");
            for (int k = 0; k < i % 4; k++) {
                sb.append(k == 0 ? "" : ",").append(i * 7 + k);
            }
            rows.add(sb.append(']').toString());
        }
        return rows;
    }

    private static List<String> readColumnAsCsv(Path file) throws IOException {
        var csv = new StringWriter();
        CsvExporter.exportCsv(file, csv, ExportOptions.defaults());
        var out = new ArrayList<String>(ROWS);
        try (var reader = de.siegmar.fastcsv.reader.CsvReader.builder().skipEmptyLines(false)
                .ofCsvRecord(new StringReader(csv.toString()))) {
            var rows = reader.iterator();
            rows.next(); // header
            rows.forEachRemaining(row -> out.add(row.getField(0)));
        }
        return out;
    }
}
