package io.github.dfa1.vortex.reader;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.io.VortexFormat;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.UnknownArray;
import io.github.dfa1.vortex.reader.decode.DecodeContext;
import io.github.dfa1.vortex.reader.decode.EncodingDecoder;
import io.github.dfa1.vortex.core.model.EncodingId;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.net.URISyntaxException;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VortexReaderTest {

    // --- trailer / magic validation ---

    private static ReadRegistry buildUniversalStubRegistry() {
        var b = ReadRegistry.builder();
        for (EncodingId encodingId : EncodingId.WellKnown.values()) {
            b.register(new EncodingDecoder() {
                @Override
                public EncodingId encodingId() {
                    return encodingId;
                }

                @Override
                public Array decode(DecodeContext ctx) {
                    // Generic zero-length stand-in: scan chunk row count comes from the
                    // layout, so the leaf array's contents are irrelevant to this test.
                    return new UnknownArray("stub", ctx.dtype(), 0, null,
                            new MemorySegment[0], new Array[0]);
                }
            });
        }
        return b.build();
    }

    @SuppressWarnings("resource")
    @Test
    void open_fileTooSmall_throwsVortexException(@TempDir Path tmpDir) throws IOException {
        // Given
        Path sut = Files.write(tmpDir.resolve("tiny.vortex"), new byte[4]);

        // When / Then
        assertThatThrownBy(() -> VortexReader.open(sut))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("file too small");
    }

    // --- real fixtures: full parse ---

    @SuppressWarnings("resource")
    @Test
    void open_wrongMagic_throwsVortexException(@TempDir Path tmpDir) throws IOException {
        // Given
        byte[] bytes = new byte[VortexFormat.TRAILER_SIZE]; // exactly 8 bytes
        bytes[4] = 'X';
        bytes[5] = 'X';
        bytes[6] = 'X';
        bytes[7] = 'X';
        Path sut = Files.write(tmpDir.resolve("bad_magic.vortex"), bytes);

        // When / Then
        assertThatThrownBy(() -> VortexReader.open(sut))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("invalid magic bytes");
    }

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
        try (var sut = VortexReader.open(path)) {

            // Then
            assertThat(sut.version()).isEqualTo(1);
            assertThat(sut.fileSize()).isGreaterThan(VortexFormat.TRAILER_SIZE);
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
        assertThat(trailerMagic).isEqualTo(VortexFormat.MAGIC.toArray(java.lang.foreign.ValueLayout.JAVA_BYTE));
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
    void fixture_trailerHasExpectedVersion(String name) throws IOException, URISyntaxException {
        // Given
        byte[] bytes = Files.readAllBytes(fixtureFile(name));
        int trailerStart = bytes.length - VortexFormat.TRAILER_SIZE;

        // When
        int version = java.nio.ByteBuffer.wrap(bytes, trailerStart, 2)
                              .order(ByteOrder.LITTLE_ENDIAN)
                              .getShort() & 0xFFFF;

        // Then
        assertThat(version).isEqualTo(1);
    }

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
        try (var sut = VortexReader.open(path, ReadRegistry.empty());
             var iter = sut.scan(ScanOptions.all())) {
            // Decode now happens in next(), not hasNext() — hasNext() is side-effect-free.
            assertThat(iter.hasNext()).isTrue();
            assertThatThrownBy(iter::next)
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("no decoder registered");
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
    void scan_withNoDecoders_allowUnknown_returnsUnknownArray(String name) throws URISyntaxException, IOException {
        // Given — empty registry + allowUnknown: every leaf decodes to a passthrough UnknownArray
        Path path = fixtureFile(name);
        var registry = ReadRegistry.builder().allowUnknown().build();

        // When
        try (var sut = VortexReader.open(path, registry);
             var iter = sut.scan(ScanOptions.all())) {

            // Then
            assertThat(iter.hasNext()).isTrue();
            try (Chunk chunk = iter.next()) {
                assertThat(chunk.rowCount()).isGreaterThan(0);
                assertThat(chunk.columns()).isNotEmpty();
                for (Array column : chunk.columns().values()) {
                    assertThat(column).isInstanceOf(UnknownArray.class);
                    UnknownArray foreign = (UnknownArray) column;
                    assertThat(foreign.encodingId()).describedAs(name).startsWith("vortex.");
                }
            }
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
        try (var sut = VortexReader.open(path, registry);
             var iter = sut.scan(ScanOptions.all())) {

            // Then
            assertThat(iter.hasNext()).isTrue();
            try (Chunk chunk = iter.next()) {
                assertThat(chunk.rowCount()).isGreaterThan(0);
                assertThat(chunk.columns()).isNotEmpty();
            }
        }
    }

    @Test
    void scan_chunkedFixture_producesExactlyOneLayoutChunk() throws URISyntaxException, IOException {
        // Given — chunked.vortex uses a ChunkedArray *encoding* inside one flat layout node
        Path path = fixtureFile("chunked.vortex");
        var registry = buildUniversalStubRegistry();
        int chunkCount = 0;

        // When
        try (var sut = VortexReader.open(path, registry);
             var iter = sut.scan(ScanOptions.all())) {
            while (iter.hasNext()) {
                try (Chunk c = iter.next()) {
                    chunkCount++;
                    assertThat(c.rowCount()).isGreaterThan(0);
                }
            }
        }

        // Then
        assertThat(chunkCount).isEqualTo(1);
    }

    @Test
    void scan_withLimit_returnsExactlyNRows() throws URISyntaxException, IOException {
        // Given — primitives.vortex has 3 rows; limit=2 forces truncation of the single chunk
        Path path = fixtureFile("primitives.vortex");
        long limit = 2;

        // When
        long totalRows = 0;
        try (var sut = VortexReader.open(path);
             var iter = sut.scan(ScanOptions.limit(limit))) {
            while (iter.hasNext()) {
                try (Chunk chunk = iter.next()) {
                    totalRows += chunk.rowCount();
                    for (Array col : chunk.columns().values()) {
                        assertThat(col.length()).isLessThanOrEqualTo(limit);
                    }
                }
            }
        }

        // Then
        assertThat(totalRows).isEqualTo(limit);
    }

    @Test
    void scan_withLimitExceedingTotal_returnsAllRows() throws URISyntaxException, IOException {
        // Given
        Path path = fixtureFile("primitives.vortex");
        long totalWithoutLimit;
        try (var sut = VortexReader.open(path);
             var iter = sut.scan(ScanOptions.all())) {
            totalWithoutLimit = 0;
            while (iter.hasNext()) {
                try (Chunk c = iter.next()) {
                    totalWithoutLimit += c.rowCount();
                }
            }
        }

        // When
        long totalWithLimit = 0;
        try (var sut = VortexReader.open(path);
             var iter = sut.scan(ScanOptions.limit(Long.MAX_VALUE))) {
            while (iter.hasNext()) {
                try (Chunk c = iter.next()) {
                    totalWithLimit += c.rowCount();
                }
            }
        }

        // Then
        assertThat(totalWithLimit).isEqualTo(totalWithoutLimit);
    }

    @Test
    void scan_withLimitZero_returnsNoRows() throws URISyntaxException, IOException {
        // Given
        Path path = fixtureFile("primitives.vortex");

        // When
        long totalRows = 0;
        try (var sut = VortexReader.open(path);
             var iter = sut.scan(ScanOptions.limit(0))) {
            while (iter.hasNext()) {
                try (Chunk c = iter.next()) {
                    totalRows += c.rowCount();
                }
            }
        }

        // Then
        assertThat(totalRows).isZero();
    }

    // --- helpers ---

    private Path fixtureFile(String name) throws URISyntaxException {
        var url = getClass().getResource("/fixtures/" + name);
        assertThat(url).as("fixture not found: " + name).isNotNull();
        return Path.of(url.toURI());
    }
}
