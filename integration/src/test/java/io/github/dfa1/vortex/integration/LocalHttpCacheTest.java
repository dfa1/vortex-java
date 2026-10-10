package io.github.dfa1.vortex.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentest4j.TestAbortedException;

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// CI lost one run in ten to `Socket Connection reset` from S3 in the fixture-reading integration
/// tests: the download had no retry and no timeout, treated only an HTTP 5xx as noise, and kept
/// nothing, so every test class fetched every fixture again. These pin the new behavior with a
/// scripted connector, so no network and no real pause is involved.
class LocalHttpCacheTest {

    private static final URI URL = URI.create("https://example.invalid/fixture.vortex");
    private static final byte[] BODY = "vortex".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path tmp;

    @Test
    void connectionReset_isRetriedAndThenSucceeds() throws Exception {
        // Given two resets followed by a good response
        Script script = new Script(reset(), reset(), ok());

        // When
        Path result = LocalHttpCache.downloadIfMissing(tmp, tmp.resolve("cache"), URL, "f.vortex",
                script, Duration.ZERO);

        // Then the third attempt wins, and its bytes are kept in the cache for the next test
        assertThat(script.attempts()).hasValue(3);
        assertThat(result).isEqualTo(tmp.resolve("cache").resolve("f.vortex")).hasBinaryContent(BODY);
    }

    @Test
    void everyAttemptReset_skipsTheTestInsteadOfFailingIt() {
        // Given
        Script script = new Script(reset(), reset(), reset());

        // When / Then infrastructure noise must not turn the build red
        assertThatThrownBy(() -> LocalHttpCache.downloadIfMissing(tmp, tmp.resolve("cache"), URL, "f.vortex",
                script, Duration.ZERO))
                .isInstanceOf(TestAbortedException.class)
                .hasMessageContaining("after 3 attempts")
                .hasMessageContaining("Connection reset");
        assertThat(script.attempts()).hasValue(3);
    }

    @Test
    void serverError_isRetriedThenSkipped() {
        // Given
        Script script = new Script(status(503), status(502), status(500));

        // When / Then
        assertThatThrownBy(() -> LocalHttpCache.downloadIfMissing(tmp, tmp.resolve("cache"), URL, "f.vortex",
                script, Duration.ZERO))
                .isInstanceOf(TestAbortedException.class)
                .hasMessageContaining("HTTP 500");
        assertThat(script.attempts()).hasValue(3);
    }

    @Test
    void missingFixture_failsAtOnceWithoutRetry() {
        // Given a 404: the fixture is genuinely absent, which is a signal, not noise
        Script script = new Script(missing());

        // When / Then
        assertThatThrownBy(() -> LocalHttpCache.downloadIfMissing(tmp, tmp.resolve("cache"), URL, "f.vortex",
                script, Duration.ZERO))
                .isInstanceOf(FileNotFoundException.class);
        assertThat(script.attempts()).hasValue(1);
    }

    @Test
    void resetHalfwayThroughTheBody_isRetriedAndLeavesNoPartialFile() throws Exception {
        // Given the first attempt dies after sending part of the body
        Path cache = tmp.resolve("cache");
        Script script = new Script(truncated(), ok());

        // When
        Path result = LocalHttpCache.downloadIfMissing(tmp, cache, URL, "f.vortex", script, Duration.ZERO);

        // Then the cache holds the complete file and nothing else
        assertThat(result).hasBinaryContent(BODY);
        try (Stream<Path> files = Files.list(cache)) {
            assertThat(files.map(p -> p.getFileName().toString()).toList()).containsExactly("f.vortex");
        }
    }

    @Test
    void cachedFixture_isReturnedWithoutTouchingTheNetwork() throws Exception {
        // Given a fixture an earlier test already downloaded
        Path cache = Files.createDirectories(tmp.resolve("cache"));
        Files.write(cache.resolve("f.vortex"), BODY);
        Script script = new Script();

        // When
        Path result = LocalHttpCache.downloadIfMissing(tmp, cache, URL, "f.vortex", script, Duration.ZERO);

        // Then
        assertThat(result).isEqualTo(cache.resolve("f.vortex"));
        assertThat(script.attempts()).hasValue(0);
    }

    @Test
    void unwritableCacheDirectory_fallsBackToTheTestsTempDir() throws Exception {
        // Given a cache "directory" that sits under a regular file, so it can never be created
        Path blocker = Files.write(tmp.resolve("blocker"), BODY);
        Path impossible = blocker.resolve("cache");
        Path testTmp = Files.createDirectories(tmp.resolve("test-tmp"));

        // When
        Path result = LocalHttpCache.downloadIfMissing(testTmp, impossible, URL, "f.vortex",
                new Script(ok()), Duration.ZERO);

        // Then
        assertThat(result).isEqualTo(testTmp.resolve("f.vortex")).hasBinaryContent(BODY);
    }

    // ── scripted connections ──────────────────────────────────────────────────

    /// One scripted attempt: what the connection does when the download asks for the status or the body.
    private interface Step {
        int status() throws IOException;

        InputStream body() throws IOException;
    }

    private static Step reset() {
        return new Step() {
            @Override
            public int status() throws IOException {
                throw new SocketException("Connection reset");
            }

            @Override
            public InputStream body() {
                throw new AssertionError("no body after a reset");
            }
        };
    }

    private static Step ok() {
        return new Step() {
            @Override
            public int status() {
                return 200;
            }

            @Override
            public InputStream body() {
                return new ByteArrayInputStream(BODY);
            }
        };
    }

    private static Step status(int code) {
        return new Step() {
            @Override
            public int status() {
                return code;
            }

            @Override
            public InputStream body() {
                throw new AssertionError("no body for a 5xx");
            }
        };
    }

    private static Step missing() {
        return new Step() {
            @Override
            public int status() {
                return 404;
            }

            @Override
            public InputStream body() throws IOException {
                throw new FileNotFoundException(URL.toString());
            }
        };
    }

    private static Step truncated() {
        return new Step() {
            @Override
            public int status() {
                return 200;
            }

            @Override
            public InputStream body() {
                return new InputStream() {
                    private int sent;

                    @Override
                    public int read() throws IOException {
                        if (sent == 2) {
                            throw new SocketException("Connection reset");
                        }
                        return BODY[sent++];
                    }
                };
            }
        };
    }

    /// Hands out the scripted steps in order, one per attempt, and counts the attempts.
    private static final class Script implements LocalHttpCache.Connector {
        private final Deque<Step> steps;
        private final AtomicInteger attempts = new AtomicInteger();

        Script(Step... steps) {
            this.steps = new ArrayDeque<>(List.of(steps));
        }

        AtomicInteger attempts() {
            return attempts;
        }

        @Override
        public HttpURLConnection open(URI url) {
            attempts.incrementAndGet();
            Step step = steps.removeFirst();
            return new HttpURLConnection(toUrl(url)) {
                @Override
                public int getResponseCode() throws IOException {
                    return step.status();
                }

                @Override
                public InputStream getInputStream() throws IOException {
                    return step.body();
                }

                @Override
                public void connect() {
                    // scripted: nothing to connect
                }

                @Override
                public void disconnect() {
                    // scripted: nothing to close
                }

                @Override
                public boolean usingProxy() {
                    return false;
                }
            };
        }

        private static URL toUrl(URI url) {
            try {
                return url.toURL();
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        }
    }
}
