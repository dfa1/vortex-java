package io.github.dfa1.vortex.writer;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// Unit tests for [WriteOptions] factories and copy-methods.
class WriteOptionsTest {

    // The hardcoded default; both factories must keep supplying it so the budget stays 1 GB unless a
    // caller overrides it via withGlobalDictMaxRetainedBytes(...). Raised from 256 MB when buffering
    // became cardinality-bounded (ADR 0021): the budget now guards ~2 B/row code arrays, not raw
    // values, so a larger budget bounds the same risk while keeping wide low-cardinality files dicted.
    private static final long DEFAULT_BUDGET = 1024L * 1024 * 1024;

    @Test
    void defaults_globalDictMaxRetainedBytes_is1Gb() {
        // Given / When
        WriteOptions result = WriteOptions.defaults();

        // Then
        assertThat(result.globalDictMaxRetainedBytes()).isEqualTo(DEFAULT_BUDGET);
    }

    @Test
    void cascading_globalDictMaxRetainedBytes_is1Gb() {
        // Given / When
        WriteOptions result = WriteOptions.cascading(3);

        // Then
        assertThat(result.globalDictMaxRetainedBytes()).isEqualTo(DEFAULT_BUDGET);
    }

    @Test
    void withGlobalDictMaxRetainedBytes_changesOnlyThatField() {
        // Given
        WriteOptions base = WriteOptions.defaults();

        // When
        WriteOptions result = base.withGlobalDictMaxRetainedBytes(120_000L);

        // Then — only the budget changes; every other component is copied unchanged.
        assertThat(result.globalDictMaxRetainedBytes()).isEqualTo(120_000L);
        assertThat(result.chunkSize()).isEqualTo(base.chunkSize());
        assertThat(result.enableZoneMaps()).isEqualTo(base.enableZoneMaps());
        assertThat(result.compressionRatioThreshold()).isEqualTo(base.compressionRatioThreshold());
        assertThat(result.allowedCascading()).isEqualTo(base.allowedCascading());
        assertThat(result.globalDict()).isEqualTo(base.globalDict());
        assertThat(result.enableZstd()).isEqualTo(base.enableZstd());
    }

    @Test
    void withGlobalDictMaxRetainedBytes_returnsNewInstance() {
        // Given
        WriteOptions base = WriteOptions.defaults();

        // When
        WriteOptions result = base.withGlobalDictMaxRetainedBytes(1L);

        // Then — records are immutable; the copy-method must not mutate the original.
        assertThat(result).isNotSameAs(base);
        assertThat(base.globalDictMaxRetainedBytes()).isEqualTo(DEFAULT_BUDGET);
    }
}
