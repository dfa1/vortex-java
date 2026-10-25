package io.github.dfa1.vortex.cli;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static io.github.dfa1.vortex.cli.CliTestSupport.capture;
import static io.github.dfa1.vortex.cli.CliTestSupport.writeTypedVortex;
import static org.assertj.core.api.Assertions.assertThat;

class ExploreCommandTest {

    @Test
    void wrongArity_returnsUsageError() {
        // Given / When
        CliTestSupport.Captured result = capture(() -> ExploreCommand.run(new String[]{"explore"}));

        // Then
        assertThat(result.status()).isEqualTo(ExitStatus.USAGE_ERROR);
        assertThat(result.stderr()).contains("usage: explore");
    }

    @Test
    void missingFile_returnsFileNotFound(@TempDir Path tmp) {
        // Given
        Path missing = tmp.resolve("nope.vortex");

        // When
        CliTestSupport.Captured result = capture(() -> ExploreCommand.run(new String[]{"explore", missing.toString()}));

        // Then — fails before any server starts
        assertThat(result.status()).isEqualTo(ExitStatus.FILE_NOT_FOUND);
        assertThat(result.stderr()).contains("file not found");
    }

    @ParameterizedTest
    @ValueSource(strings = {"--html", "--bogus"})
    void flagWithoutItsArguments_returnsUsageError(String flag) {
        // Given / When — `explore --html data.vortex` would otherwise write the page over data.vortex
        CliTestSupport.Captured result = capture(() -> ExploreCommand.run(new String[]{"explore", flag, "data.vortex"}));

        // Then
        assertThat(result.status()).isEqualTo(ExitStatus.USAGE_ERROR);
        assertThat(result.stderr()).contains("usage: explore");
    }

    @Test
    void html_writesEveryColumnAlreadyAnalyzed(@TempDir Path tmp) throws IOException {
        // Given — a 3-row file of id (I64), qty (I32), price (F64), name (Utf8)
        Path file = writeTypedVortex(tmp, "typed.vortex");
        Path out = tmp.resolve("report.html");

        // When
        CliTestSupport.Captured result = capture(() ->
                ExploreCommand.run(new String[]{"explore", "--html", out.toString(), file.toString()}));

        // Then — one file to publish: no server to ask, so no Analyze button, every column's
        // distribution in place, each behind a working Hide toggle
        assertThat(result.status()).isEqualTo(ExitStatus.OK);
        assertThat(result.stdout()).contains("written: ").contains("4 columns analyzed");
        String page = Files.readString(out);
        assertThat(page)
                .startsWith("<!doctype html>")
                .doesNotContain(">Analyze</button>")
                .doesNotContain("id=\"analyze-all\"")
                .contains("total <code>6</code>")        // id: 1 + 2 + 3
                .contains("total <code>60</code>")       // qty: 10 + 20 + 30
                .contains("<code>carol</code>");          // name: each of 3 values counted
        assertThat(page.split("data-state=\"done\">Hide</button>", -1)).hasSize(5);
    }

    @Test
    void html_missingFile_returnsFileNotFoundAndWritesNothing(@TempDir Path tmp) {
        // Given
        Path out = tmp.resolve("report.html");

        // When
        CliTestSupport.Captured result = capture(() -> ExploreCommand.run(
                new String[]{"explore", "--html", out.toString(), tmp.resolve("nope.vortex").toString()}));

        // Then
        assertThat(result.status()).isEqualTo(ExitStatus.FILE_NOT_FOUND);
        assertThat(out).doesNotExist();
    }

    @Test
    void serve_answersOnlyTheSecretPath() throws IOException, InterruptedException {
        // Given — the page shows file contents: a local process or a web page probing localhost
        // ports must not reach it without the random path
        HttpServer sut = ExploreCommand.serve("<!doctype html><p>hello</p>", "/s3cret/", 2, index -> "column " + index);
        try (HttpClient client = HttpClient.newHttpClient()) {
            // When
            HttpResponse<String> result = get(client, sut, "/s3cret/");
            HttpResponse<String> root = get(client, sut, "/");
            HttpResponse<String> guess = get(client, sut, "/s3cret/x");
            HttpResponse<String> columnWithoutSecret = get(client, sut, "/column/0");

            // Then
            assertThat(result.statusCode()).isEqualTo(200);
            assertThat(result.body()).contains("hello");
            assertThat(result.headers().firstValue("Content-Type")).hasValue("text/html; charset=utf-8");
            assertThat(root.statusCode()).isEqualTo(404);
            assertThat(guess.statusCode()).isEqualTo(404);
            assertThat(columnWithoutSecret.statusCode()).isEqualTo(404);
        } finally {
            sut.stop(0);
        }
    }

    @Test
    void serve_columnPathReturnsThatColumnsAnalysis() throws IOException, InterruptedException {
        // Given
        HttpServer sut = ExploreCommand.serve("", "/s/", 2, index -> "<p>column " + index + "</p>");
        try (HttpClient client = HttpClient.newHttpClient()) {
            // When
            HttpResponse<String> result = get(client, sut, "/s/column/1");

            // Then
            assertThat(result.statusCode()).isEqualTo(200);
            assertThat(result.body()).isEqualTo("<p>column 1</p>");
        } finally {
            sut.stop(0);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/s/column/2", "/s/column/-1", "/s/column/", "/s/column/x", "/s/column/99999999999"})
    void serve_columnOutOfRangeOrMalformed_is404(String path) throws IOException, InterruptedException {
        // Given — the index comes from the request: anything but a column the file has must not
        // reach the analysis (an out-of-range index would be an IndexOutOfBoundsException there)
        HttpServer sut = ExploreCommand.serve("", "/s/", 2, index -> "column " + index);
        try (HttpClient client = HttpClient.newHttpClient()) {
            // When
            HttpResponse<String> result = get(client, sut, path);

            // Then
            assertThat(result.statusCode()).isEqualTo(404);
        } finally {
            sut.stop(0);
        }
    }

    @Test
    void serve_analyzesColumnsConcurrently() throws IOException, InterruptedException {
        // Given — each analysis waits until both are running: on the server's default single
        // thread the first would wait forever for the second, and "Analyze all" would run one
        // column at a time however many readers there are
        CountDownLatch bothRunning = new CountDownLatch(2);
        HttpServer sut = ExploreCommand.serve("", "/s/", 2, index -> {
            bothRunning.countDown();
            try {
                return bothRunning.await(10, TimeUnit.SECONDS) ? "together" : "alone";
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "interrupted";
            }
        });
        try (HttpClient client = HttpClient.newHttpClient()) {
            // When
            CompletableFuture<HttpResponse<String>> first = client.sendAsync(request(sut, "/s/column/0"),
                    HttpResponse.BodyHandlers.ofString());
            CompletableFuture<HttpResponse<String>> second = client.sendAsync(request(sut, "/s/column/1"),
                    HttpResponse.BodyHandlers.ofString());
            List<String> result = List.of(first.join().body(), second.join().body());

            // Then
            assertThat(result).containsExactly("together", "together");
        } finally {
            sut.stop(0);
        }
    }

    @Test
    void serve_failedAnalysisIs500WithTheReason() throws IOException, InterruptedException {
        // Given — a malformed segment surfaces when the column is decoded, long after the page loaded
        HttpServer sut = ExploreCommand.serve("", "/s/", 1, index -> {
            throw new IllegalStateException("bad segment");
        });
        try (HttpClient client = HttpClient.newHttpClient()) {
            // When
            HttpResponse<String> result = get(client, sut, "/s/column/0");

            // Then — the server keeps serving; the page shows the reason in place of the chart
            assertThat(result.statusCode()).isEqualTo(500);
            assertThat(result.body()).contains("bad segment");
            assertThat(result.headers().firstValue("Content-Type")).hasValue("text/plain; charset=utf-8");
        } finally {
            sut.stop(0);
        }
    }

    @Test
    void serve_bindsLoopbackOnly() throws IOException {
        // Given / When
        HttpServer result = ExploreCommand.serve("", "/x/", 0, index -> "");

        // Then — never reachable from another machine on the network
        try {
            assertThat(result.getAddress().getAddress().isLoopbackAddress()).isTrue();
        } finally {
            result.stop(0);
        }
    }

    private static HttpResponse<String> get(HttpClient client, HttpServer server, String path)
            throws IOException, InterruptedException {
        return client.send(request(server, path), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpRequest request(HttpServer server, String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path)).build();
    }
}
