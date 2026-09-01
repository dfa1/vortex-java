package io.github.dfa1.vortex.integration;

import java.net.URI;
import java.nio.file.Path;

/// Single source of truth for the `vortex-compat-fixtures` S3 bucket: the pinned snapshot
/// version, the derived array-fixture base URL, and the shared download/cache wrapper every
/// fixture-based integration test needs.
///
/// Update [#VERSION] here — not per test class — when the Rust reference bumps. Before this
/// class existed, three test classes each hardcoded their own copy of the version and the
/// bucket host, which meant a version bump had to be found-and-replaced three times with no
/// compiler check that all three agreed.
final class RustFixtures {

    /// The `vortex-compat-fixtures` S3 snapshot these tests pull from. Keep in sync with
    /// `vortex-jni.version` in the root POM: a skew between the two means the live JNI
    /// writer/reader and the static Rust fixtures are exercising different wire formats, so a
    /// wire-format change in between would go uncaught by the fixture-based tests.
    static final String VERSION = "v0.85.0";

    private static final String BUCKET_HOST = "https://vortex-compat-fixtures.s3.amazonaws.com";
    private static final String ARRAYS_BASE = BUCKET_HOST + "/" + VERSION + "/arrays/";
    private static final Path CACHE_DIR = Path.of("/tmp/vortex-compat-fixtures", VERSION);

    private RustFixtures() {
    }

    /// The download URL for a fixture file under the pinned snapshot's `arrays/` prefix.
    ///
    /// @param name fixture file name (e.g. `"primitives.vortex"`)
    /// @return the fixture's S3 URL
    static URI arrayUri(String name) {
        return URI.create(ARRAYS_BASE + name);
    }

    /// Downloads a fixture into `tmp` if not already present in the shared local cache.
    ///
    /// @param tmp  the test's `@TempDir`, used as the download destination on a cache miss
    /// @param name fixture file name
    /// @return the local path to the fixture: the cached copy, or the freshly downloaded one
    /// @throws Exception if the download fails for a reason other than a transient 5xx
    static Path downloadArray(Path tmp, String name) throws Exception {
        return LocalHttpCache.downloadIfMissing(tmp, CACHE_DIR, arrayUri(name), name);
    }

    /// Skips the running test (via `assumeTrue`) if the fixture bucket cannot be reached at all.
    static void assumeNetworkAvailable() {
        LocalHttpCache.assumeNetworkAvailable(URI.create(BUCKET_HOST));
    }
}
