package io.github.dfa1.vortex.server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class VortexServerTest {

    private static final String CONTENT = "0123456789ABCDEFGHIJ"; // 20 bytes, index-addressable

    private final HttpClient client = HttpClient.newHttpClient();
    private VortexServer sut;
    private URI objectUri;
    private Path secretDir;

    @BeforeEach
    void startServer(@TempDir Path dataDir) throws IOException {
        Files.writeString(dataDir.resolve("data.bin"), CONTENT, StandardCharsets.US_ASCII);
        sut = VortexServer.start(dataDir, 0);
        objectUri = sut.baseUri().resolve("data.bin");

        // A sibling of dataDir, one "../" away -- deterministic regardless of how deep the OS
        // places @TempDir, unlike a fixed-depth traversal toward the filesystem root.
        secretDir = dataDir.resolveSibling("vortex-server-secret-" + System.nanoTime());
        Files.createDirectories(secretDir);
        Files.writeString(secretDir.resolve("secret.txt"), "TOP SECRET", StandardCharsets.US_ASCII);
    }

    @AfterEach
    void stopServer() throws IOException {
        sut.close();
        Files.deleteIfExists(secretDir.resolve("secret.txt"));
        Files.deleteIfExists(secretDir.resolve("pwned.txt"));
        Files.deleteIfExists(secretDir);
    }

    @Test
    void servesTheWholeObjectWhenNoRangeHeaderIsSent() throws Exception {
        // Given no Range header

        // When
        HttpResponse<String> result = get(objectUri, null);

        // Then
        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(result.body()).isEqualTo(CONTENT);
        assertThat(sut.bytesServed()).isEqualTo(CONTENT.length());
    }

    @Test
    void servesAByteRange() throws Exception {
        // Given a "bytes=2-5" range

        // When
        HttpResponse<String> result = get(objectUri, "bytes=2-5");

        // Then
        assertThat(result.statusCode()).isEqualTo(206);
        assertThat(result.body()).isEqualTo("2345");
        assertThat(result.headers().firstValue("Content-Range")).contains("bytes 2-5/20");
    }

    @Test
    void servesASuffixRange() throws Exception {
        // Given a "bytes=-3" (last 3 bytes) range

        // When
        HttpResponse<String> result = get(objectUri, "bytes=-3");

        // Then
        assertThat(result.statusCode()).isEqualTo(206);
        assertThat(result.body()).isEqualTo("HIJ");
    }

    @Test
    void rejectsAnOutOfBoundsRangeWith416() throws Exception {
        // Given a range starting past the end of the object

        // When
        HttpResponse<String> result = get(objectUri, "bytes=100-200");

        // Then
        assertThat(result.statusCode()).isEqualTo(416);
    }

    @Test
    void rejectsPathTraversalOnGet() throws Exception {
        // Given a key that escapes dataDir via "../" into a sibling directory. URI.create on a
        // full literal string preserves the ".." verbatim (unlike URI#resolve, which performs
        // RFC 3986 dot-segment normalization and would clamp it to the root before the request
        // is even sent) -- this genuinely exercises the server's own guard, not the client's.
        URI escaping = URI.create("http://127.0.0.1:" + sut.port() + "/../" + secretDir.getFileName() + "/secret.txt");

        // When
        HttpResponse<String> result = get(escaping, null);

        // Then
        assertThat(result.statusCode()).isEqualTo(404);
        assertThat(result.body()).doesNotContain("TOP SECRET");
    }

    @Test
    void rejectsPathTraversalOnPut() throws Exception {
        // Given a PUT whose key escapes dataDir via "../" into a sibling directory (see the
        // URI.create note above -- avoids URI#resolve's client-side dot-segment normalization)
        URI escaping = URI.create("http://127.0.0.1:" + sut.port() + "/../" + secretDir.getFileName() + "/pwned.txt");
        HttpRequest put = HttpRequest.newBuilder(escaping)
                .PUT(HttpRequest.BodyPublishers.ofString("pwned"))
                .build();

        // When
        HttpResponse<Void> result = client.send(put, HttpResponse.BodyHandlers.discarding());

        // Then
        assertThat(result.statusCode()).isNotEqualTo(201);
        assertThat(Files.exists(secretDir.resolve("pwned.txt"))).isFalse();
    }

    @Test
    void returns404ForAMissingObject() throws Exception {
        // Given a key with no matching object
        URI missing = sut.baseUri().resolve("nope.bin");

        // When
        HttpResponse<String> result = get(missing, null);

        // Then
        assertThat(result.statusCode()).isEqualTo(404);
    }

    @Test
    void putThenGetRoundTrips() throws Exception {
        // Given a PUT of a new object
        URI uploaded = sut.baseUri().resolve("uploaded.bin");
        HttpRequest put = HttpRequest.newBuilder(uploaded)
                .PUT(HttpRequest.BodyPublishers.ofString("hello object storage"))
                .build();

        // When
        HttpResponse<Void> putResult = client.send(put, HttpResponse.BodyHandlers.discarding());
        HttpResponse<String> getResult = get(uploaded, null);

        // Then
        assertThat(putResult.statusCode()).isEqualTo(201);
        assertThat(getResult.statusCode()).isEqualTo(200);
        assertThat(getResult.body()).isEqualTo("hello object storage");
        assertThat(sut.bytesReceived()).isEqualTo("hello object storage".length());
    }

    @Test
    void reportsStatsIndependentlyOfItsOwnResponseSize() throws Exception {
        // Given a prior GET that served 4 bytes
        get(objectUri, "bytes=2-5");

        // When
        HttpResponse<String> result = get(sut.baseUri().resolve("_stats"), null);

        // Then
        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(result.body()).contains("bytesServed=4").contains("bytesReceived=0");
    }

    @Test
    void listsStoredObjects() throws Exception {
        // Given the pre-seeded "data.bin" object

        // When
        HttpResponse<String> result = get(sut.baseUri(), null);

        // Then
        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(result.body()).contains("data.bin\t" + CONTENT.length());
    }

    private HttpResponse<String> get(URI uri, String rangeHeader) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).GET();
        if (rangeHeader != null) {
            builder.header("Range", rangeHeader);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
