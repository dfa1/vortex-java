package io.github.dfa1.vortex.demo;

import io.github.dfa1.vortex.reader.RowFilter;
import io.github.dfa1.vortex.reader.ScanOptions;
import io.github.dfa1.vortex.reader.VortexHttpReader;
import io.github.dfa1.vortex.server.VortexServer;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Demo client: generates a synthetic tick dataset, uploads it to a `vortex-server` object
/// store, then runs a filtered/projected [VortexHttpReader] scan against it — printing how many
/// bytes the scan actually pulled over the wire against the object's full size.
///
/// Run standalone (embeds its own [VortexServer]):
/// ```
/// java -jar demo/target/vortex-demo.jar
/// ```
///
/// Or against an already-running server — e.g. `java -jar server/target/vortex-server.jar` in a
/// separate terminal, for a two-process live demo:
/// ```
/// java -jar demo/target/vortex-demo.jar http://127.0.0.1:8080/
/// ```
public final class HttpRangeDemo {

    private static final long ROW_COUNT = 2_000_000;
    private static final int SYMBOL_COUNT = 30;
    private static final int CHUNK_SIZE = 65_536;
    private static final String QUERY_SYMBOL = "SYM015";
    private static final String OBJECT_KEY = "trades.vortex";

    private static final Pattern BYTES_SERVED_LINE = Pattern.compile("bytesServed=(\\d+)");

    private HttpRangeDemo() {
    }

    /// @param args optional: base URI of an already-running `vortex-server`; omitted embeds one
    /// @throws IOException          if the demo file, server, upload, or scan fails
    /// @throws InterruptedException if interrupted while starting the embedded server or sending
    ///                              an HTTP request
    public static void main(String[] args) throws IOException, InterruptedException {
        Path localFile = Files.createTempDirectory("vortex-demo").resolve(OBJECT_KEY);
        System.out.println("Generating demo file (" + ROW_COUNT + " rows, " + SYMBOL_COUNT + " symbols)...");
        DemoDataGenerator.generate(localFile, ROW_COUNT, SYMBOL_COUNT, CHUNK_SIZE);
        long fileSize = Files.size(localFile);
        System.out.printf("Wrote %s (%.1f MB)%n%n", localFile, fileSize / 1_000_000.0);

        if (args.length > 0) {
            runAgainst(URI.create(args[0]), localFile, fileSize);
        } else {
            try (VortexServer server = VortexServer.start(Files.createTempDirectory("vortex-server"), 0)) {
                System.out.println("Embedded vortex-server at " + server.baseUri());
                runAgainst(server.baseUri(), localFile, fileSize);
            }
        }
    }

    private static void runAgainst(URI serverBaseUri, Path localFile, long fileSize)
            throws IOException, InterruptedException {
        HttpClient client = HttpClient.newHttpClient();
        URI objectUri = serverBaseUri.resolve(OBJECT_KEY);

        System.out.println("Uploading to " + objectUri + " ...");
        upload(client, objectUri, localFile);

        long bytesServedBefore = readBytesServed(client, serverBaseUri);
        System.out.printf("%nScanning for %s=%s, projecting '%s' over HTTP...%n%n",
                DemoDataGenerator.SYMBOL, QUERY_SYMBOL, DemoDataGenerator.PRICE);
        long rows = scan(objectUri);
        long bytesServedAfter = readBytesServed(client, serverBaseUri);

        long servedDuringScan = bytesServedAfter - bytesServedBefore;
        System.out.println();
        System.out.println("Matched rows: " + rows);
        System.out.printf("Bytes fetched over HTTP during the scan: %,d / %,d (%.2f%% of the object)%n",
                servedDuringScan, fileSize, 100.0 * servedDuringScan / fileSize);
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

    private static long scan(URI objectUri) throws IOException {
        ScanOptions opts = ScanOptions.all()
                .withColumns(DemoDataGenerator.SYMBOL, DemoDataGenerator.PRICE)
                .withFilter(RowFilter.eq(DemoDataGenerator.SYMBOL, QUERY_SYMBOL));

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
}
