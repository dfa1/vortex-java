package io.github.dfa1.vortex.io;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.Footer;
import io.github.dfa1.vortex.core.Layout;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.encoding.EncodingRegistry;
import io.github.dfa1.vortex.fbs.Postscript;
import io.github.dfa1.vortex.scan.ScanIterator;
import io.github.dfa1.vortex.scan.ScanOptions;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/// Handle to a remote Vortex file read via HTTP Range requests.
///
/// On open: fetches the last [#TAIL_SIZE] bytes to locate the trailer, postscript,
/// and metadata blobs (footer, layout, dtype). Each [#slice] call fires a targeted
/// Range request; no full-file download occurs.
///
/// All allocated buffers (segment bytes and encoding outputs) share a single
/// confined [Arena] and are released when this handle is closed.
public final class VortexHttpReader implements VortexHandle {

    /// Tail window fetched on open. 65 KB covers the trailer, postscript, and
    /// all metadata blobs for typical Vortex files.
    static final int TAIL_SIZE = 65 * 1024;

    private final URI uri;
    private final HttpClient client;
    private final Arena arena;
    private final long fileSize;
    private final int version;
    private final Footer footer;
    private final DType dtype;
    private final Layout layout;
    private final EncodingRegistry registry;

    private VortexHttpReader(
            URI uri, HttpClient client, Arena arena, long fileSize,
            int version, Footer footer, DType dtype, Layout layout,
            EncodingRegistry registry
    ) {
        this.uri = uri;
        this.client = client;
        this.arena = arena;
        this.fileSize = fileSize;
        this.version = version;
        this.footer = footer;
        this.dtype = dtype;
        this.layout = layout;
        this.registry = registry;
    }

    public static VortexHttpReader open(URI uri) throws IOException {
        return open(uri, EncodingRegistry.loadAll());
    }

    public static VortexHttpReader open(URI uri, EncodingRegistry registry) throws IOException {
        HttpClient client = HttpClient.newHttpClient();
        Arena arena = Arena.ofConfined();
        try {
            // Single suffix Range request — Content-Range response header gives us fileSize.
            // Avoids a separate HEAD round trip.
            TailFetch tf = fetchTail(client, uri);
            byte[] tail = tf.bytes();
            long tailStart = tf.start();
            long fileSize = tf.fileSize();
            long tailLen = tail.length;

            MemorySegment tailSeg = MemorySegment.ofArray(tail);
            long trailerOff = tailLen - VortexReader.TRAILER_SIZE;

            int version = Short.toUnsignedInt(tailSeg.get(VortexReader.LE_SHORT, trailerOff));
            int postscriptLen = Short.toUnsignedInt(tailSeg.get(VortexReader.LE_SHORT, trailerOff + 2));
            checkMagic(tailSeg, trailerOff + 4, uri);

            if (version != VortexReader.SUPPORTED_VERSION) {
                throw new VortexException(
                        "unsupported file version=" + version
                                + " (this reader supports version " + VortexReader.SUPPORTED_VERSION + ")");
            }
            if (postscriptLen == 0) {
                throw new VortexException("invalid postscript: length is zero");
            }
            long bodyBytes = fileSize - VortexReader.TRAILER_SIZE;
            if (postscriptLen > bodyBytes) {
                throw new VortexException(
                        "invalid postscript: length=" + postscriptLen
                                + " exceeds file body size=" + bodyBytes);
            }

            long psOffInTail = trailerOff - postscriptLen;
            if (psOffInTail < 0) {
                throw new VortexException(
                        "postscript (%d bytes) extends beyond %d-byte tail; fetch larger tail"
                                .formatted(postscriptLen, TAIL_SIZE));
            }

            ByteBuffer postscriptBuf = tailSeg.asSlice(psOffInTail, postscriptLen)
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

            ByteBuffer footerBuf = fetchBlob(footerSpec.offset(), footerSpec.length(),
                    tailStart, tail, client, uri);
            ByteBuffer layoutBuf = fetchBlob(layoutSpec.offset(), layoutSpec.length(),
                    tailStart, tail, client, uri);
            ByteBuffer dtypeBuf = (dtypeSpec != null && dtypeSpec.length() > 0)
                                          ? fetchBlob(dtypeSpec.offset(), dtypeSpec.length(), tailStart, tail, client, uri)
                                          : null;

            var parsed = PostscriptParser.parseBlobs(footerBuf, layoutBuf, dtypeBuf);

            return new VortexHttpReader(
                    uri, client, arena, fileSize, version,
                    parsed.footer(), parsed.dtype(), parsed.layout(),
                    registry
            );
        } catch (Exception e) {
            arena.close();
            throw e;
        }
    }

    /// Fetches the last [#TAIL_SIZE] bytes in one request.
    /// Parses `Content-Range: bytes start-end/total` to extract file size and tail offset,
    /// avoiding a separate HEAD round trip.
    private static TailFetch fetchTail(HttpClient client, URI uri) throws IOException {
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
            return new TailFetch(body, start, total);
        }

        if (status == 200) {
            // Server returned full file (no Range support)
            return new TailFetch(body, 0L, body.length);
        }

        throw new VortexException("HTTP " + status + " fetching tail of " + uri);
    }

    private static byte[] fetchRange(HttpClient client, URI uri, long from, long to) throws IOException {
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
            return resp.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted fetching range from " + uri, e);
        }
    }

    private static void checkMagic(MemorySegment seg, long offset, URI uri) {
        byte m0 = seg.get(ValueLayout.JAVA_BYTE, offset);
        byte m1 = seg.get(ValueLayout.JAVA_BYTE, offset + 1);
        byte m2 = seg.get(ValueLayout.JAVA_BYTE, offset + 2);
        byte m3 = seg.get(ValueLayout.JAVA_BYTE, offset + 3);
        byte[] magic = VortexReader.MAGIC;
        if (m0 != magic[0] || m1 != magic[1] || m2 != magic[2] || m3 != magic[3]) {
            throw new VortexException(
                    "invalid magic bytes [%02x %02x %02x %02x] from %s".formatted(m0, m1, m2, m3, uri));
        }
    }

    /// Returns a ByteBuffer for a blob at absolute file `offset` of `length` bytes.
    /// If the blob falls within the already-fetched `tail`, extracts it directly;
    /// otherwise fires an additional Range request.
    private static ByteBuffer fetchBlob(
            long offset, long length,
            long tailStart, byte[] tail,
            HttpClient client, URI uri
    ) throws IOException {
        if (offset >= tailStart) {
            int relOffset = (int) (offset - tailStart);
            return ByteBuffer.wrap(tail, relOffset, (int) length)
                           .slice().order(ByteOrder.LITTLE_ENDIAN);
        }
        byte[] bytes = fetchRange(client, uri, offset, offset + length - 1);
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
    public EncodingRegistry registry() {
        return registry;
    }

    /// Fetches bytes `[offset, offset+length)` via HTTP Range and returns them
    /// as an off-heap [MemorySegment] tied to this reader's [Arena].
    @Override
    public MemorySegment slice(long offset, long length) {
        byte[] bytes;
        try {
            bytes = fetchRange(client, uri, offset, offset + length - 1);
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
    public void close() {
        arena.close();
        client.close();
    }

    private record TailFetch(byte[] bytes, long start, long fileSize) {
    }
}
