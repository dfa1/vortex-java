package io.github.dfa1.vortex.csv;

import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.array.VarBinArray;
import io.github.dfa1.vortex.reader.VortexReader;
import io.github.dfa1.vortex.reader.Chunk;
import io.github.dfa1.vortex.reader.ScanIterator;
import io.github.dfa1.vortex.reader.ScanOptions;
import io.github.dfa1.vortex.writer.WriteOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ForkJoinPool;

import static org.assertj.core.api.Assertions.assertThat;

class CsvImporterTest {

    @Test
    void infersTypedColumnsAndRoundTrips(@TempDir Path tmp) throws Exception {
        // Given
        Path csv = tmp.resolve("data.csv");
        Files.writeString(csv, "id,price,active,name\n1,1.5,true,Alice\n2,2.7,false,Bob\n");
        Path vortex = tmp.resolve("data.vortex");

        // When
        CsvImporter.importCsv(csv, vortex);

        // Then
        try (VortexReader reader = VortexReader.open(vortex)) {
            assertThat(reader.dtype()).isInstanceOf(DType.Struct.class);
            DType.Struct schema = (DType.Struct) reader.dtype();
            assertThat(schema.fieldNames().stream().map(ColumnName::value).toList()).containsExactly("id", "price", "active", "name");
            assertThat(schema.fieldTypes().get(0)).isEqualTo(DType.I64);
            assertThat(schema.fieldTypes().get(1)).isEqualTo(DType.F64);
            assertThat(schema.fieldTypes().get(2)).isEqualTo(DType.BOOL);
            assertThat(schema.fieldTypes().get(3)).isEqualTo(DType.UTF8);

            try (ScanIterator iter = reader.scan(ScanOptions.all())) {
                assertThat(iter.hasNext()).isTrue();
                try (Chunk chunk = iter.next()) {
                    assertThat(chunk.rowCount()).isEqualTo(2);
                    LongArray ids = chunk.column(ColumnName.of("id"));
                    assertThat(ids.getLong(0)).isEqualTo(1L);
                    assertThat(ids.getLong(1)).isEqualTo(2L);
                    VarBinArray names = chunk.column(ColumnName.of("name"));
                    assertThat(names.getString(0)).isEqualTo("Alice");
                    assertThat(names.getString(1)).isEqualTo("Bob");
                }
            }
        }
    }

    @Test
    void usesCustomDelimiter(@TempDir Path tmp) throws Exception {
        // Given
        Path csv = tmp.resolve("data.csv");
        Files.writeString(csv, "id;name\n1;Alice\n2;Bob\n");
        Path vortex = tmp.resolve("data.vortex");

        // When
        CsvImporter.importCsv(csv, vortex, new ImportOptions(';', 65_536, true, null, null, WriteOptions.defaults()));

        // Then
        try (VortexReader reader = VortexReader.open(vortex)) {
            DType.Struct schema = (DType.Struct) reader.dtype();
            assertThat(schema.fieldNames().stream().map(ColumnName::value).toList()).containsExactly("id", "name");
        }
    }

    @Test
    void generatesHeadersWhenMissing(@TempDir Path tmp) throws Exception {
        // Given
        Path csv = tmp.resolve("data.csv");
        Files.writeString(csv, "1,Alice\n2,Bob\n");
        Path vortex = tmp.resolve("data.vortex");

        // When
        CsvImporter.importCsv(csv, vortex, new ImportOptions(',', 65_536, false, null, null, WriteOptions.defaults()));

        // Then
        try (VortexReader reader = VortexReader.open(vortex)) {
            DType.Struct schema = (DType.Struct) reader.dtype();
            assertThat(schema.fieldNames().stream().map(ColumnName::value).toList()).containsExactly("col0", "col1");
        }
    }

    @Test
    void respectsSchemaOverride(@TempDir Path tmp) throws Exception {
        // Given
        Path csv = tmp.resolve("data.csv");
        Files.writeString(csv, "value\n42\n99\n");
        Path vortex = tmp.resolve("data.vortex");
        DType.Struct forcedSchema = new DType.Struct(
                java.util.List.of(ColumnName.of("value")),
                java.util.List.of(DType.UTF8),
                false);

        // When
        CsvImporter.importCsv(csv, vortex, ImportOptions.defaults().withSchema(forcedSchema));

        // Then
        try (VortexReader reader = VortexReader.open(vortex)) {
            DType.Struct schema = (DType.Struct) reader.dtype();
            assertThat(schema.fieldTypes().getFirst()).isEqualTo(DType.UTF8);
            try (ScanIterator iter = reader.scan(ScanOptions.all())) {
                assertThat(iter.hasNext()).isTrue();
                try (Chunk chunk = iter.next()) {
                    VarBinArray values = chunk.column(ColumnName.of("value"));
                    assertThat(values.getString(0)).isEqualTo("42");
                }
            }
        }
    }

    @Test
    void buildChunkOnAnExecutorKeepsTheColumnsAndTheirOrder() {
        // Given: a column of each kind, so every conversion path runs off the caller thread
        DType.Struct schema = DType.structBuilder()
                .field(ColumnName.of("id"), DType.I64)
                .field(ColumnName.of("price"), DType.F64)
                .field(ColumnName.of("name"), DType.UTF8)
                .build();
        List<String[]> rows = List.of(new String[]{"1", "1.5", "a"}, new String[]{"2", "2.5", "b"});

        // When
        var result = CsvImporter.buildChunk(schema, rows, ForkJoinPool.commonPool());

        // Then
        assertThat(result.keySet()).extracting(ColumnName::value).containsExactly("id", "price", "name");
        assertThat((long[]) result.get(ColumnName.of("id"))).containsExactly(1L, 2L);
        assertThat((double[]) result.get(ColumnName.of("price"))).containsExactly(1.5, 2.5);
        assertThat((String[]) result.get(ColumnName.of("name"))).containsExactly("a", "b");
    }
}
