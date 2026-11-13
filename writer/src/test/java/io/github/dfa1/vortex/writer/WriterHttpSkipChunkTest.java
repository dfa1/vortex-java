package io.github.dfa1.vortex.writer;

import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.DTypes;
import io.github.dfa1.vortex.reader.ReadRegistry;
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

/// Walking past a chunk with `ScanIterator#next()` fetches nothing over HTTP, because a chunk decodes
/// a column only when it is read. The viewer relies on it to reach a far chunk (End) by stepping
/// over the ones in between, so a change that decoded eagerly would make every such jump download
/// the whole file (#744).
@ExtendWith(MockitoExtension.class)
class WriterHttpSkipChunkTest {

    private static final URI FILE_URI = URI.create("http://example.com/skip.vortex");
    private static final ColumnName V = ColumnName.of("v");

    @Mock
    private HttpClient client;

    @Test
    void skippingChunksWithoutReadingAColumn_fetchesNothing(@TempDir Path tmp) throws Exception {
        // Given — several chunks with zone maps, as a real file has. The writer repartitions batches
        // into chunks of about 1 MiB, so 4 batches of 300k rows give around 9.
        DType.Struct schema = new DType.Struct(List.of(V), List.<DType>of(DTypes.I64), false);
        WriteOptions opts = WriteOptions.cascading(0).withZoneMaps(true).withGlobalDict(false).withoutEditions();
        Path file = tmp.resolve("skip.vtx");
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var writer = VortexWriter.create(ch, schema, opts)) {
            for (int chunk = 0; chunk < 4; chunk++) {
                long[] values = new long[300_000];
                for (int r = 0; r < values.length; r++) {
                    values[r] = (long) chunk * values.length + r;
                }
                writer.writeChunk(Map.of(V, values));
            }
        }
        byte[] bytes = Files.readAllBytes(file);
        AtomicInteger requests = new AtomicInteger();
        given(client.send(any(), any())).willAnswer(inv -> {
            requests.incrementAndGet();
            return rangeResponse(inv.getArgument(0), bytes);
        });

        try (var reader = VortexHttpReader.open(FILE_URI, ReadRegistry.loadAll(), client);
             var iter = reader.scan(ScanOptions.all())) {
            int afterOpen = requests.get();

            // When — walk past every chunk without reading a column
            int result = 0;
            while (iter.hasNext()) {
                try (var _ = iter.next()) {
                    result++;
                }
            }

            // Then — a chunk decodes its columns when read, so passing one costs no request
            assertThat(result).isGreaterThan(1);
            assertThat(requests.get() - afterOpen).isZero();
        }
    }

    private static HttpResponse<byte[]> rangeResponse(HttpRequest req, byte[] file) {
        String[] parts = req.headers().firstValue("Range").orElseThrow().substring("bytes=".length()).split("-", 2);
        int from = Integer.parseInt(parts[0].isEmpty() ? "0" : parts[0]);
        int to = Math.min(Integer.parseInt(parts[1].isEmpty() ? "0" : parts[1]), file.length - 1);
        if (parts[0].isEmpty()) {
            from = Math.max(0, file.length - Integer.parseInt(parts[1]));
            to = file.length - 1;
        }
        byte[] body = Arrays.copyOfRange(file, from, to + 1);
        String contentRange = "bytes " + from + "-" + to + "/" + file.length;
        return new HttpResponse<>() {
            @Override
            public int statusCode() {
                return 206;
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
                return FILE_URI;
            }

            @Override
            public HttpClient.Version version() {
                return HttpClient.Version.HTTP_1_1;
            }
        };
    }
}
