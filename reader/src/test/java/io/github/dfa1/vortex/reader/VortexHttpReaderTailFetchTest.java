package io.github.dfa1.vortex.reader;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.io.VortexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;

/// Verifies the tail fetch: a `206 Partial Content` (with Content-Range), a `200 OK`
/// full-file fallback, and the Hugging Face quirks — a `302`/`307` to the storage CDN,
/// and a `416` for a suffix range longer than the file (retry with the announced total).
/// Serves a committed fixture from memory via a mocked [HttpClient] so the tests stay
/// deterministic and network-free.
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

    @Test
    void defaultClient_followsRedirects() {
        // Hugging Face's /resolve/ URLs answer 302/307 to the storage CDN that serves the
        // ranges, so the shared client must follow redirects (except HTTPS → HTTP).
        assertThat(VortexHttpReader.defaultHttpClient.followRedirects())
                .isEqualTo(HttpClient.Redirect.NORMAL);
    }

    @Test
    void open_redirectNotFollowed_throwsWithStatus() throws Exception {
        // Given — a redirect response that reaches the reader (Redirect.NEVER clients, or an
        // HTTPS → HTTP downgrade that Redirect.NORMAL won't follow) must fail loudly.
        doReturn(response(302, null, new byte[0])).when(client).send(any(), any());

        // When / Then
        assertThatThrownBy(() -> VortexHttpReader.open(URI, ReadRegistry.empty(), client))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("HTTP 302");
    }

    @Test
    void open_suffixLongerThanFile_retriesWithTotalFrom416() throws Exception {
        // Given — Hugging Face's storage answers 416 + `Content-Range: bytes */n` for a
        // suffix range longer than the file (RFC 9110 §14.1.3 says send the whole file;
        // S3 and nginx do). The retry with bytes=-n must open the file end-to-end.
        byte[] file = fixtureBytes();
        doReturn(response(416, "bytes */" + file.length, new byte[0]))
                .doReturn(response(206, "bytes 0-" + (file.length - 1) + "/" + file.length, file))
                .when(client).send(any(), any());

        // When
        try (var sut = VortexHttpReader.open(URI, ReadRegistry.empty(), client)) {

            // Then
            assertThat(sut.fileSize()).isEqualTo(file.length);
            assertThat(sut.layout()).isNotNull();
            assertThat(sut.footer()).isNotNull();
            assertThat(sut.dtype()).isNotNull();
        }
    }

    @Test
    void open_416WithoutContentRange_throws() throws Exception {
        // Given — 416 without the unsatisfied-range header: nothing to retry with.
        doReturn(response(416, null, new byte[0])).when(client).send(any(), any());

        // When / Then
        assertThatThrownBy(() -> VortexHttpReader.open(URI, ReadRegistry.empty(), client))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("416")
                .hasMessageContaining("Content-Range");
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
            "*/4728",            // missing the "bytes " prefix
            "bytes 0-4727/4728", // satisfied-range form, not the */total one
            "bytes */x",         // non-numeric total
            "bytes */",          // empty total
            "bytes */-1"         // negative total
    })
    void open_416WithMalformedUnsatisfiedRange_throws(String contentRange) throws Exception {
        // Given — the Content-Range of a 416 comes from an untrusted server; anything
        // structurally broken must surface as a VortexException, not a retry.
        doReturn(response(416, contentRange, new byte[0])).when(client).send(any(), any());

        // When / Then
        assertThatThrownBy(() -> VortexHttpReader.open(URI, ReadRegistry.empty(), client))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("Content-Range");
    }

    @Test
    void open_416RetryStill416_throwsWithStatus() throws Exception {
        // Given — the server rejects the retry too; give up with the retry's status.
        doReturn(response(416, "bytes */4728", new byte[0]))
                .doReturn(response(416, "bytes */4728", new byte[0]))
                .when(client).send(any(), any());

        // When / Then
        assertThatThrownBy(() -> VortexHttpReader.open(URI, ReadRegistry.empty(), client))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("HTTP 416");
    }

    @Test
    void open_429_reportsTheServersExplanationAndRetryAfter() throws Exception {
        // Given — Hugging Face's anonymous rate limit answers with a message saying what to do
        // and when to retry; the bare status code hides both from the user.
        doReturn(responseWithHeaders(429, Map.of(
                "x-error-message", List.of("We had to rate limit your IP. Pass a HF_TOKEN."),
                "retry-after", List.of("31")), new byte[0])).when(client).send(any(), any());

        // When / Then
        assertThatThrownBy(() -> VortexHttpReader.open(URI, ReadRegistry.empty(), client))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("HTTP 429")
                .hasMessageContaining("Pass a HF_TOKEN.")
                .hasMessageContaining("Retry-After: 31");
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

    private static HttpResponse<byte[]> response(int status, String contentRange, byte[] body) {
        Map<String, List<String>> headers = Map.of();
        if (contentRange != null) {
            headers = Map.of("content-range", List.of(contentRange));
        }
        return responseWithHeaders(status, headers, body);
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<byte[]> responseWithHeaders(int status, Map<String, List<String>> headers, byte[] body) {
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
                return HttpHeaders.of(headers, (k, v) -> true);
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
