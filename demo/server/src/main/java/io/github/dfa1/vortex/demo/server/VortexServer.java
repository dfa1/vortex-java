package io.github.dfa1.vortex.demo.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/// Minimal, dependency-free object-storage HTTP server: `GET`/`HEAD` a key (honoring byte-range
/// requests), `PUT` a key, and list the store — just enough surface to stand in for a real object
/// store (S3, Azure Blob, GCS) in a demo.
///
/// The JDK's own `jwebserver` (`com.sun.net.httpserver.SimpleFileServer`) does not implement
/// `Range` at all — it always returns the whole object. `VortexHttpReader` (in `vortex-reader`)
/// depends entirely on working `206 Partial Content` responses to fetch only the segments a scan
/// actually touches; against a non-Range server its first segment fetch throws `VortexException`
/// because the returned byte count never matches the requested range. This class is the minimum
/// needed to demonstrate partial fetching over plain HTTP without a real cloud account.
///
/// One flat key space, backed by one local directory — a key maps directly to a file under
/// [#dataDir]. Binds to loopback only, single range per request, no auth, no multipart, no
/// versioning. Not hardened for untrusted networks; demo/local use only. Depends on nothing but
/// the JDK, so it is reusable outside this project.
public final class VortexServer implements AutoCloseable {

    private final HttpServer server;
    private final Path dataDir;
    private final AtomicLong bytesServed = new AtomicLong();
    private final AtomicLong bytesReceived = new AtomicLong();

    private VortexServer(HttpServer server, Path dataDir) {
        this.server = server;
        this.dataDir = dataDir;
    }

    /// Starts a server storing objects under `dataDir`, bound to loopback on `port`.
    ///
    /// @param dataDir directory backing the object store; created if absent
    /// @param port    port to bind, or `0` to let the OS choose an ephemeral port
    /// @return a running server; call [#close] to stop it
    /// @throws IOException if `dataDir` cannot be created or the server socket cannot be bound
    public static VortexServer start(Path dataDir, int port) throws IOException {
        Files.createDirectories(dataDir);
        // Absolute + normalized once here so every #resolve call's startsWith containment check
        // compares like with like -- a relative dataDir (e.g. ".") left as-is would never
        // startsWith-match a resolved candidate that Path#normalize stripped the "." from,
        // 404-ing every GET/HEAD even though the file is right there (confirmed live: "vortex-server
        // 8080 ." served a correct directory listing but 404'd every single-object request).
        Path root = dataDir.toAbsolutePath().normalize();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        VortexServer instance = new VortexServer(server, root);
        server.createContext("/", instance::handle);
        server.start();
        return instance;
    }

    /// Runs a standalone server. Args: `[port] [dataDir]`, both optional — an omitted port picks
    /// an ephemeral one, an omitted data directory creates a fresh temp directory. Blocks until
    /// interrupted (`Ctrl+C`).
    ///
    /// @param args `[port] [dataDir]`
    /// @throws IOException      if the server cannot start
    /// @throws InterruptedException if interrupted while blocking
    public static void main(String[] args) throws IOException, InterruptedException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 0;
        Path dataDir = args.length > 1 ? Path.of(args[1]) : Files.createTempDirectory("vortex-server");
        try (VortexServer server = start(dataDir, port)) {
            System.out.println("vortex-server listening on " + server.baseUri());
            System.out.println("Serving objects from " + dataDir.toAbsolutePath());
            System.out.println("Press Ctrl+C to stop.");
            Thread.currentThread().join();
        }
    }

    /// @return the port this server is actually bound to
    public int port() {
        return server.getAddress().getPort();
    }

    /// @return the server's base URI, e.g. `http://127.0.0.1:PORT/`
    public URI baseUri() {
        return URI.create("http://127.0.0.1:" + port() + "/");
    }

    /// @return the total response-body bytes served (`GET`/`HEAD`) since this server started
    public long bytesServed() {
        return bytesServed.get();
    }

    /// @return the total request-body bytes received (`PUT`) since this server started
    public long bytesReceived() {
        return bytesReceived.get();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            switch (exchange.getRequestMethod()) {
                case "GET", "HEAD" -> handleGet(exchange);
                case "PUT" -> handlePut(exchange);
                default -> exchange.sendResponseHeaders(405, -1);
            }
        } finally {
            exchange.close();
        }
    }

    private void handleGet(HttpExchange exchange) throws IOException {
        boolean sendBody = exchange.getRequestMethod().equals("GET");
        URI uri = exchange.getRequestURI();
        if (uri.getPath().equals("/_stats")) {
            handleStats(exchange, sendBody);
            return;
        }
        if (uri.getPath().equals("/")) {
            handleList(exchange, sendBody);
            return;
        }

        Path file = resolve(uri);
        if (file == null || !Files.isRegularFile(file)) {
            exchange.sendResponseHeaders(404, -1);
            return;
        }

        long totalLength = Files.size(file);
        exchange.getResponseHeaders().add("Accept-Ranges", "bytes");
        exchange.getResponseHeaders().add("Content-Type", "application/octet-stream");

        String rangeHeader = exchange.getRequestHeaders().getFirst("Range");
        Optional<ByteRange> range = parseRange(rangeHeader, totalLength);
        if (rangeHeader != null && range.isEmpty()) {
            exchange.getResponseHeaders().add("Content-Range", "bytes */" + totalLength);
            exchange.sendResponseHeaders(416, -1);
            return;
        }

        long start = range.map(ByteRange::start).orElse(0L);
        long end = range.map(ByteRange::end).orElse(totalLength - 1);
        long served = totalLength == 0 ? 0 : end - start + 1;

        if (range.isPresent()) {
            exchange.getResponseHeaders().add("Content-Range", "bytes %d-%d/%d".formatted(start, end, totalLength));
        }
        exchange.sendResponseHeaders(range.isPresent() ? 206 : 200, sendBody ? served : -1);
        if (sendBody) {
            writeRange(file, start, served, exchange.getResponseBody());
        }
        bytesServed.addAndGet(served);
        log("%-4s %-40s range=%-20s served=%,10d / %,10d bytes (%5.1f%%)".formatted(
                exchange.getRequestMethod(), uri, rangeHeader == null ? "(none, full object)" : rangeHeader,
                served, totalLength, totalLength == 0 ? 0 : 100.0 * served / totalLength));
    }

    private void handleList(HttpExchange exchange, boolean sendBody) throws IOException {
        StringBuilder body = new StringBuilder();
        try (Stream<Path> entries = Files.list(dataDir)) {
            entries.filter(Files::isRegularFile).sorted().forEach(p -> {
                try {
                    body.append(p.getFileName()).append('\t').append(Files.size(p)).append('\n');
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
        }
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(200, sendBody ? bytes.length : -1);
        if (sendBody) {
            exchange.getResponseBody().write(bytes);
        }
        bytesServed.addAndGet(bytes.length);
    }

    /// Reports [#bytesServed] and [#bytesReceived] as plain text, unaffected by this response's
    /// own size — a demo client (possibly a separate process, unable to read these counters
    /// directly) polls this to measure exactly how many bytes a scan pulled over the wire.
    private void handleStats(HttpExchange exchange, boolean sendBody) throws IOException {
        byte[] bytes = "bytesServed=%d%nbytesReceived=%d%n".formatted(bytesServed.get(), bytesReceived.get())
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(200, sendBody ? bytes.length : -1);
        if (sendBody) {
            exchange.getResponseBody().write(bytes);
        }
    }

    private void handlePut(HttpExchange exchange) throws IOException {
        Path file = resolve(exchange.getRequestURI());
        if (file == null) {
            exchange.sendResponseHeaders(400, -1);
            return;
        }
        Files.createDirectories(file.getParent());
        long received;
        try (InputStream in = exchange.getRequestBody();
             OutputStream out = Files.newOutputStream(file, StandardOpenOption.CREATE,
                     StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            received = in.transferTo(out);
        }
        bytesReceived.addAndGet(received);
        log("PUT  %-40s received=%,d bytes -> %s".formatted(exchange.getRequestURI(), received, file));
        exchange.sendResponseHeaders(201, -1);
    }

    private Path resolve(URI requestUri) {
        String path = requestUri.getPath();
        String relative = path.startsWith("/") ? path.substring(1) : path;
        if (relative.isEmpty()) {
            return null;
        }
        Path candidate = dataDir.resolve(relative).normalize();
        return candidate.startsWith(dataDir) ? candidate : null;
    }

    private static void writeRange(Path file, long offset, long length, OutputStream out) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            channel.position(offset);
            ByteBuffer buffer = ByteBuffer.allocate(8192);
            long remaining = length;
            while (remaining > 0) {
                buffer.clear();
                buffer.limit((int) Math.min(buffer.capacity(), remaining));
                int n = channel.read(buffer);
                if (n < 0) {
                    break;
                }
                buffer.flip();
                out.write(buffer.array(), buffer.arrayOffset(), buffer.remaining());
                remaining -= n;
            }
        }
    }

    private static void log(String message) {
        System.out.println("  " + message);
    }

    /// An inclusive byte range, already resolved against a known total length.
    ///
    /// @param start first byte offset served (inclusive)
    /// @param end   last byte offset served (inclusive)
    private record ByteRange(long start, long end) {
    }

    /// Parses a `Range: bytes=...` header value. Supports `bytes=X-Y`, `bytes=X-` (open-ended),
    /// and the suffix form `bytes=-N` (last `N` bytes). Multi-range requests (comma-separated)
    /// and malformed values are reported as empty, distinct from a missing header, so the caller
    /// can tell "no Range header" (serve the whole object) apart from "Range header present but
    /// unsatisfiable" (`416`).
    ///
    /// @param header      the raw `Range` header value, or `null` if absent
    /// @param totalLength the object's total length in bytes
    /// @return the resolved range, or empty if `header` is `null`, malformed, multi-range, or
    ///         out of bounds
    private static Optional<ByteRange> parseRange(String header, long totalLength) {
        if (header == null || !header.startsWith("bytes=") || header.contains(",")) {
            return Optional.empty();
        }
        String spec = header.substring("bytes=".length());
        int dash = spec.indexOf('-');
        if (dash < 0) {
            return Optional.empty();
        }
        String startPart = spec.substring(0, dash);
        String endPart = spec.substring(dash + 1);
        try {
            long start;
            long end;
            if (startPart.isEmpty()) {
                if (endPart.isEmpty()) {
                    return Optional.empty();
                }
                long suffixLength = Long.parseLong(endPart);
                start = Math.max(0, totalLength - suffixLength);
                end = totalLength - 1;
            } else {
                start = Long.parseLong(startPart);
                end = endPart.isEmpty() ? totalLength - 1 : Math.min(Long.parseLong(endPart), totalLength - 1);
            }
            if (start < 0 || start > end || start >= totalLength) {
                return Optional.empty();
            }
            return Optional.of(new ByteRange(start, end));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
