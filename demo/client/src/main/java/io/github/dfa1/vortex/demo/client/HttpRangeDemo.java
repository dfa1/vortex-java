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

/// Demo client: uploads an existing Vortex file (e.g. one produced by
/// `vortex-fakedata-generator`) to a `vortex-server` object store, then runs a
/// time-range-filtered, projected [VortexHttpReader] scan against it — printing how many bytes
/// the scan actually pulled over the wire against the object's full size.
///
/// Filters by a numeric column range (`timestamp` by default) rather than an equality match on
/// some other column: real data is written in the order it arrives, so a time-ordered column is
/// naturally clustered by row position even without any artificial sorting -- zone-map pruning
/// narrows a time-range query to the handful of chunks that actually overlap it, no
/// `--sort-by`-style clustering needed.
///
/// Run standalone (embeds its own [VortexServer]):
/// ```
/// java -jar client/target/vortex-demo.jar --file trades.vortex
/// ```
///
/// Or against an already-running server — e.g. `java -jar server/target/vortex-server.jar` in a
/// separate terminal, for a two-process live demo:
/// ```
/// java -jar client/target/vortex-demo.jar --file trades.vortex --server http://127.0.0.1:8080/
/// ```
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
        Path file = null;
        URI serverBaseUri = null;
        String filterColumn = DEFAULT_FILTER_COLUMN;
        long filterMin = DEFAULT_FILTER_MIN;
        long filterMax = DEFAULT_FILTER_MAX;
        String projectColumn = DEFAULT_PROJECT_COLUMN;

        int i = 0;
        while (i < args.length) {
            switch (args[i]) {
                case "--file" -> {
                    file = Path.of(args[++i]);
                    i++;
                }
                case "--server" -> {
                    serverBaseUri = URI.create(args[++i]);
                    i++;
                }
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

        if (file == null) {
            throw new IllegalArgumentException("--file is required");
        }
        long fileSize = Files.size(file);

        if (serverBaseUri != null) {
            runAgainst(serverBaseUri, file, fileSize, filterColumn, filterMin, filterMax, projectColumn);
        } else {
            try (VortexServer server = VortexServer.start(Files.createTempDirectory("vortex-server"), 0)) {
                System.out.println("Embedded vortex-server at " + server.baseUri());
                runAgainst(server.baseUri(), file, fileSize, filterColumn, filterMin, filterMax, projectColumn);
            }
        }
    }

    private static void runAgainst(URI serverBaseUri, Path localFile, long fileSize,
            String filterColumn, long filterMin, long filterMax, String projectColumn)
            throws IOException, InterruptedException {
        HttpClient client = HttpClient.newHttpClient();
        URI objectUri = serverBaseUri.resolve(localFile.getFileName().toString());

        System.out.println("Uploading to " + objectUri + " ...");
        upload(client, objectUri, localFile);

        long bytesServedBefore = readBytesServed(client, serverBaseUri);
        System.out.printf("%nScanning for %d <= %s <= %d, projecting '%s' over HTTP...%n%n",
                filterMin, filterColumn, filterMax, projectColumn);
        long rows = scanWithLiveDownloadCounter(client, serverBaseUri, objectUri, bytesServedBefore, fileSize,
                filterColumn, filterMin, filterMax, projectColumn);
        long bytesServedAfter = readBytesServed(client, serverBaseUri);

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
    private static long scanWithLiveDownloadCounter(HttpClient client, URI serverBaseUri, URI objectUri,
            long bytesServedBefore, long fileSize, String filterColumn, long filterMin, long filterMax,
            String projectColumn) throws IOException, InterruptedException {
        AtomicBoolean scanning = new AtomicBoolean(true);
        Thread poller = Thread.ofVirtual().name("download-progress").start(() -> {
            while (scanning.get()) {
                try {
                    long servedSoFar = readBytesServed(client, serverBaseUri) - bytesServedBefore;
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

    private static long readBytesServed(HttpClient client, URI serverBaseUri)
            throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(serverBaseUri.resolve("_stats")).GET().build();
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
                Usage: vortex-demo --file FILE [options]

                Options:
                  --file FILE            local .vortex file to upload and scan (required)
                  --server URI           base URI of an already-running vortex-server; omit to embed one
                  --filter-column NAME   numeric column to range-filter on (default: timestamp)
                  --filter-min N         inclusive lower bound (default: matches the README example's
                                         row [1000000, 1050000) window)
                  --filter-max N         inclusive upper bound
                  --project NAME         column to project (default: price)

                Generate a file first with vortex-fakedata-generator, e.g.:
                  vortex-fakedata-generator --rows 2000000 --out trades.vortex \\
                      "timestamp:i64:series(1700000000000,1000)" \\
                      "symbol:utf8:enum(SYM,30)" \\
                      "price:f64:range(50,150)" \\
                      "volume:i64:range(100,10000)"
                """);
    }
}
