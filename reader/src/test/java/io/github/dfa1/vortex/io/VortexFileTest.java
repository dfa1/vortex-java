package io.github.dfa1.vortex.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VortexFileTest {

    // --- trailer / magic validation ---

    @Test
    void open_fileTooSmall_throwsIOException(@TempDir Path tmpDir) throws IOException {
        // Given
        Path sut = Files.write(tmpDir.resolve("tiny.vortex"), new byte[4]);

        // When / Then
        assertThatThrownBy(() -> VortexFile.open(sut))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("file too small");
    }

    @Test
    void open_wrongMagic_throwsIOException(@TempDir Path tmpDir) throws IOException {
        // Given
        byte[] bytes = new byte[VortexFile.TRAILER_SIZE]; // exactly 8 bytes
        bytes[4] = 'X'; bytes[5] = 'X'; bytes[6] = 'X'; bytes[7] = 'X';
        Path sut = Files.write(tmpDir.resolve("bad_magic.vortex"), bytes);

        // When / Then
        assertThatThrownBy(() -> VortexFile.open(sut))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("invalid magic bytes");
    }

    // --- real fixtures: full parse ---

    @ParameterizedTest
    @ValueSource(strings = {
        "primitives.vortex",
        "booleans.vortex",
        "null.vortex",
        "varbin.vortex",
        "chunked.vortex"
    })
    void open_fixture_parsesSuccessfully(String name) throws URISyntaxException, IOException {
        // Given
        Path path = fixtureFile(name);

        // When
        try (var sut = VortexFile.open(path)) {

            // Then
            assertThat(sut.version()).isEqualTo(1);
            assertThat(sut.fileSize()).isGreaterThan(VortexFile.TRAILER_SIZE);
            assertThat(sut.layout()).isNotNull();
            assertThat(sut.footer()).isNotNull();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "primitives.vortex",
        "booleans.vortex",
        "null.vortex",
        "varbin.vortex",
        "chunked.vortex"
    })
    void fixture_hasMagicBytesAtEnd(String name) throws IOException, URISyntaxException {
        // Given
        byte[] bytes = Files.readAllBytes(fixtureFile(name));

        // When
        byte[] trailerMagic = new byte[]{
            bytes[bytes.length - 4],
            bytes[bytes.length - 3],
            bytes[bytes.length - 2],
            bytes[bytes.length - 1]
        };

        // Then
        assertThat(trailerMagic).isEqualTo(VortexFile.MAGIC);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "primitives.vortex",
        "booleans.vortex",
        "null.vortex",
        "varbin.vortex",
        "chunked.vortex"
    })
    void fixture_trailerHasExpectedVersion(String name) throws IOException, URISyntaxException {
        // Given
        byte[] bytes = Files.readAllBytes(fixtureFile(name));
        int trailerStart = bytes.length - VortexFile.TRAILER_SIZE;

        // When
        int version = java.nio.ByteBuffer.wrap(bytes, trailerStart, 2)
                .order(ByteOrder.LITTLE_ENDIAN)
                .getShort() & 0xFFFF;

        // Then
        assertThat(version).isEqualTo(1);
    }

    // --- helpers ---

    private Path fixtureFile(String name) throws URISyntaxException {
        var url = getClass().getResource("/fixtures/" + name);
        assertThat(url).as("fixture not found: " + name).isNotNull();
        return Path.of(url.toURI());
    }
}
