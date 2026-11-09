package io.github.dfa1.vortex.integration;

import dev.vortex.api.Session;
import dev.vortex.api.VortexWriter;
import dev.vortex.arrow.ArrowAllocation;
import dev.vortex.jni.NativeLoader;
import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.ScanOptions;
import io.github.dfa1.vortex.reader.VortexReader;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.ListArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.StructArray;
import io.github.dfa1.vortex.reader.array.VarBinArray;
import org.apache.arrow.c.ArrowArray;
import org.apache.arrow.c.ArrowSchema;
import org.apache.arrow.c.Data;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.complex.ListVector;
import org.apache.arrow.vector.complex.StructVector;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// A nullable text field of a struct keeps its validity in its own encoding (FSST on its codes,
/// VarBinView, OnPair), not under `vortex.masked`, in a Rust-written file. Inside one segment, as
/// the elements of a list are, Java decoded every struct field with its nullability stripped, and
/// those decoders read their validity child only for a nullable dtype, so the field's null rows
/// came back as empty strings. (A top-level struct column is a struct layout, whose fields are
/// decoded each with its own dtype.)
class StructFieldValidityInteropIntegrationTest {

    private static final Session SESSION = Session.create();
    private static final BufferAllocator ALLOCATOR = ArrowAllocation.rootAllocator();
    private static final ColumnName V = ColumnName.of("v");
    private static final int ROWS = 8192;

    static {
        NativeLoader.loadJni();
    }

    /// Each shape steers Rust's cascade to a different text encoding for the field.
    static Stream<Arguments> fields() {
        return Stream.of(
                Arguments.of("unique (fsst)", (IntFunction<String>) i -> "some text value number " + i * 7919L),
                Arguments.of("short (varbinview)", (IntFunction<String>) i -> Integer.toString(i % 97)),
                Arguments.of("long repeated (onpair)", (IntFunction<String>) i -> "https://example.com/path/" + i % 3000 + "/index.html"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fields")
    void javaReadsTheNullsOfANullableTextField(String name, IntFunction<String> values, @TempDir Path tmp)
            throws Exception {
        // Given — rows of two structs each, the field null in every third struct
        Path file = tmp.resolve("jni.vortex");
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 2 * ROWS; i++) {
            expected.add(i % 3 == 0 ? null : values.apply(i));
        }
        writeWithJni(file, expected);

        // When
        List<String> result = readWithJava(file);

        // Then
        assertThat(result).isEqualTo(expected);
    }

    /// A `list<struct{s: utf8?}>` column of two elements per row: `elements` in order.
    private static void writeWithJni(Path path, List<String> elements) throws Exception {
        Field struct = new Field("item", FieldType.notNullable(ArrowType.Struct.INSTANCE),
                List.of(Field.nullable("s", ArrowType.Utf8.INSTANCE)));
        Schema schema = new Schema(List.of(new Field("v", FieldType.notNullable(ArrowType.List.INSTANCE),
                List.of(struct))));
        try (VortexWriter writer = VortexWriter.builder(SESSION, path.toAbsolutePath().toUri().toString(), schema,
                ALLOCATOR).build();
             VectorSchemaRoot root = VectorSchemaRoot.create(schema, ALLOCATOR)) {
            ListVector list = (ListVector) root.getVector("v");
            list.allocateNew();
            StructVector items = (StructVector) list.getDataVector();
            VarCharVector s = (VarCharVector) items.getChild("s");
            for (int i = 0; i < elements.size(); i++) {
                items.setIndexDefined(i);
                if (elements.get(i) == null) {
                    s.setNull(i);
                } else {
                    s.setSafe(i, elements.get(i).getBytes(StandardCharsets.UTF_8));
                }
            }
            for (int row = 0; row < elements.size() / 2; row++) {
                list.startNewValue(row);
                list.endValue(row, 2);
            }
            s.setValueCount(elements.size());
            items.setValueCount(elements.size());
            list.setValueCount(elements.size() / 2);
            root.setRowCount(elements.size() / 2);
            try (ArrowArray array = ArrowArray.allocateNew(ALLOCATOR);
                 ArrowSchema arrowSchema = ArrowSchema.allocateNew(ALLOCATOR)) {
                Data.exportVectorSchemaRoot(ALLOCATOR, root, null, array, arrowSchema);
                writer.writeBatch(array.memoryAddress(), arrowSchema.memoryAddress());
            }
        }
    }

    private static List<String> readWithJava(Path file) throws IOException {
        List<String> out = new ArrayList<>();
        try (var reader = VortexReader.open(file, ReadRegistry.loadAll());
             var scan = reader.scan(ScanOptions.all())) {
            while (scan.hasNext()) {
                try (var chunk = scan.next()) {
                    ListArray list = chunk.column(V);
                    // a struct of one field decodes straight to that field
                    Array elements = list.elements();
                    if (elements instanceof StructArray struct) {
                        elements = struct.field(0);
                    }
                    MaskedArray.Unwrapped field = MaskedArray.unwrap(elements);
                    VarBinArray strings = (VarBinArray) field.inner();
                    for (long i = 0; i < strings.length(); i++) {
                        boolean valid = field.validity() == null || field.validity().getBoolean(i);
                        out.add(valid ? strings.getString(i) : null);
                    }
                }
            }
        }
        return out;
    }
}
