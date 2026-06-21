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

/// Covers the [VortexHttpReader#open(URI, ReadRegistry)] overload, which wires the shared
/// default [HttpClient]. The three-arg overload is covered by
/// [VortexHttpReaderTailFetchTest]; this drives the two-arg overload to completion by
/// substituting the package-private default-client seam with a mocked client serving a
/// committed fixture, so it stays deterministic and network-free.
@ExtendWith(MockitoExtension.class)
class VortexHttpReaderOpenOverloadTest {

    @Mock
    private HttpClient client;

    private static final URI URI = java.net.URI.create("http://example.com/primitives.vortex");

    @Test
    void open_uriAndRegistry_usesDefaultClient() throws Exception {
        // Given the fixture fits the tail window and the default-client seam is the mock
        byte[] file = fixtureBytes();
        doReturn(response(206, "bytes 0-" + (file.length - 1) + "/" + file.length, file))
                .when(client).send(any(), any());
        HttpClient original = VortexHttpReader.defaultHttpClient;
        VortexHttpReader.defaultHttpClient = client;

        // When the two-arg overload is used (no explicit client)
        try (var sut = VortexHttpReader.open(URI, ReadRegistry.empty())) {

            // Then it delegates through the default client and parses metadata
            assertThat(sut.fileSize()).isEqualTo(file.length);
            assertThat(sut.fileSize()).isGreaterThan(VortexFormat.TRAILER_SIZE);
            assertThat(sut.dtype()).isNotNull();
        } finally {
            VortexHttpReader.defaultHttpClient = original;
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static byte[] fixtureBytes() throws IOException {
        try (InputStream in = VortexHttpReaderOpenOverloadTest.class
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
