package io.github.dfa1.vortex.csv;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.array.LongArray;
import io.github.dfa1.vortex.core.array.VarBinArray;
import io.github.dfa1.vortex.io.VortexReader;
import io.github.dfa1.vortex.scan.ScanIterator;
import io.github.dfa1.vortex.scan.ScanOptions;
import io.github.dfa1.vortex.scan.ScanResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

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
            assertThat(schema.fieldNames()).containsExactly("id", "price", "active", "name");
            assertThat(schema.fieldTypes().get(0)).isEqualTo(new DType.Primitive(PType.I64, false));
            assertThat(schema.fieldTypes().get(1)).isEqualTo(new DType.Primitive(PType.F64, false));
            assertThat(schema.fieldTypes().get(2)).isEqualTo(new DType.Bool(false));
            assertThat(schema.fieldTypes().get(3)).isEqualTo(new DType.Utf8(false));

            try (ScanIterator iter = reader.scan(ScanOptions.all())) {
                assertThat(iter.hasNext()).isTrue();
                ScanResult chunk = iter.next();
                assertThat(chunk.rowCount()).isEqualTo(2);
                LongArray ids = chunk.column("id");
                assertThat(ids.getLong(0)).isEqualTo(1L);
                assertThat(ids.getLong(1)).isEqualTo(2L);
                VarBinArray names = chunk.column("name");
                assertThat(new String(names.getBytes(0), StandardCharsets.UTF_8)).isEqualTo("Alice");
                assertThat(new String(names.getBytes(1), StandardCharsets.UTF_8)).isEqualTo("Bob");
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
        CsvImporter.importCsv(csv, vortex, new ImportOptions(';', 65_536, true, null, null));

        // Then
        try (VortexReader reader = VortexReader.open(vortex)) {
            DType.Struct schema = (DType.Struct) reader.dtype();
            assertThat(schema.fieldNames()).containsExactly("id", "name");
        }
    }

    @Test
    void generatesHeadersWhenMissing(@TempDir Path tmp) throws Exception {
        // Given
        Path csv = tmp.resolve("data.csv");
        Files.writeString(csv, "1,Alice\n2,Bob\n");
        Path vortex = tmp.resolve("data.vortex");

        // When
        CsvImporter.importCsv(csv, vortex, new ImportOptions(',', 65_536, false, null, null));

        // Then
        try (VortexReader reader = VortexReader.open(vortex)) {
            DType.Struct schema = (DType.Struct) reader.dtype();
            assertThat(schema.fieldNames()).containsExactly("col0", "col1");
        }
    }

    @Test
    void respectsSchemaOverride(@TempDir Path tmp) throws Exception {
        // Given
        Path csv = tmp.resolve("data.csv");
        Files.writeString(csv, "value\n42\n99\n");
        Path vortex = tmp.resolve("data.vortex");
        DType.Struct forcedSchema = new DType.Struct(
                java.util.List.of("value"),
                java.util.List.of(new DType.Utf8(false)),
                false);

        // When
        CsvImporter.importCsv(csv, vortex, ImportOptions.defaults().withSchema(forcedSchema));

        // Then
        try (VortexReader reader = VortexReader.open(vortex)) {
            DType.Struct schema = (DType.Struct) reader.dtype();
            assertThat(schema.fieldTypes().get(0)).isEqualTo(new DType.Utf8(false));
            try (ScanIterator iter = reader.scan(ScanOptions.all())) {
                assertThat(iter.hasNext()).isTrue();
                ScanResult chunk = iter.next();
                VarBinArray values = chunk.column("value");
                assertThat(new String(values.getBytes(0), StandardCharsets.UTF_8)).isEqualTo("42");
            }
        }
    }
}
