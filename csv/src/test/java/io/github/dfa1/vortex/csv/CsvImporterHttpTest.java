package io.github.dfa1.vortex.csv;

import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.reader.Chunk;
import io.github.dfa1.vortex.reader.ScanIterator;
import io.github.dfa1.vortex.reader.ScanOptions;
import io.github.dfa1.vortex.reader.VortexReader;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.array.VarBinArray;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import javax.net.ssl.SSLSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;

/// Covers [CsvImporter#importCsv(URI, Path, ImportOptions)] against a mocked [HttpClient], the
/// same deterministic, network-free approach `VortexHttpReaderOpenOverloadTest` uses on the
/// reader side of the wire format. CSV is read front to back in one streaming pass, so — unlike
/// the `parquet` module's Range-request-based `HttpInputFile` — a single plain GET response is
/// all that's needed here.
@ExtendWith(MockitoExtension.class)
class CsvImporterHttpTest {

    @Mock
    private HttpClient client;

    private static final URI URI = java.net.URI.create("http://example.com/data.csv");

    @Test
    void importsFromUrl_streamsBodyIntoVortex(@TempDir Path tmp) throws Exception {
        // Given
        String csv = "id,price,name\n1,1.5,Alice\n2,2.7,Bob\n";
        doReturn(response(200, csv)).when(client).send(any(), any());
        HttpClient original = CsvImporter.httpClient;
        CsvImporter.httpClient = client;
        Path vortex = tmp.resolve("data.vortex");

        try {
            // When
            CsvImporter.importCsv(URI, vortex);

            // Then
            try (VortexReader reader = VortexReader.open(vortex)) {
                DType.Struct schema = (DType.Struct) reader.dtype();
                assertThat(schema.fieldNames().stream().map(ColumnName::value).toList())
                        .containsExactly("id", "price", "name");
                try (ScanIterator iter = reader.scan(ScanOptions.all())) {
                    assertThat(iter.hasNext()).isTrue();
                    try (Chunk chunk = iter.next()) {
                        assertThat(chunk.rowCount()).isEqualTo(2);
                        LongArray ids = chunk.column("id");
                        assertThat(ids.getLong(0)).isEqualTo(1L);
                        VarBinArray names = chunk.column("name");
                        assertThat(names.getString(0)).isEqualTo("Alice");
                        assertThat(names.getString(1)).isEqualTo("Bob");
                    }
                }
            }
        } finally {
            CsvImporter.httpClient = original;
        }
    }

    @Test
    void nonOkStatus_throws(@TempDir Path tmp) throws Exception {
        // Given
        doReturn(response(404, "")).when(client).send(any(), any());
        HttpClient original = CsvImporter.httpClient;
        CsvImporter.httpClient = client;
        Path vortex = tmp.resolve("data.vortex");

        try {
            // When / Then
            assertThatThrownBy(() -> CsvImporter.importCsv(URI, vortex))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("404");
        } finally {
            CsvImporter.httpClient = original;
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static HttpResponse<InputStream> response(int status, String body) {
        return new HttpResponse<>() {
            @Override
            public int statusCode() {
                return status;
            }

            @Override
            public InputStream body() {
                return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
            }

            @Override
            public HttpHeaders headers() {
                return HttpHeaders.of(Map.of(), (k, v) -> true);
            }

            @Override
            public HttpRequest request() {
                return null;
            }

            @Override
            public Optional<HttpResponse<InputStream>> previousResponse() {
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
