package io.github.dfa1.vortex.parquet;

import dev.hardwood.InputFile;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.OptionalLong;

/// [InputFile] backed by HTTP Range requests, mirroring `VortexHttpReader`'s range-fetch pattern
/// on the reader side of the wire format. Each [#readRange] call fires one targeted `Range` GET;
/// no full-file download occurs. [#length] is discovered once, via `HEAD`, in [#open].
final class HttpInputFile implements InputFile {

    /// Shared across all instances, mirroring `VortexHttpReader`'s `HttpClient` reuse rationale:
    /// the JDK client is heavyweight and designed for reuse. Never closed: lifetime tracks the JVM.
    private static final HttpClient DEFAULT_CLIENT = HttpClient.newHttpClient();

    private final URI uri;
    private final HttpClient client;
    private long length = -1;

    HttpInputFile(URI uri) {
        this(uri, DEFAULT_CLIENT);
    }

    HttpInputFile(URI uri, HttpClient client) {
        this.uri = uri;
        this.client = client;
    }

    @Override
    public void open() throws IOException {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<Void> response = send(request, HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " opening " + uri);
        }
        OptionalLong contentLength = response.headers().firstValueAsLong("Content-Length");
        if (contentLength.isEmpty()) {
            throw new IOException("HEAD response missing Content-Length from " + uri);
        }
        length = contentLength.getAsLong();
    }

    @Override
    public ByteBuffer readRange(long offset, int len) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("Range", "bytes=" + offset + "-" + (offset + len - 1))
                .GET()
                .build();
        HttpResponse<byte[]> response = send(request, HttpResponse.BodyHandlers.ofByteArray());
        int status = response.statusCode();
        if (status != 206 && status != 200) {
            throw new IOException("HTTP " + status + " fetching range from " + uri);
        }
        byte[] body = response.body();
        if (body.length != len) {
            throw new IOException(
                    "HTTP range [%d, %d] from %s: expected %d bytes, got %d"
                            .formatted(offset, offset + len - 1, uri, len, body.length));
        }
        return ByteBuffer.wrap(body);
    }

    @Override
    public long length() {
        return length;
    }

    @Override
    public String name() {
        return uri.toString();
    }

    @Override
    public void close() {
        // The shared HttpClient outlives this InputFile; nothing to release here.
    }

    private <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) throws IOException {
        try {
            return client.send(request, handler);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted fetching " + request.uri(), e);
        }
    }
}
