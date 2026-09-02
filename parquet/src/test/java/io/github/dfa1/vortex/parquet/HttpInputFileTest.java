package io.github.dfa1.vortex.parquet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.net.ssl.SSLSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;

/// Verifies [HttpInputFile]'s HTTP Range-request mechanics against a mocked [HttpClient], the
/// same deterministic, network-free approach `VortexHttpReaderTailFetchTest` uses on the reader
/// side of the wire format.
@ExtendWith(MockitoExtension.class)
class HttpInputFileTest {

    @Mock
    private HttpClient client;

    private static final URI URI = java.net.URI.create("http://example.com/data.parquet");

    @Test
    void open_headResponseWithContentLength_setsLength() throws Exception {
        // Given
        doReturn(response(200, Map.of("content-length", List.of("12345")), null))
                .when(client).send(any(), any());
        HttpInputFile sut = new HttpInputFile(URI, client);

        // When
        sut.open();

        // Then
        assertThat(sut.length()).isEqualTo(12345L);
    }

    @Test
    void open_missingContentLength_throws() throws Exception {
        // Given — a HEAD response carrying no Content-Length header
        doReturn(response(200, Map.of(), null)).when(client).send(any(), any());
        HttpInputFile sut = new HttpInputFile(URI, client);

        // When / Then
        assertThatThrownBy(sut::open)
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Content-Length");
    }

    @Test
    void open_non200Status_throws() throws Exception {
        // Given
        doReturn(response(404, Map.of(), null)).when(client).send(any(), any());
        HttpInputFile sut = new HttpInputFile(URI, client);

        // When / Then
        assertThatThrownBy(sut::open)
                .isInstanceOf(IOException.class)
                .hasMessageContaining("404");
    }

    @Test
    void readRange_returnsRequestedBytes() throws Exception {
        // Given
        byte[] body = "hello".getBytes(StandardCharsets.UTF_8);
        doReturn(response(206, Map.of(), body)).when(client).send(any(), any());
        HttpInputFile sut = new HttpInputFile(URI, client);

        // When
        ByteBuffer result = sut.readRange(10, body.length);

        // Then
        byte[] actual = new byte[result.remaining()];
        result.get(actual);
        assertThat(actual).isEqualTo(body);
    }

    @Test
    void readRange_bodyLengthMismatch_throws() throws Exception {
        // Given — server returned fewer bytes than the requested range length
        byte[] body = "short".getBytes(StandardCharsets.UTF_8);
        doReturn(response(206, Map.of(), body)).when(client).send(any(), any());
        HttpInputFile sut = new HttpInputFile(URI, client);

        // When / Then
        assertThatThrownBy(() -> sut.readRange(0, body.length + 1))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("expected");
    }

    @Test
    void readRange_badStatus_throws() throws Exception {
        // Given
        doReturn(response(500, Map.of(), new byte[0])).when(client).send(any(), any());
        HttpInputFile sut = new HttpInputFile(URI, client);

        // When / Then
        assertThatThrownBy(() -> sut.readRange(0, 1))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("500");
    }

    @Test
    void name_returnsUriString() {
        // Given / When / Then
        assertThat(new HttpInputFile(URI, client).name()).isEqualTo(URI.toString());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static <T> HttpResponse<T> response(int status, Map<String, List<String>> headers, T body) {
        return new HttpResponse<>() {
            @Override
            public int statusCode() {
                return status;
            }

            @Override
            public T body() {
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
            public Optional<HttpResponse<T>> previousResponse() {
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
