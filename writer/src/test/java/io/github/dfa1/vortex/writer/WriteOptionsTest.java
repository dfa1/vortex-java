package io.github.dfa1.vortex.writer;

import io.github.dfa1.vortex.core.model.MemorySize;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// Unit tests for [WriteOptions] factories and copy-methods.
class WriteOptionsTest {

    // The hardcoded default; both factories must keep supplying it so the budget stays 2 GB unless a
    // caller overrides it via withGlobalDictMaxRetainedBytes(...). Raised from 256 MB when buffering
    // became cardinality-bounded (ADR 0021), then from 1 GB (#303) so wide high-cardinality files
    // keep their high-cardinality columns globally dictionaried instead of evicting them to per-chunk.
    private static final MemorySize DEFAULT_BUDGET = MemorySize.ofGiB(2);

    @Test
    void defaults_globalDictMaxRetainedBytes_is2Gb() {
        // Given / When
        WriteOptions result = WriteOptions.defaults();

        // Then
        assertThat(result.globalDictMaxRetainedBytes()).isEqualTo(DEFAULT_BUDGET);
    }

    @Test
    void cascading_globalDictMaxRetainedBytes_is2Gb() {
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
        WriteOptions result = base.withGlobalDictMaxRetainedBytes(new MemorySize(120_000));

        // Then — only the budget changes; every other component is copied unchanged.
        assertThat(result.globalDictMaxRetainedBytes()).isEqualTo(new MemorySize(120_000));
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
        WriteOptions result = base.withGlobalDictMaxRetainedBytes(new MemorySize(1));

        // Then — records are immutable; the copy-method must not mutate the original.
        assertThat(result).isNotSameAs(base);
        assertThat(base.globalDictMaxRetainedBytes()).isEqualTo(DEFAULT_BUDGET);
    }
}
