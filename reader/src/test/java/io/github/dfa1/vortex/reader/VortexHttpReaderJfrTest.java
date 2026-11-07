package io.github.dfa1.vortex.reader;

import io.github.dfa1.vortex.core.testing.JfrRecorderBase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class VortexHttpReaderJfrTest extends JfrRecorderBase {

    private static final URI SIGNED = URI.create("https://user:secret@example.com:8443/data/primitives.vortex?X-Amz-Signature=abc#frag");

    @Mock
    private HttpClient client;

    @Test
    void open_recordsTailRangeAndFileOpenedWithoutCredentials() throws Exception {
        // Given
        byte[] file = fixtureBytes();
        given(client.<byte[]>send(any(), any()))
                .willReturn(response(206, "bytes 0-" + (file.length - 1) + "/" + file.length, file));

        // When
        try (var sut = VortexHttpReader.open(SIGNED, ReadRegistry.empty(), client)) {
            assertThat(sut.fileSize()).isEqualTo(file.length);
        }

        // Then
        var ranges = events("HttpRange");
        assertThat(ranges).hasSize(1);
        assertThat(ranges.getFirst().getLong("offset")).isEqualTo(-1);
        assertThat(ranges.getFirst().getLong("length")).isEqualTo(VortexHttpReader.TAIL_SIZE.bytes());
        assertThat(ranges.getFirst().getInt("status")).isEqualTo(206);
        assertThat(ranges.getFirst().getString("source")).isEqualTo("https://example.com:8443/data/primitives.vortex");
        var opened = events("FileOpened");
        assertThat(opened).hasSize(1);
        assertThat(opened.getFirst().getString("source")).isEqualTo("https://example.com:8443/data/primitives.vortex");
        assertThat(opened.getFirst().getLong("fileSize")).isEqualTo(file.length);
    }

    @Test
    void open_sendFails_recordsStatusZeroAndNoFileOpened() throws Exception {
        // Given
        given(client.<byte[]>send(any(), any())).willThrow(new IOException("connection reset"));

        // When / Then
        assertThatThrownBy(() -> VortexHttpReader.open(SIGNED, ReadRegistry.empty(), client))
                .isInstanceOf(IOException.class);
        var ranges = events("HttpRange");
        assertThat(ranges).hasSize(1);
        assertThat(ranges.getFirst().getInt("status")).isZero();
        assertThat(events("FileOpened")).isEmpty();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "http://example.com/a.vortex|http://example.com/a.vortex",
            "https://example.com:9000/a/b.vortex?sig=x|https://example.com:9000/a/b.vortex",
            "https://u:p@[::1]:8443/a.vortex#f|https://[::1]:8443/a.vortex",
            "http://example.com|http://example.com",
    })
    void describe_dropsUserInfoQueryAndFragment(String uri, String expected) {
        // Given / When
        String described = VortexHttpReader.describe(URI.create(uri));

        // Then
        assertThat(described).isEqualTo(expected);
    }

    private static byte[] fixtureBytes() throws IOException {
        try (InputStream in = VortexHttpReaderJfrTest.class.getResourceAsStream("/fixtures/primitives.vortex")) {
            return in.readAllBytes();
        }
    }

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
                return HttpHeaders.of(Map.of("content-range", List.of(contentRange)), (k, v) -> true);
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
            public URI uri() {
                return SIGNED;
            }

            @Override
            public HttpClient.Version version() {
                return HttpClient.Version.HTTP_1_1;
            }
        };
    }
}
