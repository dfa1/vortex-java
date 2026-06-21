package io.github.dfa1.vortex.reader;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.IoBounds;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.core.VortexFormat;
import io.github.dfa1.vortex.fbs.Postscript;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/// Handle to a remote Vortex file read via HTTP Range requests.
///
/// On open: fetches the last [#TAIL_SIZE] bytes to locate the trailer, postscript,
/// and metadata blobs (footer, layout, dtype). Each [#rawSegment] call fires a targeted
/// Range request; no full-file download occurs.
///
/// All allocated buffers (segment bytes and encoding outputs) share a single
/// confined [Arena] and are released when this handle is closed.
public final class VortexHttpReader implements VortexHandle {

    /// Tail window fetched on open. 65 KB covers the trailer, postscript, and
    /// all metadata blobs for typical Vortex files.
    static final int TAIL_SIZE = 65 * 1024;

    /// Shared across all instances. JDK HttpClient is heavyweight and designed for reuse;
    /// per-reader instantiation would create redundant connection pools and selector threads.
    /// Never closed: lifetime tracks the JVM.
    ///
    /// Package-private and non-final purely as a unit-test seam: tests substitute a mocked
    /// client to drive the default-client [#open(URI, ReadRegistry)] overload without real
    /// network I/O. Production code never reassigns it.
    static HttpClient defaultHttpClient = HttpClient.newHttpClient();

    private final URI uri;
    private final HttpClient client;
    private final Arena arena;
    private final long fileSize;
    private final int version;
    private final Footer footer;
    private final DType dtype;
    private final Layout layout;
    private final ReadRegistry registry;

    private VortexHttpReader(
        URI uri, HttpClient client, long fileSize,
        int version, Footer footer, DType dtype, Layout layout,
        ReadRegistry registry
    ) {
        this.uri = uri;
        this.client = client;
        this.arena = Arena.ofConfined();
        this.fileSize = fileSize;
        this.version = version;
        this.footer = footer;
        this.dtype = dtype;
        this.layout = layout;
        this.registry = registry;
    }

    public static VortexHttpReader open(URI uri) throws IOException {
        return open(uri, ReadRegistry.loadAll());
    }

    public static VortexHttpReader open(URI uri, ReadRegistry registry) throws IOException {
        return open(uri, registry, defaultHttpClient);
    }

    /// Opens a remote Vortex file using a caller-supplied [HttpClient].
    ///
    /// Use this overload when the default shared client is unsuitable — e.g. to configure
    /// a proxy, custom TLS context, or per-request timeout.
    ///
    /// @param uri      HTTP(S) URL of the Vortex file
    /// @param registry decoding registry
    /// @param client   HTTP client to use for all Range requests
    /// @return an open handle to the remote file
    /// @throws IOException if the file cannot be opened or parsed
    public static VortexHttpReader open(URI uri, ReadRegistry registry, HttpClient client) throws IOException {
        // Single suffix Range request — Content-Range response header gives us fileSize.
        // Avoids a separate HEAD round trip.
        TailFetch tf = fetchTail(uri, client);
        byte[] tail = tf.bytes();
        long tailStart = tf.start();
        long fileSize = tf.fileSize();
        long tailLen = tail.length;

        MemorySegment tailSeg = MemorySegment.ofArray(tail);
        long trailerOff = tailLen - VortexFormat.TRAILER_SIZE;
        long bodyBytes = fileSize - VortexFormat.TRAILER_SIZE;
        Trailer trailer = Trailer.parse(IoBounds.slice(tailSeg, trailerOff, VortexFormat.TRAILER_SIZE), bodyBytes);

        // HTTP-specific: postscript may extend past the prefetched tail and need a larger fetch.
        long psOffInTail = trailerOff - trailer.postscriptLen();
        if (psOffInTail < 0) {
            throw new VortexException(
                "postscript (%d bytes) extends beyond %d-byte tail; fetch larger tail"
                    .formatted(trailer.postscriptLen(), TAIL_SIZE));
        }

        ByteBuffer postscriptBuf = IoBounds.slice(tailSeg, psOffInTail, trailer.postscriptLen())
                                       .asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);

        var ps = Postscript.getRootAsPostscript(postscriptBuf);

        var footerSpec = ps.footer();
        if (footerSpec == null) {
            throw new VortexException("postscript missing footer segment");
        }
        var layoutSpec = ps.layout();
        if (layoutSpec == null) {
            throw new VortexException("postscript missing layout segment");
        }
        var dtypeSpec = ps.dtype();

        ByteBuffer footerBuf = fetchBlob(footerSpec.offset(), footerSpec.length(), tailStart, tail, uri, client);
        ByteBuffer layoutBuf = fetchBlob(layoutSpec.offset(), layoutSpec.length(), tailStart, tail, uri, client);
        ByteBuffer dtypeBuf = (dtypeSpec != null && dtypeSpec.length() > 0)
                                  ? fetchBlob(dtypeSpec.offset(), dtypeSpec.length(), tailStart, tail, uri, client)
                                  : null;

        var parsed = PostscriptParser.parseBlobs(footerBuf, layoutBuf, dtypeBuf);

        return new VortexHttpReader(
            uri, client, fileSize, trailer.version(),
            parsed.footer(), parsed.dtype(), parsed.layout(),
            registry
        );
    }

    /// Fetches the last [#TAIL_SIZE] bytes in one request.
    /// Parses `Content-Range: bytes start-end/total` to extract file size and tail offset,
    /// avoiding a separate HEAD round trip.
    private static TailFetch fetchTail(URI uri, HttpClient client) throws IOException {
        HttpRequest req = HttpRequest.newBuilder(uri)
                              .header("Range", "bytes=-" + TAIL_SIZE)
                              .GET()
                              .build();
        HttpResponse<byte[]> resp;
        try {
            resp = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted fetching tail of " + uri, e);
        }

        byte[] body = resp.body();
        int status = resp.statusCode();

        if (status == 206) {
            // Content-Range: bytes <start>-<end>/<total>
            String cr = resp.headers().firstValue("Content-Range")
                            .orElseThrow(() -> new VortexException("206 response missing Content-Range from " + uri));
            String spec = cr.substring("bytes ".length()); // "<start>-<end>/<total>"
            int slash = spec.indexOf('/');
            long total = Long.parseLong(spec.substring(slash + 1));
            long start = Long.parseLong(spec.substring(0, spec.indexOf('-')));
            long end = Long.parseLong(spec.substring(spec.indexOf('-') + 1, slash));
            long expected = end - start + 1;
            if (body.length != expected) {
                throw new VortexException(
                    "HTTP tail from %s: Content-Range declares %d bytes but body has %d"
                        .formatted(uri, expected, body.length));
            }
            return new TailFetch(body, start, total);
        }

        if (status == 200) {
            // Server returned full file (no Range support)
            return new TailFetch(body, 0L, body.length);
        }

        throw new VortexException("HTTP " + status + " fetching tail of " + uri);
    }

    private static byte[] fetchRange(URI uri, long from, long to, HttpClient client) throws IOException {
        HttpRequest req = HttpRequest.newBuilder(uri)
                              .header("Range", "bytes=" + from + "-" + to)
                              .GET()
                              .build();
        try {
            HttpResponse<byte[]> resp = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
            int status = resp.statusCode();
            if (status != 206 && status != 200) {
                throw new VortexException("HTTP " + status + " fetching range from " + uri);
            }
            byte[] body = resp.body();
            long expected = to - from + 1;
            if (body.length != expected) {
                throw new VortexException(
                    "HTTP range [%d, %d] from %s: expected %d bytes, got %d"
                        .formatted(from, to, uri, expected, body.length));
            }
            return body;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted fetching range from " + uri, e);
        }
    }

    /// Returns a ByteBuffer for a blob at absolute file `offset` of `length` bytes.
    /// If the blob falls within the already-fetched `tail`, extracts it directly;
    /// otherwise fires an additional Range request.
    private static ByteBuffer fetchBlob(
        long offset, long length,
        long tailStart, byte[] tail,
        URI uri, HttpClient client
    ) throws IOException {
        if (offset >= tailStart) {
            int relOffset = (int) (offset - tailStart);
            return ByteBuffer.wrap(tail, relOffset, (int) length)
                       .slice().order(ByteOrder.LITTLE_ENDIAN);
        }
        byte[] bytes = fetchRange(uri, offset, offset + length - 1, client);
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }

    @Override
    public DType dtype() {
        return dtype;
    }

    @Override
    public Layout layout() {
        return layout;
    }

    @Override
    public Footer footer() {
        return footer;
    }

    @Override
    public int version() {
        return version;
    }

    @Override
    public long fileSize() {
        return fileSize;
    }

    // ── HTTP helpers ──────────────────────────────────────────────────────────

    @Override
    public io.github.dfa1.vortex.reader.array.Array decodeFlatSegment(
            io.github.dfa1.vortex.reader.SegmentSpec spec,
            DType dtype, long rowCount,
            java.lang.foreign.SegmentAllocator arenaOut
    ) {
        MemorySegment seg = rawSegment(spec);
        return new FlatSegmentDecoder(registry)
                .decode(seg, footer.arraySpecs(), dtype, rowCount, arenaOut);
    }

    /// Fetches the bytes of the given segment spec via HTTP Range.
    /// Returns an off-heap [MemorySegment] tied to this reader's [Arena].
    ///
    /// @param spec the segment to fetch
    /// @return a read-only [MemorySegment] containing the fetched bytes
    @Override
    public MemorySegment rawSegment(SegmentSpec spec) {
        long offset = spec.offset();
        long length = spec.length();
        byte[] bytes;
        try {
            bytes = fetchRange(uri, offset, offset + length - 1, client);
        } catch (IOException e) {
            throw new VortexException(
                "failed to fetch [%d, %d) from %s: %s".formatted(offset, offset + length, uri, e.getMessage()));
        }
        MemorySegment seg = arena.allocate(length);
        MemorySegment.copy(MemorySegment.ofArray(bytes), 0, seg, 0, length);
        return seg.asReadOnly();
    }

    @Override
    public ScanIterator scan(ScanOptions options) {
        return new ScanIterator(this, options);
    }

    @Override
    public ReadRegistry registry() {
        return registry;
    }

    @Override
    public void close() {
        arena.close();
    }

    @SuppressWarnings("java:S6218") // internal data carrier; record components are arrays of immutable primitives or refs that flow through pipelines without ever being compared.
    private record TailFetch(byte[] bytes, long start, long fileSize) {
    }
}
