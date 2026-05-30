package io.github.dfa1.vortex.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CliIT {

    @Test
    void importExportRoundTrip(@TempDir Path tmp) throws Exception {
        // Given
        Path csvIn = tmp.resolve("data.csv");
        Files.writeString(csvIn, "id,name\n1,Alice\n2,Bob\n3,Charlie\n");

        // When — import CSV to Vortex
        int importExit = ImportCommand.run(new String[]{"import", csvIn.toString()});
        assertThat(importExit).isZero();

        // When — export Vortex back to CSV via stdout
        Path vortexPath = tmp.resolve("data.vortex");
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream saved = System.out;
        System.setOut(new PrintStream(baos, true, StandardCharsets.UTF_8));
        int exportExit;
        try {
            exportExit = ExportCommand.run(new String[]{"export", vortexPath.toString()});
        } finally {
            System.setOut(saved);
        }

        // Then
        assertThat(exportExit).isZero();
        String[] lines = baos.toString(StandardCharsets.UTF_8).split("\r?\n");
        assertThat(lines).hasSize(4);
        assertThat(lines[0]).isEqualTo("id,name");
        assertThat(lines[1]).isEqualTo("1,Alice");
        assertThat(lines[2]).isEqualTo("2,Bob");
        assertThat(lines[3]).isEqualTo("3,Charlie");
    }

    @Test
    void inspectPrintsSchema(@TempDir Path tmp) throws Exception {
        // Given — create a vortex file via import
        Path csvIn = tmp.resolve("data.csv");
        Files.writeString(csvIn, "id,score\n1,9.5\n");
        ImportCommand.run(new String[]{"import", csvIn.toString()});
        Path vortexPath = tmp.resolve("data.vortex");

        // When
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream saved = System.out;
        System.setOut(new PrintStream(baos, true, StandardCharsets.UTF_8));
        int exit;
        try {
            exit = InspectCommand.run(new String[]{"inspect", vortexPath.toString()});
        } finally {
            System.setOut(saved);
        }

        // Then
        assertThat(exit).isZero();
        String output = baos.toString(StandardCharsets.UTF_8);
        assertThat(output).contains("Schema:");
        assertThat(output).contains("id");
        assertThat(output).contains("score");
    }

    @Test
    void schemaPrintsMachineReadableDType(@TempDir Path tmp) throws Exception {
        // Given — create a vortex file via import
        Path csvIn = tmp.resolve("data.csv");
        Files.writeString(csvIn, "id,name\n1,Alice\n");
        ImportCommand.run(new String[]{"import", csvIn.toString()});
        Path vortexPath = tmp.resolve("data.vortex");

        // When
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream saved = System.out;
        System.setOut(new PrintStream(baos, true, StandardCharsets.UTF_8));
        int exit;
        try {
            exit = SchemaCommand.run(new String[]{"schema", vortexPath.toString()});
        } finally {
            System.setOut(saved);
        }

        // Then
        assertThat(exit).isZero();
        assertThat(baos.toString(StandardCharsets.UTF_8).strip())
                .isEqualTo("struct<id: I64, name: utf8>");
    }
}
