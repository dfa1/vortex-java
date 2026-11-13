package io.github.dfa1.vortex.writer;

import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.DTypes;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.SegmentSpec;
import io.github.dfa1.vortex.reader.VortexHttpReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.Random;
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
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ssl.SSLSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/// `segmentTail` reads only the end of a segment over HTTP, where the inspector finds the array node,
/// so `inspect` on a remote file no longer downloads every segment to print metadata (#743).
@ExtendWith(MockitoExtension.class)
class WriterHttpTailTest {

    private static final URI FILE_URI = URI.create("http://example.com/tail.vortex");
    private static final ColumnName V = ColumnName.of("v");

    @Mock
    private HttpClient client;

    @Test
    void segmentTail_matchesTheEndOfTheSegmentAndFetchesLittle(@TempDir Path tmp) throws Exception {
        // Given — a multi-megabyte file with many segments
        DType.Struct schema = new DType.Struct(List.of(V), List.<DType>of(DTypes.I64), false);
        WriteOptions opts = WriteOptions.cascading(0).withZoneMaps(true).withGlobalDict(false).withoutEditions();
        Path file = tmp.resolve("tail.vtx");
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var writer = VortexWriter.create(ch, schema, opts)) {
            for (int chunk = 0; chunk < 4; chunk++) {
                long[] values = new long[300_000];
                Random random = new Random(chunk);
                for (int r = 0; r < values.length; r++) {
                    values[r] = random.nextLong();
                }
                writer.writeChunk(Map.of(V, values));
            }
        }
        byte[] bytes = Files.readAllBytes(file);
        AtomicLong fetched = new AtomicLong();
        given(client.send(any(), any())).willAnswer(inv -> {
            HttpResponse<byte[]> response = rangeResponse(inv.getArgument(0), bytes);
            fetched.addAndGet(response.body().length);
            return response;
        });

        try (var reader = VortexHttpReader.open(FILE_URI, ReadRegistry.loadAll(), client)) {
            List<SegmentSpec> specs = reader.footer().segmentSpecs().stream().filter(s -> s.length() > 4).toList();
            long afterOpen = fetched.get();

            // When
            reader.prefetchTails(specs);
            List<byte[]> result = new ArrayList<>();
            for (SegmentSpec spec : specs) {
                result.add(reader.segmentTail(spec).toArray(ValueLayout.JAVA_BYTE));
            }

            // Then — each tail is the last bytes of its segment, and far less than the segments themselves was fetched
            long segmentBytes = 0;
            for (int i = 0; i < specs.size(); i++) {
                SegmentSpec spec = specs.get(i);
                int end = (int) (spec.offset() + spec.length());
                assertThat(result.get(i)).isEqualTo(Arrays.copyOfRange(bytes, end - result.get(i).length, end));
                segmentBytes += spec.length();
            }
            assertThat(segmentBytes).isGreaterThan(1_000_000);
            assertThat(fetched.get() - afterOpen).isLessThan(segmentBytes / 10);
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
