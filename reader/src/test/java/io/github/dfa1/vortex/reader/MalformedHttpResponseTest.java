package io.github.dfa1.vortex.reader;

import io.github.dfa1.vortex.core.VortexException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.net.ssl.SSLSession;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;

/// Verifies that VortexHttpReader fails loudly when the HTTP server returns a body
/// whose length doesn't match the requested Range — both short and extra bytes.
@ExtendWith(MockitoExtension.class)
class MalformedHttpResponseTest {

    @Mock
    private HttpClient client;

    private static final URI URI = java.net.URI.create("http://example.com/test.vortex");

    @Test
    void tailFetch_shortBody_throws() throws Exception {
        // Given — server claims bytes 0-65535/131072 but returns only 100 bytes
        doReturn(response206("bytes 0-65535/131072", new byte[100]))
                .when(client).send(any(), any());

        // When / Then — 65536 declared, 100 received
        assertThatThrownBy(() -> VortexHttpReader.open(URI, ReadRegistry.empty(), client))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("65536 bytes")
                .hasMessageContaining("body has 100");
    }

    @Test
    void tailFetch_extraBody_throws() throws Exception {
        // Given — server claims bytes 0-99/131072 but returns 200 bytes
        doReturn(response206("bytes 0-99/131072", new byte[200]))
                .when(client).send(any(), any());

        // When / Then — 100 declared, 200 received
        assertThatThrownBy(() -> VortexHttpReader.open(URI, ReadRegistry.empty(), client))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("100 bytes")
                .hasMessageContaining("body has 200");
    }

    @Test
    void tailFetch_missingContentRange_throws() throws Exception {
        // Given — 206 but no Content-Range header
        doReturn(response206(null, new byte[64])).when(client).send(any(), any());

        // When / Then
        assertThatThrownBy(() -> VortexHttpReader.open(URI, ReadRegistry.empty(), client))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("Content-Range");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static HttpResponse<byte[]> response206(String contentRange, byte[] body) {
        return new HttpResponse<>() {
            @Override public int statusCode() { return 206; }
            @Override public byte[] body() { return body; }
            @Override public HttpHeaders headers() {
                Map<String, List<String>> map = contentRange == null
                        ? Map.of()
                        : Map.of("content-range", List.of(contentRange));
                return HttpHeaders.of(map, (k, v) -> true);
            }
            @Override public HttpRequest request() { return null; }
            @Override public Optional<HttpResponse<byte[]>> previousResponse() { return Optional.empty(); }
            @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
            @Override public java.net.URI uri() { return URI; }
            @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
        };
    }
}
