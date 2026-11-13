package io.github.dfa1.vortex.reader;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.io.MemorySize;
import io.github.dfa1.vortex.core.io.IoBounds;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.io.VortexFormat;
import io.github.dfa1.vortex.core.fbs.FbsPostscript;
import io.github.dfa1.vortex.core.jfr.FileOpenedEvent;
import io.github.dfa1.vortex.core.jfr.HttpRangeEvent;
import io.github.dfa1.vortex.reader.layout.Layout;
import io.github.dfa1.vortex.reader.layout.LayoutRegistry;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

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
    static final MemorySize TAIL_SIZE = MemorySize.ofKiB(65);

    /// First guess for [#segmentTail(SegmentSpec)]: an array-node FlatBuffer is a few hundred
    /// bytes, so one request nearly always gets it with its length.
    private static final MemorySize TAIL_PEEK_SIZE = MemorySize.ofKiB(4);

    /// Cap on concurrent prefetch requests: enough to hide a ~200 ms round trip across a
    /// screenful of columns without opening a connection per segment.
    private static final int MAX_IN_FLIGHT = 8;

    /// Cap on concurrent [#prefetchTails(Collection)] requests: a tail is a few KiB, so the
    /// round trip dominates and a deeper queue than [#MAX_IN_FLIGHT] pays off.
    private static final int MAX_TAILS_IN_FLIGHT = 32;

    /// Most heap one [#prefetch(Collection)] call queues: a fetched response waits as a `byte[]`
    /// until its segments are read, unlike the off-heap arena a read copies it into.
    private static final long MAX_PREFETCH_BYTES = MemorySize.ofMiB(64).bytes();

    /// Rust's `CoalesceConfig::object_storage()`: merge two segments at most 1 MiB apart.
    private static final long COALESCE_DISTANCE = MemorySize.ofMiB(1).bytes();

    /// Rust's `CoalesceConfig::object_storage()`: a merged request spans at most 16 MiB.
    private static final long COALESCE_MAX_SIZE = MemorySize.ofMiB(16).bytes();

    /// Shared across all instances. JDK HttpClient is heavyweight and designed for reuse;
    /// per-reader instantiation would create redundant connection pools and selector threads.
    /// Never closed: lifetime tracks the JVM.
    ///
    /// Package-private and non-final purely as a unit-test seam: tests substitute a mocked
    /// client to drive the default-client [#open(URI, ReadRegistry)] overload without real
    /// network I/O. Production code never reassigns it.
    ///
    /// Follows redirects (`Redirect.NORMAL`): hosts like Hugging Face answer their
    /// `/resolve/` URLs with a 302/307 to the storage CDN, which serves the Range.
    static HttpClient defaultHttpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final URI uri;
    private final HttpClient client;
    private final Arena arena;
    private final long fileSize;
    private final int version;
    private final Footer footer;
    private final DType dtype;
    private final Layout layout;
    private final ReadRegistry registry;
    private final LayoutRegistry layoutRegistry;
    /// Range requests started by [#prefetch(Collection)] and not yet consumed by [#rawSegment].
    private final Map<SegmentSpec, Pending> prefetched;

    /// Tail requests started by [#prefetchTails(Collection)] and not yet consumed by [#segmentTail].
    private final Map<SegmentSpec, CompletableFuture<byte[]>> tails;
    /// Every segment already read, so reading it again (scrolling back) costs no request. Never
    /// evicted and never expired: the file is immutable for the life of this handle, and the bytes
    /// live in [#arena] until close anyway, so keeping the reference adds no memory. Bounded by the
    /// file size. Touched only from the arena's owning thread, like [#rawSegment].
    private final Map<SegmentSpec, MemorySegment> fetched;
    private final Semaphore inFlight;
    private final Semaphore tailInFlight;
    private final ExecutorService fetcher;
    private boolean closed;

    private VortexHttpReader(
        URI uri, HttpClient client, long fileSize,
        int version, Footer footer, DType dtype, Layout layout,
        ReadRegistry registry, LayoutRegistry layoutRegistry) {
        this.uri = uri;
        this.client = client;
        this.arena = Arena.ofConfined();
        this.fileSize = fileSize;
        this.version = version;
        this.footer = footer;
        this.dtype = dtype;
        this.layout = layout;
        this.registry = registry;
        this.layoutRegistry = layoutRegistry;
        this.prefetched = new ConcurrentHashMap<>();
        this.tails = new ConcurrentHashMap<>();
        this.fetched = new HashMap<>();
        this.closed = false;
        this.inFlight = new Semaphore(MAX_IN_FLIGHT);
        this.tailInFlight = new Semaphore(MAX_TAILS_IN_FLIGHT);
        this.fetcher = Executors.newVirtualThreadPerTaskExecutor();
    }

    /// Opens a remote Vortex file with the default encoding registry and HTTP client.
    ///
    /// @param uri the file's URL; the server must honor HTTP `Range` requests
    /// @return a handle reading the file through HTTP range requests
    /// @throws IOException if the request fails or the file is not a valid Vortex file
    public static VortexHttpReader open(URI uri) throws IOException {
        return open(uri, ReadRegistry.loadAll());
    }

    /// Opens a remote Vortex file with the given encoding registry and the default HTTP client.
    ///
    /// @param uri      the file's URL; the server must honor HTTP `Range` requests
    /// @param registry decoders for the encodings in the file
    /// @return a handle reading the file through HTTP range requests
    /// @throws IOException if the request fails or the file is not a valid Vortex file
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
        return open(uri, registry, LayoutRegistry.defaults(), client);
    }

    /// Opens a remote Vortex file with explicit encoding and layout registries and a
    /// caller-supplied [HttpClient].
    ///
    /// @param uri            HTTP(S) URL of the Vortex file
    /// @param registry       the encoding decode registry
    /// @param layoutRegistry the layout decode registry (custom layouts register here)
    /// @param client         HTTP client to use for all Range requests
    /// @return an open handle to the remote file
    /// @throws IOException if the file cannot be opened or parsed
    public static VortexHttpReader open(URI uri, ReadRegistry registry, LayoutRegistry layoutRegistry,
            HttpClient client) throws IOException {
        var opened = new FileOpenedEvent();
        opened.begin();
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
                    .formatted(trailer.postscriptLen(), TAIL_SIZE.bytes()));
        }

        MemorySegment postscriptSeg = IoBounds.slice(tailSeg, psOffInTail, trailer.postscriptLen());

        var ps = FbsPostscript.getRootAsFbsPostscript(postscriptSeg);

        var footerSpec = ps.footer();
        if (footerSpec == null) {
            throw new VortexException("postscript missing footer segment");
        }
        var layoutSpec = ps.layout();
        if (layoutSpec == null) {
            throw new VortexException("postscript missing layout segment");
        }
        var dtypeSpec = ps.dtype();

        MemorySegment footerBuf = fetchBlob(footerSpec.offset(), footerSpec.length(), tailStart, tail, uri, client);
        MemorySegment layoutBuf = fetchBlob(layoutSpec.offset(), layoutSpec.length(), tailStart, tail, uri, client);
        MemorySegment dtypeBuf = (dtypeSpec != null && dtypeSpec.length() > 0)
                                  ? fetchBlob(dtypeSpec.offset(), dtypeSpec.length(), tailStart, tail, uri, client)
                                  : null;

        var parsed = PostscriptParser.parseBlobs(footerBuf, layoutBuf, dtypeBuf);
        // Reject footer segmentSpecs that fall outside the file before any rawSegment() builds a
        // Range request from them. The local-file path runs this inside PostscriptParser.parse;
        // the HTTP path calls parseBlobs directly and must validate here too.
        PostscriptParser.validateSegmentSpecs(parsed.footer().segmentSpecs(), fileSize);
        DType dtype = registry.resolveExtensions(parsed.dtype());

        var reader = new VortexHttpReader(
            uri, client, fileSize, trailer.version(),
            parsed.footer(), dtype, parsed.layout(),
            registry, layoutRegistry
        );
        if (opened.shouldCommit()) {
            opened.source = describe(uri);
            opened.fileSize = fileSize;
            opened.formatVersion = trailer.version();
            opened.segmentCount = parsed.footer().segmentSpecs().size();
            opened.commit();
        }
        return reader;
    }

    /// Describes `uri` as `scheme://host[:port]` plus its raw path. Drops user info, query and
    /// fragment: presigned URLs carry credentials there, and a recording must not.
    static String describe(URI uri) {
        var sb = new StringBuilder();
        if (uri.getScheme() != null) {
            sb.append(uri.getScheme()).append("://");
        }
        if (uri.getHost() != null) {
            sb.append(uri.getHost());
        }
        if (uri.getPort() >= 0) {
            sb.append(':').append(uri.getPort());
        }
        if (uri.getRawPath() != null) {
            sb.append(uri.getRawPath());
        }
        return sb.toString();
    }

    /// Sends a range request, committing an [HttpRangeEvent] whether or not it got a response.
    private static HttpResponse<byte[]> sendRange(
            HttpRequest req, HttpClient client, URI uri, long offset, long length)
            throws IOException, InterruptedException {
        var event = new HttpRangeEvent();
        event.begin();
        int status = 0;
        try {
            HttpResponse<byte[]> resp = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
            status = resp.statusCode();
            return resp;
        } finally {
            if (event.shouldCommit()) {
                event.source = describe(uri);
                event.offset = offset;
                event.length = length;
                event.status = status;
                event.commit();
            }
        }
    }

    /// Fetches the last [#TAIL_SIZE] bytes in one request.
    /// Parses `Content-Range: bytes start-end/total` to extract file size and tail offset,
    /// avoiding a separate HEAD round trip.
    private static TailFetch fetchTail(URI uri, HttpClient client) throws IOException {
        HttpResponse<byte[]> resp = sendTailRequest(uri, client, TAIL_SIZE.bytes());
        if (resp.statusCode() == 416) {
            // A suffix range longer than the file is valid (RFC 9110 §14.1.3) and S3 or nginx
            // answer with the whole file, but some servers (e.g. Hugging Face's storage) reject
            // it with 416 + `Content-Range: bytes */<total>`. Retry once with that total.
            long total = parseUnsatisfiedRange(resp.headers().firstValue("Content-Range")
                    .orElseThrow(() -> new VortexException("416 response missing Content-Range from " + uri)), uri);
            resp = sendTailRequest(uri, client, total);
        }

        byte[] body = resp.body();
        int status = resp.statusCode();

        if (status == 206) {
            // Content-Range: bytes <start>-<end>/<total>
            String cr = resp.headers().firstValue("Content-Range")
                            .orElseThrow(() -> new VortexException("206 response missing Content-Range from " + uri));
            return parseContentRange(cr, body, uri);
        }

        if (status == 200) {
            // Server returned full file (no Range support)
            return new TailFetch(body, 0L, body.length);
        }

        throw new VortexException(httpError(resp, "fetching tail of " + uri));
    }

    /// The message for an unexpected status: the code, and for the server's own explanation, as
    /// Hugging Face gives on a `429` (who is limited, what to do), `x-error-message` and
    /// `Retry-After` when present. Both come from an untrusted server, so the text is capped.
    private static String httpError(HttpResponse<?> resp, String what) {
        StringBuilder message = new StringBuilder("HTTP ").append(resp.statusCode()).append(' ').append(what);
        resp.headers().firstValue("x-error-message").ifPresent(m -> message.append(": ").append(cap(m)));
        resp.headers().firstValue("Retry-After")
                .ifPresent(seconds -> message.append(" (Retry-After: ").append(cap(seconds)).append(')'));
        return message.toString();
    }

    private static String cap(String text) {
        return text.length() <= 300 ? text : text.substring(0, 300) + "…";
    }

    /// Sends one suffix-Range request for the last `suffixLength` bytes of `uri`.
    private static HttpResponse<byte[]> sendTailRequest(URI uri, HttpClient client, long suffixLength)
            throws IOException {
        HttpRequest req = HttpRequest.newBuilder(uri)
                              .header("Range", "bytes=-" + suffixLength)
                              .GET()
                              .build();
        try {
            return sendRange(req, client, uri, -1, suffixLength);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted fetching tail of " + uri, e);
        }
    }

    /// Parses a `bytes */<total>` unsatisfied-range Content-Range header (RFC 9110 §14.2)
    /// from an untrusted server. Any structural defect (missing `bytes */` prefix,
    /// non-numeric or negative total) surfaces as a [VortexException].
    private static long parseUnsatisfiedRange(String contentRange, URI uri) {
        try {
            String prefix = "bytes */";
            if (!contentRange.startsWith(prefix)) {
                throw new VortexException("malformed Content-Range '" + contentRange + "' from " + uri);
            }
            long total = Long.parseLong(contentRange.substring(prefix.length()));
            if (total < 0) {
                throw new VortexException("malformed Content-Range '" + contentRange + "' from " + uri);
            }
            return total;
        } catch (NumberFormatException e) {
            throw new VortexException("malformed Content-Range '" + contentRange + "' from " + uri, e);
        }
    }

    /// Parses a `bytes <start>-<end>/<total>` Content-Range header from an untrusted server.
    /// Any structural defect (missing `bytes ` prefix, missing `-`/`/`, non-numeric fields)
    /// surfaces as a [VortexException] rather than a raw [NumberFormatException] or
    /// [StringIndexOutOfBoundsException].
    private static TailFetch parseContentRange(String contentRange, byte[] body, URI uri) {
        try {
            String prefix = "bytes ";
            if (!contentRange.startsWith(prefix)) {
                throw new VortexException("malformed Content-Range '" + contentRange + "' from " + uri);
            }
            String spec = contentRange.substring(prefix.length()); // "<start>-<end>/<total>"
            int dash = spec.indexOf('-');
            int slash = spec.indexOf('/');
            if (dash < 0 || slash < 0 || dash > slash) {
                throw new VortexException("malformed Content-Range '" + contentRange + "' from " + uri);
            }
            long start = Long.parseLong(spec.substring(0, dash));
            long end = Long.parseLong(spec.substring(dash + 1, slash));
            long total = Long.parseLong(spec.substring(slash + 1));
            long expected = end - start + 1;
            if (body.length != expected) {
                throw new VortexException(
                    "HTTP tail from %s: Content-Range declares %d bytes but body has %d"
                        .formatted(uri, expected, body.length));
            }
            return new TailFetch(body, start, total);
        } catch (NumberFormatException e) {
            throw new VortexException("malformed Content-Range '" + contentRange + "' from " + uri, e);
        }
    }

    private static byte[] fetchRange(URI uri, long from, long to, HttpClient client) throws IOException {
        HttpRequest req = HttpRequest.newBuilder(uri)
                              .header("Range", "bytes=" + from + "-" + to)
                              .GET()
                              .build();
        try {
            HttpResponse<byte[]> resp = sendRange(req, client, uri, from, to - from + 1);
            int status = resp.statusCode();
            if (status != 206 && status != 200) {
                throw new VortexException(httpError(resp, "fetching range from " + uri));
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

    /// Returns a MemorySegment for a blob at absolute file `offset` of `length` bytes.
    /// If the blob falls within the already-fetched `tail`, extracts it directly;
    /// otherwise fires an additional Range request.
    private static MemorySegment fetchBlob(
        long offset, long length,
        long tailStart, byte[] tail,
        URI uri, HttpClient client) throws IOException {
        if (offset >= tailStart) {
            long relOffset = offset - tailStart;
            return IoBounds.slice(MemorySegment.ofArray(tail), relOffset, length);
        }
        byte[] bytes = fetchRange(uri, offset, offset + length - 1, client);
        return MemorySegment.ofArray(bytes);
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
    public io.github.dfa1.vortex.reader.array.Array decodeSegment(
            io.github.dfa1.vortex.reader.SegmentSpec spec,
            DType dtype, long rowCount,
            java.lang.foreign.SegmentAllocator arenaOut) {
        MemorySegment seg = rawSegment(spec);
        return new SerializedArrayDecoder(registry)
                .decode(seg, footer.arraySpecs(), dtype, rowCount, arenaOut);
    }

    /// Fetches the bytes of the given segment spec via HTTP Range.
    /// Returns an off-heap [MemorySegment] tied to this reader's [Arena].
    ///
    /// @param spec the segment to fetch
    /// @return a read-only [MemorySegment] containing the fetched bytes
    @Override
    public MemorySegment rawSegment(SegmentSpec spec) {
        MemorySegment cached = fetched.get(spec);
        if (cached != null) {
            return cached;
        }
        long offset = spec.offset();
        long length = spec.length();
        byte[] bytes;
        long from = 0;
        try {
            Pending started = prefetched.remove(spec);
            if (started != null) {
                bytes = started.bytes().get();
                from = started.from();
            } else {
                bytes = fetchRange(uri, offset, offset + length - 1, client);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new VortexException("interrupted fetching [%d, %d) from %s".formatted(offset, offset + length, uri));
        } catch (ExecutionException | IOException e) {
            Throwable cause = e;
            if (e instanceof ExecutionException && e.getCause() != null) {
                cause = e.getCause();
            }
            if (cause instanceof VortexException vortex) {
                throw vortex;
            }
            throw new VortexException(
                "failed to fetch [%d, %d) from %s: %s".formatted(offset, offset + length, uri, cause.getMessage()));
        }
        MemorySegment seg = arena.allocate(length);
        MemorySegment.copy(MemorySegment.ofArray(bytes), from, seg, 0, length);
        MemorySegment readOnly = seg.asReadOnly();
        fetched.put(spec, readOnly);
        return readOnly;
    }

    /// Fetches only the end of the segment: a first guess of [#TAIL_PEEK_SIZE] covers the
    /// array-node FlatBuffer of almost every segment, and a second request completes it when the
    /// trailing length says it is larger. Uses the request [#prefetchTails(Collection)] started
    /// for this segment, if any.
    ///
    /// @param spec the segment to read the end of
    /// @return a read-only [MemorySegment] ending at the same byte as the segment
    @Override
    public MemorySegment segmentTail(SegmentSpec spec) {
        CompletableFuture<byte[]> started = tails.remove(spec);
        byte[] tail;
        if (started == null) {
            tail = fetchTail(spec);
        } else {
            try {
                tail = started.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new VortexException("interrupted fetching the end of a segment from " + uri);
            } catch (ExecutionException e) {
                if (e.getCause() instanceof VortexException vortex) {
                    throw vortex;
                }
                throw new VortexException("failed to fetch the end of a segment from " + uri, e.getCause());
            }
        }
        return MemorySegment.ofArray(tail).asReadOnly();
    }

    /// Starts the [#segmentTail(SegmentSpec)] requests for `specs` on virtual threads, at most
    /// [#MAX_TAILS_IN_FLIGHT] at a time. A tail is a few KiB, so queueing one per segment of a large
    /// file stays small on the heap. Call it from the thread that opened this handle; it does
    /// nothing once the handle is closed.
    ///
    /// @param specs the segments whose ends are about to be read
    @Override
    public void prefetchTails(Collection<SegmentSpec> specs) {
        if (closed) {
            return;
        }
        for (SegmentSpec spec : specs) {
            tails.computeIfAbsent(spec, s -> CompletableFuture.supplyAsync(() -> fetchTail(s), fetcher));
        }
    }

    private byte[] fetchTail(SegmentSpec spec) {
        long end = spec.offset() + spec.length();
        long first = Math.min(spec.length(), TAIL_PEEK_SIZE.bytes());
        byte[] tail = fetchBounded(end - first, end);
        if (tail.length >= Integer.BYTES) {
            long needed = Integer.BYTES + Integer.toUnsignedLong(
                    ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN).getInt(tail.length - Integer.BYTES));
            if (needed > tail.length && needed <= spec.length()) {
                tail = fetchBounded(end - needed, end);
            }
        }
        return tail;
    }

    private byte[] fetchBounded(long from, long end) {
        tailInFlight.acquireUninterruptibly();
        try {
            return fetchRange(uri, from, end - 1, client);
        } catch (IOException e) {
            throw new VortexException("failed to fetch [%d, %d) from %s: %s".formatted(from, end, uri, e.getMessage()));
        } finally {
            tailInFlight.release();
        }
    }

    /// Starts the range requests for `specs` on virtual threads, at most [#MAX_IN_FLIGHT] at a
    /// time, so the [#rawSegment(SegmentSpec)] calls that follow find their bytes already on the
    /// way instead of paying one round trip each. Allocation still happens on the calling
    /// thread, in the reader's confined arena.
    ///
    /// Segments close together in the file share one request, as Rust's object-storage
    /// `CoalesceConfig` does: at most [#COALESCE_DISTANCE] of unwanted bytes between two, and at
    /// most [#COALESCE_MAX_SIZE] per request. A round trip costs far more than the bytes between
    /// neighboring segments, which are mostly alignment padding.
    ///
    /// A hint only: a call queues at most [#MAX_PREFETCH_BYTES] on the heap, and the segments past
    /// that are left to be read on demand. Call it from the thread that opened this handle, the
    /// one that calls [#rawSegment(SegmentSpec)]; it does nothing once the handle is closed.
    ///
    /// @param specs the segments about to be read
    @Override
    public void prefetch(Collection<SegmentSpec> specs) {
        if (closed) {
            return;
        }
        List<SegmentSpec> wanted = specs.stream()
                .filter(s -> !fetched.containsKey(s) && !prefetched.containsKey(s))
                .toList();
        long queued = 0;
        for (List<SegmentSpec> group : coalesce(wanted)) {
            long start = group.getFirst().offset();
            long end = start;
            for (SegmentSpec spec : group) {
                end = Math.max(end, spec.offset() + spec.length());
            }
            if (queued > 0 && queued + (end - start) > MAX_PREFETCH_BYTES) {
                break;
            }
            queued += end - start;
            fetchGroup(group, start, end);
        }
    }

    /// Groups `specs` into the requests that fetch them: sorted by offset, de-duplicated, with
    /// zero-length segments dropped, and each group at most [#COALESCE_DISTANCE] of gap between
    /// neighbors and [#COALESCE_MAX_SIZE] long. Package-private for the boundary tests.
    static List<List<SegmentSpec>> coalesce(Collection<SegmentSpec> specs) {
        List<SegmentSpec> sorted = specs.stream()
                .filter(s -> s.length() > 0)
                .distinct()
                .sorted(Comparator.comparingLong(SegmentSpec::offset))
                .toList();
        List<List<SegmentSpec>> groups = new ArrayList<>();
        int first = 0;
        while (first < sorted.size()) {
            long start = sorted.get(first).offset();
            long end = start + sorted.get(first).length();
            int next = first + 1;
            while (next < sorted.size()) {
                SegmentSpec candidate = sorted.get(next);
                long candidateEnd = Math.max(end, candidate.offset() + candidate.length());
                if (candidate.offset() - end > COALESCE_DISTANCE || candidateEnd - start > COALESCE_MAX_SIZE) {
                    break;
                }
                end = candidateEnd;
                next++;
            }
            groups.add(sorted.subList(first, next));
            first = next;
        }
        return groups;
    }

    /// One request for `[start, end)`; each of `group` reads its own slice of the response, which is
    /// held on the heap until the last of them is read.
    private void fetchGroup(List<SegmentSpec> group, long start, long end) {
        CompletableFuture<byte[]> whole = CompletableFuture.supplyAsync(() -> {
            inFlight.acquireUninterruptibly();
            try {
                return fetchRange(uri, start, end - 1, client);
            } catch (IOException e) {
                throw new VortexException("failed to fetch [%d, %d) from %s: %s"
                        .formatted(start, end, uri, e.getMessage()));
            } finally {
                inFlight.release();
            }
        }, fetcher);
        for (SegmentSpec spec : group) {
            prefetched.put(spec, new Pending(whole, spec.offset() - start));
        }
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
    public LayoutRegistry layoutRegistry() {
        return layoutRegistry;
    }

    @Override
    public void close() {
        // The arena first: a close from the wrong thread throws here, before anything is torn down.
        // The fetcher tasks only produce heap arrays, they never touch the arena.
        arena.close();
        closed = true;
        fetcher.shutdownNow();
        prefetched.clear();
        tails.clear();
        fetched.clear();
    }

    /// A started range request and where one segment's bytes begin in its response.
    private record Pending(CompletableFuture<byte[]> bytes, long from) {
    }

    @SuppressWarnings("java:S6218") // internal data carrier; record components are arrays of immutable primitives or refs that flow through pipelines without ever being compared.
    private record TailFetch(byte[] bytes, long start, long fileSize) {
    }
}
