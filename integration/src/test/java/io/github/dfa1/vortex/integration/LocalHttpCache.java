package io.github.dfa1.vortex.integration;

import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Shared fixture-download-and-cache helper for integration tests that pull test data
/// from S3/GitHub over HTTP: check a local cache first, download on miss, and skip
/// (rather than fail) the test on transient server errors or no network.
final class LocalHttpCache {

    private LocalHttpCache() {
    }

    /// Downloads `url` into `tmp` if `cacheDir` doesn't already have a file named `name`,
    /// leaving no copy in `cacheDir` (callers that want the download persisted for reuse by
    /// later runs must pass a `cacheDir` that already exists and is writable — this method
    /// only reads from it).
    ///
    /// A 5xx response skips the test via `assumeTrue` (assumed transient infrastructure
    /// noise, not a real signal); a 4xx or other `IOException` fails it (the fixture is
    /// genuinely missing or broken).
    ///
    /// @param tmp      the test's `@TempDir`, used as the download destination when the
    ///                 fixture isn't already in `cacheDir`
    /// @param cacheDir persistent local cache directory (e.g. `/tmp/pco-fixtures/v0.75.0`)
    /// @param url      the fixture's download URL
    /// @param name     the fixture's file name, used for both the cache lookup and dest file
    /// @return the local path to the fixture: the cached copy, or the freshly downloaded one
    /// @throws Exception if the download fails for a reason other than a transient 5xx
    static Path downloadIfMissing(Path tmp, Path cacheDir, URI url, String name) throws Exception {
        Path cached = cacheDir.resolve(name);
        if (Files.exists(cached)) {
            return cached;
        }
        Path dest = tmp.resolve(name);
        var conn = (HttpURLConnection) url.toURL().openConnection();
        int code = conn.getResponseCode();
        assumeTrue(code < 500, () -> "transient server error " + code + " for " + name);
        try (var in = conn.getInputStream()) {
            Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
        }
        return dest;
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
