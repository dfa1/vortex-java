package io.github.dfa1.vortex.writer;

import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.MemorySize;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.RowFilter;
import io.github.dfa1.vortex.reader.ScanOptions;
import io.github.dfa1.vortex.reader.VortexHttpReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/// Regression coverage for #380: zone-map pruning over HTTP must consult the compact zone-map
/// table (one small segment for the whole column) instead of fetching each chunk's own full data
/// segment just to check whether it can be skipped — otherwise "checking" a chunk costs as much
/// over the network as simply not pruning it at all.
///
/// Lives here (not in `reader`) because building the fixture needs [VortexWriter]; `vortex-writer`
/// already depends on `vortex-reader` in test scope for round-trip assertions like this one.
@ExtendWith(MockitoExtension.class)
class WriterZoneMapHttpPruningTest {

    @Mock
    private HttpClient client;

    private static final URI FILE_URI = URI.create("http://example.com/pruning.vortex");

    @Test
    void pruningEveryChunk_fetchesTheZoneTableOnce_notOncePerChunk(@TempDir Path tmp) throws Exception {
        // Given a plain (non-cascading) I64 column across 4 chunks of 2 rows, each chunk in a
        // disjoint value range: [0,1], [10,11], [20,21], [30,31].
        DType.Struct schema = new DType.Struct(List.of(ColumnName.of("v")), List.of(DType.I64), false);
        WriteOptions opts = new WriteOptions(2, true, 0.90, 0, false, false, MemorySize.ofMiB(256), Map.of());
        Path file = tmp.resolve("pruning.vtx");
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, schema, opts)) {
            sut.writeChunk(Map.of(ColumnName.of("v"), new long[]{0L, 1L}));
            sut.writeChunk(Map.of(ColumnName.of("v"), new long[]{10L, 11L}));
            sut.writeChunk(Map.of(ColumnName.of("v"), new long[]{20L, 21L}));
            sut.writeChunk(Map.of(ColumnName.of("v"), new long[]{30L, 31L}));
        }
        byte[] bytes = Files.readAllBytes(file);
        AtomicInteger requestCount = new AtomicInteger();
        given(client.send(any(), any())).willAnswer(inv -> {
            requestCount.incrementAndGet();
            HttpRequest req = inv.getArgument(0);
            return rangeResponse(req, bytes);
        });

        // When — a filter no chunk can satisfy (every chunk's max is well below 1000). The file is
        // tiny, so #open() resolves footer/layout/dtype from a single tail fetch; only the scan
        // below should issue further requests.
        try (var reader = VortexHttpReader.open(FILE_URI, ReadRegistry.loadAll(), client)) {
            int afterOpen = requestCount.get();
            try (var iter = reader.scan(ScanOptions.all().withFilter(RowFilter.gt("v", 1000L)))) {
                assertThat(iter.hasNext()).isFalse();
            }

            // Then exactly one additional request — decoding the shared zone-map table once —
            // accounts for pruning all 4 chunks. Before the #380 fix, checking each chunk fetched
            // that chunk's own full data segment to read its embedded stats (4 requests), so
            // pruning cost as much over HTTP as not pruning at all.
            assertThat(requestCount.get() - afterOpen).isEqualTo(1);
        }
    }

    private static HttpResponse<byte[]> rangeResponse(HttpRequest req, byte[] file) {
        String rangeHeader = req.headers().firstValue("Range").orElseThrow();
        String spec = rangeHeader.substring("bytes=".length());
        String[] parts = spec.split("-", 2);
        int from = parts[0].isEmpty()
                ? Math.max(0, file.length - Integer.parseInt(parts[1]))
                : Integer.parseInt(parts[0]);
        int to = parts[0].isEmpty() ? file.length - 1 : Math.min(Integer.parseInt(parts[1]), file.length - 1);
        byte[] body = Arrays.copyOfRange(file, from, to + 1);
        String contentRange = "bytes " + from + "-" + to + "/" + file.length;
        return response(206, contentRange, body);
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
            public URI uri() {
                return FILE_URI;
            }

            @Override
            public HttpClient.Version version() {
                return HttpClient.Version.HTTP_1_1;
            }
        };
    }
}
