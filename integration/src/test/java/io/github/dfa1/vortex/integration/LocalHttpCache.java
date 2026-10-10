package io.github.dfa1.vortex.integration;

import javax.net.ssl.SSLException;

import java.io.EOFException;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Shared fixture-download-and-cache helper for integration tests that pull test data
/// from S3/GitHub over HTTP: check a local cache first, download on miss, and skip
/// (rather than fail) the test on transient server errors or no network.
final class LocalHttpCache {

    private LocalHttpCache() {
    }

    /// How a download opens its connection; replaced in tests so retry behavior needs no network.
    @FunctionalInterface
    interface Connector {
        HttpURLConnection open(URI url) throws IOException;
    }

    private static final int ATTEMPTS = 3;
    private static final Duration BACKOFF = Duration.ofSeconds(2);
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    /// Returns the fixture `name`: the copy already in `cacheDir`, or a download of `url` that is
    /// kept there for the next test, so each fixture is fetched once per run instead of once per
    /// test class. A `cacheDir` that cannot be written falls back to a copy in `tmp`.
    ///
    /// Connection-level failures (a reset, a timeout, a TLS or EOF error) and 5xx responses are
    /// infrastructure noise: they are retried up to three times with a growing pause, and if every
    /// attempt fails the test is skipped via `assumeTrue`, not failed. A 4xx or any other
    /// `IOException` fails it (the fixture is genuinely missing or broken). On CI, S3 resets
    /// failed one run in ten before this retried.
    ///
    /// @param tmp      the test's `@TempDir`, the fallback destination when `cacheDir` is unwritable
    /// @param cacheDir persistent local cache directory (e.g. `/tmp/vortex-compat-fixtures/v0.86.1`)
    /// @param url      the fixture's download URL
    /// @param name     the fixture's file name, used for both the cache lookup and dest file
    /// @return the local path to the fixture: the cached copy, or the freshly downloaded one
    /// @throws Exception if the download fails for a reason other than transient noise
    static Path downloadIfMissing(Path tmp, Path cacheDir, URI url, String name) throws Exception {
        return downloadIfMissing(tmp, cacheDir, url, name, LocalHttpCache::open, BACKOFF);
    }

    static Path downloadIfMissing(Path tmp, Path cacheDir, URI url, String name, Connector connector,
                                  Duration backoff) throws Exception {
        Path cached = cacheDir.resolve(name);
        if (Files.exists(cached)) {
            return cached;
        }
        String lastFailure = "no attempt";
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                HttpURLConnection conn = connector.open(url);
                int code = conn.getResponseCode();
                if (code < 500) {
                    return save(conn, tmp, cacheDir, name);
                }
                lastFailure = "HTTP " + code;
            } catch (SocketException | SocketTimeoutException | SSLException | EOFException e) {
                lastFailure = e.toString();
            }
            if (attempt < ATTEMPTS) {
                Thread.sleep(backoff.multipliedBy(attempt));
            }
        }
        String reason = lastFailure;
        assumeTrue(false, () -> "could not download " + name + " after " + ATTEMPTS + " attempts: " + reason);
        throw new AssertionError("unreachable");
    }

    private static HttpURLConnection open(URI url) throws IOException {
        var conn = (HttpURLConnection) url.toURL().openConnection();
        conn.setConnectTimeout((int) TIMEOUT.toMillis());
        conn.setReadTimeout((int) TIMEOUT.toMillis());
        return conn;
    }

    /// Streams the body to a temporary file and moves it into place atomically, so a download cut
    /// short never leaves a truncated fixture that a later test would trust.
    private static Path save(HttpURLConnection conn, Path tmp, Path cacheDir, String name) throws IOException {
        Path dir;
        try {
            Files.createDirectories(cacheDir);
            dir = cacheDir;
        } catch (IOException _) {
            dir = tmp;
        }
        Path part = Files.createTempFile(dir, name, ".part");
        try (var in = conn.getInputStream()) {
            Files.copy(in, part, StandardCopyOption.REPLACE_EXISTING);
            Path dest = dir.resolve(name);
            Files.move(part, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return dest;
        } finally {
            Files.deleteIfExists(part);
        }
    }

    /// Like [#downloadIfMissing] but skips the test (rather than failing it) on ANY download
    /// failure, not just a 5xx — for endpoints known to rate-limit or block by egress IP
    /// (e.g. CloudFront blocking CI runners with a 403) where any failure is infrastructure
    /// noise rather than a genuine "fixture is broken" signal.
    ///
    /// @param tmp      the test's `@TempDir`, used as the download destination when the
    ///                 fixture isn't already in `cacheDir`
    /// @param cacheDir persistent local cache directory
    /// @param url      the fixture's download URL
    /// @param name     the fixture's file name, used for both the cache lookup and dest file
    /// @return the local path to the fixture: the cached copy, or the freshly downloaded one
    static Path downloadIfMissingOrSkip(Path tmp, Path cacheDir, URI url, String name) {
        try {
            return downloadIfMissing(tmp, cacheDir, url, name);
        } catch (Exception e) {
            assumeTrue(false, "could not download " + name + ": " + e.getMessage());
            throw new AssertionError("unreachable");
        }
    }

    /// Skips the running test (via `assumeTrue`) if `pingUrl` cannot be reached at all —
    /// distinguishes "no network" (skip, infrastructure) from "fixture missing" (fail, signal).
    ///
    /// @param pingUrl a URL known to be reachable whenever the test network is up
    static void assumeNetworkAvailable(URI pingUrl) {
        try {
            pingUrl.toURL().openStream().close();
        } catch (Exception _) {
            assumeTrue(false, "no network");
        }
    }
}
