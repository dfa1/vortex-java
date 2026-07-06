package io.github.dfa1.vortex.csv;

import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.writer.VortexWriter;
import io.github.dfa1.vortex.writer.WriteOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.StringWriter;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CsvExporterTest {

    @Test
    void exportsToCsvFile(@TempDir Path tmp) throws Exception {
        // Given
        Path vortex = tmp.resolve("data.vortex");
        DType.Struct schema = new DType.Struct(
                List.of(ColumnName.of("id"), ColumnName.of("name")),
                List.of(DType.I64, DType.UTF8),
                false);
        try (FileChannel ch = FileChannel.open(vortex, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             VortexWriter writer = VortexWriter.create(ch, schema, WriteOptions.defaults())) {
            writer.writeChunk(Map.of(ColumnName.of("id"), new long[]{1L, 2L}, ColumnName.of("name"), new String[]{"Alice", "Bob"}));
        }
        Path csv = tmp.resolve("out.csv");

        // When
        CsvExporter.exportCsv(vortex, csv);

        // Then
        List<String> lines = Files.readAllLines(csv);
        assertThat(lines).hasSize(3);
        assertThat(lines.get(0)).isEqualTo("id,name");
        assertThat(lines.get(1)).isEqualTo("1,Alice");
        assertThat(lines.get(2)).isEqualTo("2,Bob");
    }

    @Test
    void exportsToWriter(@TempDir Path tmp) throws Exception {
        // Given
        Path vortex = tmp.resolve("data.vortex");
        DType.Struct schema = new DType.Struct(
                List.of(ColumnName.of("x")),
                List.of(DType.F64),
                false);
        try (FileChannel ch = FileChannel.open(vortex, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             VortexWriter writer = VortexWriter.create(ch, schema, WriteOptions.defaults())) {
            writer.writeChunk(Map.of(ColumnName.of("x"), new double[]{1.5, 2.7}));
        }
        StringWriter out = new StringWriter();

        // When
        CsvExporter.exportCsv(vortex, out, ExportOptions.defaults());

        // Then
        String[] lines = out.toString().split("\r?\n");
        assertThat(lines).hasSize(3);
        assertThat(lines[0]).isEqualTo("x");
        assertThat(lines[1]).isEqualTo("1.5");
        assertThat(lines[2]).isEqualTo("2.7");
    }

    @Test
    void suppressesHeaderWhenConfigured(@TempDir Path tmp) throws Exception {
        // Given
        Path vortex = tmp.resolve("data.vortex");
        DType.Struct schema = new DType.Struct(
                List.of(ColumnName.of("id")),
                List.of(DType.I64),
                false);
        try (FileChannel ch = FileChannel.open(vortex, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             VortexWriter writer = VortexWriter.create(ch, schema, WriteOptions.defaults())) {
            writer.writeChunk(Map.of(ColumnName.of("id"), new long[]{7L}));
        }
        Path csv = tmp.resolve("out.csv");

        // When
        CsvExporter.exportCsv(vortex, csv, new ExportOptions(',', false, java.util.List.of(), null));

        // Then
        List<String> lines = Files.readAllLines(csv);
        assertThat(lines).hasSize(1);
        assertThat(lines.getFirst()).isEqualTo("7");
    }
}
