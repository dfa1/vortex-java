package io.github.dfa1.vortex.demo.client;

import io.github.dfa1.vortex.demo.server.VortexServer;
import io.github.dfa1.vortex.reader.RowFilter;
import io.github.dfa1.vortex.reader.ScanOptions;
import io.github.dfa1.vortex.reader.VortexHttpReader;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Demo client: two modes, chosen by the first argument.
///
/// **Upload only** — copy a local file to a `vortex-server`, no query:
/// ```
/// java -jar vortex-demo.jar --upload trades.vortex http://127.0.0.1:8080/
/// ```
///
/// **Query** — a time-range-filtered, projected [VortexHttpReader] scan, printing how many bytes
/// the scan actually pulled over the wire against the object's full size. Given a remote
/// `http(s)://` URL, queries that object directly, no upload. Given a local file path, embeds its
/// own [VortexServer], uploads the file, then queries it there:
/// ```
/// java -jar vortex-demo.jar http://127.0.0.1:8080/trades.vortex --filter-column price --filter-min 100 --filter-max 105
/// java -jar vortex-demo.jar trades.vortex
/// ```
///
/// Filters by a numeric column range (`timestamp` by default) rather than an equality match on
/// some other column: real data is written in the order it arrives, so a time-ordered column is
/// naturally clustered by row position even without any artificial sorting -- zone-map pruning
/// narrows a time-range query to the handful of chunks that actually overlap it, no
/// `--sort-by`-style clustering needed.
public final class HttpRangeDemo {

    private static final String DEFAULT_FILTER_COLUMN = "timestamp";
    // Matches vortex-fakedata-generator's own README example: timestamp:i64:series(1700000000000,1000)
    // over 2,000,000 rows. This window covers rows [1_000_000, 1_050_000) -- 50,000 of 2,000,000 rows.
    private static final long DEFAULT_FILTER_MIN = 1_701_000_000_000L;
    private static final long DEFAULT_FILTER_MAX = 1_701_049_999_000L;
    private static final String DEFAULT_PROJECT_COLUMN = "price";

    private static final Pattern BYTES_SERVED_LINE = Pattern.compile("bytesServed=(\\d+)");
    private static final Duration POLL_INTERVAL = Duration.ofMillis(100);

    private HttpRangeDemo() {
    }

    /// @param args CLI arguments; run with no arguments to print usage
    public static void main(String[] args) {
        try {
            run(args);
        } catch (RuntimeException | IOException | InterruptedException e) {
            System.err.println("error: " + e.getMessage());
            System.err.println();
            printUsage();
            System.exit(1);
        }
    }

    private static void run(String[] args) throws IOException, InterruptedException {
        if (args.length == 0) {
            throw new IllegalArgumentException("a local file or a remote object URL is required");
        }
        if (args[0].equals("--upload")) {
            runUploadOnly(args);
            return;
        }

        String target = args[0];
        String filterColumn = DEFAULT_FILTER_COLUMN;
        long filterMin = DEFAULT_FILTER_MIN;
        long filterMax = DEFAULT_FILTER_MAX;
        String projectColumn = DEFAULT_PROJECT_COLUMN;

        int i = 1;
        while (i < args.length) {
            switch (args[i]) {
                case "--filter-column" -> {
                    filterColumn = args[++i];
                    i++;
                }
                case "--filter-min" -> {
                    filterMin = Long.parseLong(args[++i]);
                    i++;
                }
                case "--filter-max" -> {
                    filterMax = Long.parseLong(args[++i]);
                    i++;
                }
                case "--project" -> {
                    projectColumn = args[++i];
                    i++;
                }
                default -> throw new IllegalArgumentException("unknown argument: " + args[i]);
            }
        }

        if (target.startsWith("http://") || target.startsWith("https://")) {
            runQuery(HttpClient.newHttpClient(), URI.create(target), filterColumn, filterMin, filterMax, projectColumn);
        } else {
            Path file = Path.of(target);
            try (VortexServer server = VortexServer.start(Files.createTempDirectory("vortex-server"), 0)) {
                System.out.println("Embedded vortex-server at " + server.baseUri());
                HttpClient client = HttpClient.newHttpClient();
                URI objectUri = server.baseUri().resolve(file.getFileName().toString());
                System.out.println("Uploading to " + objectUri + " ...");
                upload(client, objectUri, file);
                runQuery(client, objectUri, filterColumn, filterMin, filterMax, projectColumn);
            }
        }
    }

    private static void runUploadOnly(String[] args) throws IOException, InterruptedException {
        if (args.length < 3) {
            throw new IllegalArgumentException("--upload requires FILE and SERVER_URL, e.g. "
                    + "--upload trades.vortex http://127.0.0.1:8080/");
        }
        Path file = Path.of(args[1]);
        URI objectUri = URI.create(args[2]).resolve(file.getFileName().toString());
        System.out.println("Uploading to " + objectUri + " ...");
        upload(HttpClient.newHttpClient(), objectUri, file);
        System.out.printf("Uploaded %,d bytes.%n", Files.size(file));
    }

    /// Queries `objectUri` directly -- no upload, whether it's an object this run just uploaded
    /// itself or one that was already there. Reports bytes fetched against the object's *actual*
    /// size, read from the opened [VortexHttpReader] itself ([io.github.dfa1.vortex.reader.VortexHandle#fileSize]),
    /// so no local file is needed for this path at all.
    private static void runQuery(HttpClient client, URI objectUri, String filterColumn, long filterMin,
            long filterMax, String projectColumn) throws IOException, InterruptedException {
        URI statsUri = objectUri.resolve("/_stats");
        long fileSize;
        try (VortexHttpReader vf = VortexHttpReader.open(objectUri)) {
            fileSize = vf.fileSize();
        }

        long bytesServedBefore = readBytesServed(client, statsUri);
        System.out.printf("%nScanning for %d <= %s <= %d, projecting '%s' over HTTP...%n%n",
                filterMin, filterColumn, filterMax, projectColumn);
        long rows = scanWithLiveDownloadCounter(client, statsUri, objectUri, bytesServedBefore, fileSize,
                filterColumn, filterMin, filterMax, projectColumn);
        long bytesServedAfter = readBytesServed(client, statsUri);

        long servedDuringScan = bytesServedAfter - bytesServedBefore;
        System.out.println("Matched rows: " + rows);
        System.out.printf("Bytes fetched over HTTP during the scan: %,d / %,d (%.2f%% of the object)%n",
                servedDuringScan, fileSize, 100.0 * servedDuringScan / fileSize);
    }

    /// Runs [#scan] while a background thread polls the server's `/_stats` endpoint and prints a
    /// live, in-place-updating "bytes downloaded so far" line to stderr — the point being visible
    /// on stage: the number climbs a little, then stops well short of the file's full size,
    /// instead of the scan just silently returning a final count. Only really visible to the eye
    /// on a large enough dataset that the scan takes more than a poll interval or two; on a small
    /// file the whole scan finishes before the first redraw and this degrades gracefully to
    /// printing just the final line.
    private static long scanWithLiveDownloadCounter(HttpClient client, URI statsUri, URI objectUri,
            long bytesServedBefore, long fileSize, String filterColumn, long filterMin, long filterMax,
            String projectColumn) throws IOException, InterruptedException {
        AtomicBoolean scanning = new AtomicBoolean(true);
        Thread poller = Thread.ofVirtual().name("download-progress").start(() -> {
            while (scanning.get()) {
                try {
                    long servedSoFar = readBytesServed(client, statsUri) - bytesServedBefore;
                    System.err.printf("\r  Downloaded so far: %,d / %,d bytes (%.1f%%)",
                            servedSoFar, fileSize, 100.0 * servedSoFar / fileSize);
                    Thread.sleep(POLL_INTERVAL);
                } catch (IOException | InterruptedException e) {
                    return;
                }
            }
        });

        try {
            return scan(objectUri, filterColumn, filterMin, filterMax, projectColumn);
        } finally {
            scanning.set(false);
            poller.interrupt();
            joinQuietly(poller);
            System.err.println();
        }
    }

    private static void joinQuietly(Thread thread) {
        try {
            thread.join(Duration.ofSeconds(1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void upload(HttpClient client, URI objectUri, Path localFile)
            throws IOException, InterruptedException {
        HttpRequest put = HttpRequest.newBuilder(objectUri)
                .PUT(HttpRequest.BodyPublishers.ofFile(localFile))
                .build();
        HttpResponse<Void> response = client.send(put, HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() != 201) {
            throw new IOException("upload failed: HTTP " + response.statusCode());
        }
    }

    private static long readBytesServed(HttpClient client, URI statsUri) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(statsUri).GET().build();
        HttpResponse<String> response = client.send(req, HttpResponse.BodyHandlers.ofString());
        Matcher m = BYTES_SERVED_LINE.matcher(response.body());
        if (!m.find()) {
            throw new IOException("could not parse /_stats response: " + response.body());
        }
        return Long.parseLong(m.group(1));
    }

    private static long scan(URI objectUri, String filterColumn, long filterMin, long filterMax, String projectColumn)
            throws IOException {
        RowFilter filter = RowFilter.gte(filterColumn, filterMin).and(RowFilter.lte(filterColumn, filterMax));
        ScanOptions opts = ScanOptions.all().withColumns(filterColumn, projectColumn).withFilter(filter);

        long rows = 0;
        try (VortexHttpReader vf = VortexHttpReader.open(objectUri);
             var iter = vf.scan(opts)) {
            while (iter.hasNext()) {
                try (var chunk = iter.next()) {
                    rows += chunk.rowCount();
                }
            }
        }
        return rows;
    }

    private static void printUsage() {
        System.err.println("""
                Usage:
                  vortex-demo --upload FILE SERVER_URL   upload FILE to a vortex-server, no query
                  vortex-demo URL [options]              query an existing remote object directly
                  vortex-demo FILE [options]              embed a server, upload FILE, then query it

                Options (query modes only):
                  --filter-column NAME   numeric column to range-filter on (default: timestamp)
                  --filter-min N         inclusive lower bound (default: matches the README example's
                                         row [1000000, 1050000) window)
                  --filter-max N         inclusive upper bound
                  --project NAME         column to project (default: price)

                Examples:
                  vortex-demo --upload trades.vortex http://127.0.0.1:8080/
                  vortex-demo http://127.0.0.1:8080/trades.vortex --filter-column price --filter-min 100 --filter-max 105
                  vortex-demo trades.vortex

                Generate a file first with vortex-fakedata-generator, e.g.:
                  vortex-fakedata-generator --rows 2000000 --out trades.vortex \\
                      "timestamp:i64:series(1700000000000,1000)" \\
                      "symbol:utf8:enum(SYM,30)" \\
                      "price:f64:range(50,150)" \\
                      "volume:i64:range(100,10000)"
                """);
    }
}
