package io.github.dfa1.vortex.io;

import io.github.dfa1.vortex.core.Array;
import io.github.dfa1.vortex.encoding.DecodeContext;
import io.github.dfa1.vortex.encoding.Codec;
import io.github.dfa1.vortex.encoding.CodecRegistry;
import io.github.dfa1.vortex.encoding.EncodingId;
import io.github.dfa1.vortex.scan.ScanOptions;
import io.github.dfa1.vortex.scan.ScanResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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

    // --- scan ---

    @ParameterizedTest
    @ValueSource(strings = {
        "primitives.vortex",
        "booleans.vortex",
        "null.vortex",
        "varbin.vortex",
        "chunked.vortex"
    })
    void scan_withNoDecoders_reachesDecodeStep(String name) throws URISyntaxException, IOException {
        // Given
        Path path = fixtureFile(name);

        // When / Then — layout traversal succeeds; decode fails only on missing decoder
        try (var sut = VortexFile.open(path, CodecRegistry.empty());
             var iter = sut.scan(ScanOptions.all())) {
            assertThatThrownBy(iter::hasNext)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("no codec for encoding:");
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
    void scan_withStubDecoder_producesAtLeastOneChunk(String name) throws URISyntaxException, IOException {
        // Given
        Path path = fixtureFile(name);
        var registry = buildUniversalStubRegistry();

        // When
        try (var sut = VortexFile.open(path, registry);
             var iter = sut.scan(ScanOptions.all())) {

            // Then
            assertThat(iter.hasNext()).isTrue();
            ScanResult chunk = iter.next();
            assertThat(chunk.rowCount()).isGreaterThan(0);
            assertThat(chunk.columns()).isNotEmpty();
        }
    }

    @Test
    void scan_chunkedFixture_producesExactlyOneLayoutChunk() throws URISyntaxException, IOException {
        // Given — chunked.vortex uses a ChunkedArray *encoding* inside one flat layout node
        Path path = fixtureFile("chunked.vortex");
        var registry = buildUniversalStubRegistry();
        int chunkCount = 0;

        // When
        try (var sut = VortexFile.open(path, registry);
             var iter = sut.scan(ScanOptions.all())) {
            while (iter.hasNext()) {
                iter.next();
                chunkCount++;
            }
        }

        // Then
        assertThat(chunkCount).isEqualTo(1);
    }

    private static CodecRegistry buildUniversalStubRegistry() {
        var registry = CodecRegistry.empty();
        Codec stub = new Codec() {
            @Override public EncodingId encodingId() { return EncodingId.VORTEX_PRIMITIVE; }
            @Override public Array decode(DecodeContext ctx) { return Array.empty(ctx.dtype()); }
        };
        for (String id : List.of(
                "vortex.flat", "vortex.bool", "vortex.null",
                "vortex.varbin", "vortex.struct", "vortex.chunked",
                "vortex.bitpacked", "vortex.constant", "vortex.sparse",
                "fastlanes.bitpacked", "fastlanes.delta", "fastlanes.for",
                "alp", "alp_rd", "dict", "zigzag", "roaring.bool", "roaring.int",
                "byte_bool")) {
            registry.register(id, stub);
        }
        return registry;
    }

    // --- helpers ---

    private Path fixtureFile(String name) throws URISyntaxException {
        var url = getClass().getResource("/fixtures/" + name);
        assertThat(url).as("fixture not found: " + name).isNotNull();
        return Path.of(url.toURI());
    }
}
