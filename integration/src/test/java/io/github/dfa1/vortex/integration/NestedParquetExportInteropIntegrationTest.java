package io.github.dfa1.vortex.integration;

import dev.hardwood.InputFile;
import dev.hardwood.reader.ParquetFileReader;
import dev.hardwood.reader.RowReader;
import dev.hardwood.row.PqList;
import dev.hardwood.row.PqStruct;
import dev.vortex.api.Session;
import dev.vortex.api.VortexWriter;
import dev.vortex.arrow.ArrowAllocation;
import dev.vortex.jni.NativeLoader;
import io.github.dfa1.vortex.parquet.ParquetExporter;
import org.apache.arrow.c.ArrowArray;
import org.apache.arrow.c.ArrowSchema;
import org.apache.arrow.c.Data;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.Float4Vector;
import org.apache.arrow.vector.VarBinaryVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.complex.FixedSizeListVector;
import org.apache.arrow.vector.complex.ListVector;
import org.apache.arrow.vector.complex.StructVector;
import org.apache.arrow.vector.types.FloatingPointPrecision;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// #721: a Rust-written file with the nested column shapes of the Raincloud datasets whose Parquet
/// export failed — `mnist`'s `struct<bytes: binary, path: string>`, `cohere-wikipedia-simple-embed`'s
/// fixed-size float embedding, `coig`'s `list<struct<question, answer>>` — exports to Parquet with
/// every row, null and empty list intact. The list of structs is decoded from a `vortex.struct`
/// encoding inside the list's segment, the path a Java-written test file need not take.
class NestedParquetExportInteropIntegrationTest {

    private static final Session SESSION = Session.create();
    private static final BufferAllocator ALLOCATOR = ArrowAllocation.rootAllocator();
    private static final int ROWS = 1000;
    private static final int DIM = 4;

    static {
        NativeLoader.loadJni();
    }

    @Test
    void javaExportsTheNestedColumnsJniWrote(@TempDir Path tmp) throws Exception {
        // Given
        Path vortex = tmp.resolve("jni.vortex");
        writeWithJni(vortex);
        Path parquet = tmp.resolve("out.parquet");

        // When
        ParquetExporter.exportParquet(vortex, parquet);

        // Then
        List<List<Object>> result = new ArrayList<>();
        try (ParquetFileReader reader = ParquetFileReader.open(InputFile.of(parquet));
             RowReader rows = reader.buildRowReader().build()) {
            while (rows.hasNext()) {
                rows.next();
                result.add(Arrays.asList(image(rows), embedding(rows), turns(rows)));
            }
        }
        List<List<Object>> expected = new ArrayList<>();
        for (int i = 0; i < ROWS; i++) {
            expected.add(Arrays.asList(expectedImage(i), expectedEmbedding(i), expectedTurns(i)));
        }
        assertThat(result).isEqualTo(expected);
    }

    // ── expected values: every fifth image and seventh embedding null, a third of the paths and a
    // quarter of the answers null, and row i holding i % 3 turns, so empty lists recur ──────────

    private static List<Object> expectedImage(int row) {
        if (row % 5 == 0) {
            return null;
        }
        return Arrays.asList(HexFormat.of().formatHex(imageBytes(row)), row % 3 == 0 ? null : "img/" + row + ".png");
    }

    private static byte[] imageBytes(int row) {
        return new byte[]{(byte) row, (byte) (row >> 8), 7};
    }

    private static List<Float> expectedEmbedding(int row) {
        if (row % 7 == 0) {
            return null;
        }
        List<Float> values = new ArrayList<>(DIM);
        for (int j = 0; j < DIM; j++) {
            values.add(row * 0.5f + j);
        }
        return values;
    }

    private static List<List<String>> expectedTurns(int row) {
        List<List<String>> turns = new ArrayList<>();
        for (int t = 0; t < row % 3; t++) {
            int element = 3 * row + t;
            turns.add(Arrays.asList("q" + element, element % 4 == 0 ? null : "a" + element));
        }
        return turns;
    }

    // ── read back from Parquet in the same shape ──────────────────────────────────────────────

    private static List<Object> image(RowReader rows) {
        if (rows.isNull("img")) {
            return null;
        }
        PqStruct img = rows.getStruct("img");
        return Arrays.asList(HexFormat.of().formatHex(img.getBinary("bytes")), img.getString("path"));
    }

    private static List<Float> embedding(RowReader rows) {
        if (rows.isNull("emb")) {
            return null;
        }
        return rows.getList("emb").floats();
    }

    private static List<List<String>> turns(RowReader rows) {
        PqList conv = rows.getList("conv");
        List<List<String>> turns = new ArrayList<>();
        for (PqStruct turn : conv.structs()) {
            turns.add(Arrays.asList(turn.getString("question"), turn.getString("answer")));
        }
        return turns;
    }

    // ── vortex-jni writer ─────────────────────────────────────────────────────────────────────

    private static void writeWithJni(Path path) throws Exception {
        Field img = new Field("img", FieldType.nullable(ArrowType.Struct.INSTANCE), List.of(
                Field.notNullable("bytes", ArrowType.Binary.INSTANCE),
                Field.nullable("path", ArrowType.Utf8.INSTANCE)));
        Field emb = new Field("emb", FieldType.nullable(new ArrowType.FixedSizeList(DIM)), List.of(
                Field.notNullable("item", new ArrowType.FloatingPoint(FloatingPointPrecision.SINGLE))));
        Field turn = new Field("item", FieldType.notNullable(ArrowType.Struct.INSTANCE), List.of(
                Field.notNullable("question", ArrowType.Utf8.INSTANCE),
                Field.nullable("answer", ArrowType.Utf8.INSTANCE)));
        Field conv = new Field("conv", FieldType.notNullable(ArrowType.List.INSTANCE), List.of(turn));
        Schema schema = new Schema(List.of(img, emb, conv));
        try (VortexWriter writer = VortexWriter.builder(SESSION, path.toAbsolutePath().toUri().toString(), schema,
                ALLOCATOR).build();
             VectorSchemaRoot root = VectorSchemaRoot.create(schema, ALLOCATOR)) {
            fillImages((StructVector) root.getVector("img"));
            fillEmbeddings((FixedSizeListVector) root.getVector("emb"));
            fillTurns((ListVector) root.getVector("conv"));
            root.setRowCount(ROWS);
            try (ArrowArray array = ArrowArray.allocateNew(ALLOCATOR);
                 ArrowSchema arrowSchema = ArrowSchema.allocateNew(ALLOCATOR)) {
                Data.exportVectorSchemaRoot(ALLOCATOR, root, null, array, arrowSchema);
                writer.writeBatch(array.memoryAddress(), arrowSchema.memoryAddress());
            }
        }
    }

    private static void fillImages(StructVector images) {
        images.allocateNew();
        VarBinaryVector bytes = (VarBinaryVector) images.getChild("bytes");
        VarCharVector paths = (VarCharVector) images.getChild("path");
        for (int i = 0; i < ROWS; i++) {
            List<Object> image = expectedImage(i);
            // a null struct's required field still holds a value in Arrow; only the struct is null
            bytes.setSafe(i, imageBytes(i));
            if (image == null) {
                images.setNull(i);
                paths.setNull(i);
                continue;
            }
            images.setIndexDefined(i);
            if (image.get(1) == null) {
                paths.setNull(i);
            } else {
                paths.setSafe(i, ((String) image.get(1)).getBytes(StandardCharsets.UTF_8));
            }
        }
        bytes.setValueCount(ROWS);
        paths.setValueCount(ROWS);
        images.setValueCount(ROWS);
    }

    private static void fillEmbeddings(FixedSizeListVector embeddings) {
        embeddings.allocateNew();
        Float4Vector items = (Float4Vector) embeddings.getDataVector();
        for (int i = 0; i < ROWS; i++) {
            List<Float> embedding = expectedEmbedding(i);
            for (int j = 0; j < DIM; j++) {
                items.setSafe(i * DIM + j, embedding == null ? 0f : embedding.get(j));
            }
            if (embedding == null) {
                embeddings.setNull(i);
            } else {
                embeddings.setNotNull(i);
            }
        }
        items.setValueCount(ROWS * DIM);
        embeddings.setValueCount(ROWS);
    }

    private static void fillTurns(ListVector conversations) {
        conversations.allocateNew();
        StructVector turns = (StructVector) conversations.getDataVector();
        VarCharVector questions = (VarCharVector) turns.getChild("question");
        VarCharVector answers = (VarCharVector) turns.getChild("answer");
        int element = 0;
        for (int i = 0; i < ROWS; i++) {
            conversations.startNewValue(i);
            List<List<String>> rowTurns = expectedTurns(i);
            for (List<String> rowTurn : rowTurns) {
                turns.setIndexDefined(element);
                questions.setSafe(element, rowTurn.get(0).getBytes(StandardCharsets.UTF_8));
                if (rowTurn.get(1) == null) {
                    answers.setNull(element);
                } else {
                    answers.setSafe(element, rowTurn.get(1).getBytes(StandardCharsets.UTF_8));
                }
                element++;
            }
            conversations.endValue(i, rowTurns.size());
        }
        questions.setValueCount(element);
        answers.setValueCount(element);
        turns.setValueCount(element);
        conversations.setValueCount(ROWS);
    }
}
