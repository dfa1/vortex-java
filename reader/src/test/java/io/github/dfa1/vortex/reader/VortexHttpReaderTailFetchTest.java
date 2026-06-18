package io.github.dfa1.vortex.reader;

import io.github.dfa1.vortex.core.VortexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.net.ssl.SSLSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;

/// Verifies the happy-path tail fetch: both a `206 Partial Content` (with Content-Range)
/// and a `200 OK` full-file fallback drive [VortexHttpReader] through `TailFetch` and
/// successful metadata parsing. Serves a committed fixture from memory via a mocked
/// [HttpClient] so the test stays deterministic and network-free.
@ExtendWith(MockitoExtension.class)
class VortexHttpReaderTailFetchTest {

    @Mock
    private HttpClient client;

    private static final URI URI = java.net.URI.create("http://example.com/primitives.vortex");

    @Test
    void open_partialContent_parsesMetadata() throws Exception {
        // Given — fixture fits in the 65 KB tail window, so the suffix Range returns
        // the whole file as 206 with Content-Range bytes 0-(n-1)/n
        byte[] file = fixtureBytes();
        doReturn(response(206, "bytes 0-" + (file.length - 1) + "/" + file.length, file))
                .when(client).send(any(), any());

        // When
        try (var sut = VortexHttpReader.open(URI, ReadRegistry.empty(), client)) {

            // Then
            assertThat(sut.fileSize()).isEqualTo(file.length);
            assertThat(sut.fileSize()).isGreaterThan(VortexFormat.TRAILER_SIZE);
            assertThat(sut.layout()).isNotNull();
            assertThat(sut.footer()).isNotNull();
            assertThat(sut.dtype()).isNotNull();
        }
    }

    @Test
    void open_fullFile_whenServerIgnoresRange() throws Exception {
        // Given — server lacks Range support and replies 200 with the entire file;
        // TailFetch must then treat start=0 and fileSize=body.length
        byte[] file = fixtureBytes();
        doReturn(response(200, null, file)).when(client).send(any(), any());

        // When
        try (var sut = VortexHttpReader.open(URI, ReadRegistry.empty(), client)) {

            // Then
            assertThat(sut.fileSize()).isEqualTo(file.length);
            assertThat(sut.layout()).isNotNull();
            assertThat(sut.footer()).isNotNull();
            assertThat(sut.dtype()).isNotNull();
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static byte[] fixtureBytes() throws IOException {
        try (InputStream in = VortexHttpReaderTailFetchTest.class
                .getResourceAsStream("/fixtures/primitives.vortex")) {
            if (in == null) {
                throw new IOException("missing test fixture: /fixtures/primitives.vortex");
            }
            return in.readAllBytes();
        }
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<byte[]> response(int status, String contentRange, byte[] body) {
        return new HttpResponse<>() {
            @Override
            public int statusCode() {
                return status;
            }

            @Override
            public byte[] body() {
                return body;
            }

            @Override
            public HttpHeaders headers() {
                Map<String, List<String>> map = contentRange == null
                        ? Map.of()
                        : Map.of("content-range", List.of(contentRange));
                return HttpHeaders.of(map, (k, v) -> true);
            }

            @Override
            public HttpRequest request() {
                return null;
            }

            @Override
            public Optional<HttpResponse<byte[]>> previousResponse() {
                return Optional.empty();
            }

            @Override
            public Optional<SSLSession> sslSession() {
                return Optional.empty();
            }

            @Override
            public java.net.URI uri() {
                return URI;
            }

            @Override
            public HttpClient.Version version() {
                return HttpClient.Version.HTTP_1_1;
            }
        };
    }
}
