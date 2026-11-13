package io.github.dfa1.vortex.writer;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.DTypes;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.ScanOptions;
import io.github.dfa1.vortex.reader.VortexHttpReader;
import io.github.dfa1.vortex.reader.array.LongArray;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import javax.net.ssl.SSLSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/// #744: reading the columns of a chunk over HTTP costs one round trip per column. `Chunk#prefetch`
/// starts those range requests together, so a screenful of columns waits for one round trip
/// instead of one each.
///
/// Lives here because building the fixture needs [VortexWriter].
@ExtendWith(MockitoExtension.class)
class WriterHttpPrefetchTest {

    private static final URI FILE_URI = URI.create("http://example.com/prefetch.vortex");
    private static final int COLUMNS = 6;

    @Mock
    private HttpClient client;

    @Test
    void prefetch_fetchesTheColumnsConcurrently_andDecodesTheSameValues(@TempDir Path tmp) throws Exception {
        // Given a one-chunk file of 6 I64 columns, each its own 4 MB segment, so the 24 MB in all
        // exceeds one 16 MiB coalesced request and needs two. Each request waits at a barrier for
        // the other, so the test passes only if both are in flight at once.
        List<ColumnName> names = columnNames();
        byte[] bytes = writeFile(tmp, names, 500_000);
        CyclicBarrier bothInFlight = new CyclicBarrier(2);
        AtomicBoolean armed = new AtomicBoolean();
        given(client.send(any(), any())).willAnswer(inv -> {
            if (armed.get()) {
                bothInFlight.await(10, TimeUnit.SECONDS);
            }
            return rangeResponse(inv.getArgument(0), bytes);
        });

        try (var reader = VortexHttpReader.open(FILE_URI, ReadRegistry.loadAll(), client);
             var iter = reader.scan(ScanOptions.all());
             var chunk = iter.next()) {
            armed.set(true);

            // When
            chunk.prefetch(names);
            Map<ColumnName, long[]> result = new LinkedHashMap<>();
            for (ColumnName name : names) {
                LongArray column = chunk.column(name);
                result.put(name, new long[]{column.getLong(0), column.getLong(1), column.getLong(2)});
            }

            // Then
            assertThat(bothInFlight.getNumberWaiting()).isZero();
            for (int c = 0; c < COLUMNS; c++) {
                assertThat(result.get(names.get(c))).containsExactly(c * 100L, c * 100L + 1, c * 100L + 2);
            }
        }
    }

    @Test
    void prefetch_failedRequest_failsEachColumnOfTheGroupThenRefetches(@TempDir Path tmp) throws Exception {
        // Given — the server answers the coalesced request with a 500
        List<ColumnName> names = columnNames();
        byte[] bytes = writeFile(tmp, names);
        AtomicBoolean failing = new AtomicBoolean();
        given(client.send(any(), any())).willAnswer(inv -> {
            if (failing.get()) {
                return response(500, null, new byte[0]);
            }
            return rangeResponse(inv.getArgument(0), bytes);
        });

        try (var reader = VortexHttpReader.open(FILE_URI, ReadRegistry.loadAll(), client);
             var iter = reader.scan(ScanOptions.all());
             var chunk = iter.next()) {
            failing.set(true);
            chunk.prefetch(names);
            ColumnName first = names.getFirst();
            ColumnName last = names.getLast();

            // When / Then — every member of the group reports the failure as a VortexException
            assertThatThrownBy(() -> chunk.column(first)).isInstanceOf(VortexException.class);
            assertThatThrownBy(() -> chunk.column(last)).isInstanceOf(VortexException.class);

            // and once the server recovers, the same column is fetched afresh
            failing.set(false);
            LongArray result = chunk.column(first);
            assertThat(result.getLong(1)).isEqualTo(1L);
        }
    }

    @Test
    void prefetch_neighboringSegments_shareOneRequest(@TempDir Path tmp) throws Exception {
        // Given — the 6 column segments of a chunk sit next to each other in the file, so one
        // request covers them all instead of one round trip each
        List<ColumnName> names = columnNames();
        byte[] bytes = writeFile(tmp, names);
        AtomicInteger requests = new AtomicInteger();
        given(client.send(any(), any())).willAnswer(inv -> {
            requests.incrementAndGet();
            return rangeResponse(inv.getArgument(0), bytes);
        });

        try (var reader = VortexHttpReader.open(FILE_URI, ReadRegistry.loadAll(), client);
             var iter = reader.scan(ScanOptions.all());
             var chunk = iter.next()) {
            requests.set(0);

            // When
            chunk.prefetch(names);
            Map<ColumnName, long[]> result = new LinkedHashMap<>();
            for (ColumnName name : names) {
                LongArray column = chunk.column(name);
                result.put(name, new long[]{column.getLong(0), column.getLong(1), column.getLong(2)});
            }

            // Then — every column still gets its own bytes out of the shared response
            assertThat(requests.get()).isEqualTo(1);
            for (int c = 0; c < COLUMNS; c++) {
                assertThat(result.get(names.get(c))).containsExactly(c * 100L, c * 100L + 1, c * 100L + 2);
            }
        }
    }

    @Test
    void rereadingAColumn_isServedFromMemory(@TempDir Path tmp) throws Exception {
        // Given — a column read once, as when the viewer scrolls forward and then back
        List<ColumnName> names = columnNames();
        byte[] bytes = writeFile(tmp, names);
        AtomicInteger requests = new AtomicInteger();
        given(client.send(any(), any())).willAnswer(inv -> {
            requests.incrementAndGet();
            return rangeResponse(inv.getArgument(0), bytes);
        });

        try (var reader = VortexHttpReader.open(FILE_URI, ReadRegistry.loadAll(), client)) {
            try (var iter = reader.scan(ScanOptions.all()); var chunk = iter.next()) {
                chunk.prefetch(names);
                chunk.column(names.get(2));
            }
            requests.set(0);

            // When — a fresh scan reads the same column again
            long result;
            try (var iter = reader.scan(ScanOptions.all()); var chunk = iter.next()) {
                LongArray column = chunk.column(names.get(2));
                result = column.getLong(1);
            }

            // Then
            assertThat(requests.get()).isZero();
            assertThat(result).isEqualTo(201L);
        }
    }

    @Test
    void withoutPrefetch_columnsAreFetchedOneAtATime(@TempDir Path tmp) throws Exception {
        // Given
        List<ColumnName> names = columnNames();
        byte[] bytes = writeFile(tmp, names);
        AtomicInteger running = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        given(client.send(any(), any())).willAnswer(inv -> {
            peak.accumulateAndGet(running.incrementAndGet(), Math::max);
            try {
                return rangeResponse(inv.getArgument(0), bytes);
            } finally {
                running.decrementAndGet();
            }
        });

        try (var reader = VortexHttpReader.open(FILE_URI, ReadRegistry.loadAll(), client);
             var iter = reader.scan(ScanOptions.all());
             var chunk = iter.next()) {
            peak.set(0);

            // When
            for (ColumnName name : names) {
                chunk.column(name);
            }

            // Then
            assertThat(peak.get()).isEqualTo(1);
        }
    }

    private static List<ColumnName> columnNames() {
        return IntStream.range(0, COLUMNS).mapToObj(c -> ColumnName.of("c" + c)).toList();
    }

    private static byte[] writeFile(Path tmp, List<ColumnName> names) throws Exception {
        return writeFile(tmp, names, 3);
    }

    /// Column `c` holds `c * 100 + row`.
    private static byte[] writeFile(Path tmp, List<ColumnName> names, int rows) throws Exception {
        DType.Struct schema = new DType.Struct(names, names.stream().<DType>map(_ -> DTypes.I64).toList(), false);
        WriteOptions opts = WriteOptions.cascading(0).withZoneMaps(false).withGlobalDict(false).withoutEditions();
        Path file = tmp.resolve("prefetch.vtx");
        Map<ColumnName, Object> data = new LinkedHashMap<>();
        for (int c = 0; c < names.size(); c++) {
            long[] values = new long[rows];
            for (int r = 0; r < rows; r++) {
                values[r] = c * 100L + r;
            }
            data.put(names.get(c), values);
        }
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var writer = VortexWriter.create(ch, schema, opts)) {
            writer.writeChunk(data);
        }
        return Files.readAllBytes(file);
    }

    private static HttpResponse<byte[]> rangeResponse(HttpRequest req, byte[] file) {
        String rangeHeader = req.headers().firstValue("Range").orElseThrow();
        String[] parts = rangeHeader.substring("bytes=".length()).split("-", 2);
        int from = Integer.parseInt(parts[0].isEmpty() ? "0" : parts[0]);
        int to = Math.min(Integer.parseInt(parts[1].isEmpty() ? "0" : parts[1]), file.length - 1);
        if (parts[0].isEmpty()) {
            // suffix range "bytes=-N": the last N bytes
            from = Math.max(0, file.length - Integer.parseInt(parts[1]));
            to = file.length - 1;
        }
        byte[] body = Arrays.copyOfRange(file, from, to + 1);
        return response(206, "bytes " + from + "-" + to + "/" + file.length, body);
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
                Map<String, List<String>> headers = Map.of();
                if (contentRange != null) {
                    headers = Map.of("content-range", List.of(contentRange));
                }
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
