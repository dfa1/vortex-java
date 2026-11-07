package io.github.dfa1.vortex.reader;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.testing.JfrRecorderBase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VortexReaderJfrTest extends JfrRecorderBase {

    @Test
    void open_recordsFileOpenedAndFileMapping(@TempDir Path tmp) throws IOException {
        // Given
        Path file = fixture(tmp);
        long size = Files.size(file);

        // When
        int version;
        int segments;
        try (var sut = VortexReader.open(file)) {
            version = sut.version();
            segments = sut.footer().segmentSpecs().size();
        }

        // Then
        var opened = events("FileOpened");
        assertThat(opened).hasSize(1);
        assertThat(opened.getFirst().getString("source")).isEqualTo(file.toString());
        assertThat(opened.getFirst().getLong("fileSize")).isEqualTo(size);
        assertThat(opened.getFirst().getInt("formatVersion")).isEqualTo(version);
        assertThat(opened.getFirst().getInt("segmentCount")).isEqualTo(segments);
        var mapping = events("FileMapping");
        assertThat(mapping).hasSize(1);
        assertThat(mapping.getFirst().getString("file")).isEqualTo(file.toString());
        assertThat(mapping.getFirst().getLong("size")).isEqualTo(size);
    }

    @Test
    void open_tooSmallFile_recordsNoFileOpened(@TempDir Path tmp) throws IOException {
        // Given
        Path file = Files.write(tmp.resolve("tiny.vortex"), new byte[]{1, 2, 3});

        // When / Then
        assertThatThrownBy(() -> VortexReader.open(file)).isInstanceOf(VortexException.class);
        assertThat(events("FileOpened")).isEmpty();
    }

    @Test
    void scan_recordsSegmentDecoded(@TempDir Path tmp) throws IOException {
        // Given
        Path file = fixture(tmp);

        // When
        try (var reader = VortexReader.open(file);
             var scan = reader.scan(ScanOptions.all())) {
            scan.forEachRemaining(Chunk::close);
        }

        // Then
        var decoded = events("SegmentDecoded");
        assertThat(decoded).isNotEmpty();
        assertThat(decoded).allSatisfy(e -> {
            assertThat(e.getString("encoding")).startsWith("vortex.");
            assertThat(e.getLong("rowCount")).isPositive();
            assertThat(e.getLong("segmentSize")).isPositive();
        });
    }

    private static Path fixture(Path tmp) throws IOException {
        try (InputStream in = VortexReaderJfrTest.class.getResourceAsStream("/fixtures/primitives.vortex")) {
            Path file = tmp.resolve("primitives.vortex");
            Files.copy(in, file);
            return file;
        }
    }
}
