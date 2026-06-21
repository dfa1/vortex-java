package io.github.dfa1.vortex.reader;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Unit coverage for the [VortexHttpReader#open(URI, ReadRegistry)] overload, which wires the
/// shared default [java.net.http.HttpClient]. A non-HTTP URI makes the request builder reject
/// the scheme before any socket is opened, so the delegation runs without real network I/O.
class VortexHttpReaderOpenOverloadTest {

    @Test
    void open_uriAndRegistry_delegatesToDefaultClient() {
        // Given a URI whose scheme HttpRequest.newBuilder rejects (no network performed)
        URI uri = URI.create("ftp://example.invalid/file.vortex");

        // When / Then the two-arg overload runs and surfaces the rejection
        assertThatThrownBy(() -> VortexHttpReader.open(uri, ReadRegistry.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
