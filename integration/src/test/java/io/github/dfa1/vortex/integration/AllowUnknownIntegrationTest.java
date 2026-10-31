package io.github.dfa1.vortex.integration;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.reader.Chunk;
import io.github.dfa1.vortex.reader.array.UnknownArray;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.VortexReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Verifies Registry.allowUnknown() passthrough behavior end-to-end
/// using a JNI-written file whose encodings are deliberately absent from the registry.
class AllowUnknownIntegrationTest {

    /// Scans the whole file, discarding chunks — used to trigger decode errors from a single call.
    private static void scanAll(Path file, ReadRegistry registry) throws IOException {
        try (VortexReader vf = VortexReader.open(file, registry);
             var iter = vf.scan(io.github.dfa1.vortex.reader.ScanOptions.all())) {
            // a chunk decodes a column when it is read, so read them all
            iter.forEachRemaining(Chunk::columns);
        }
    }

    @Test
    void allowUnknown_emptyRegistry_allColumnsReturnUnknownArray(@TempDir Path tmp) throws IOException {
        // Given — JNI writes a real file; empty registry has no decoders
        Path file = tmp.resolve("test.vtx");
        JniFiles.writeIdsAndRandomValues(file, 10_000);

        // When
        var totalRows = new AtomicLong();
        var allUnknown = new AtomicBoolean(true);
        var chunkCount = new AtomicLong();
        try (VortexReader vf = VortexReader.open(file, ReadRegistry.builder().allowUnknown().build());
             var iter = vf.scan(io.github.dfa1.vortex.reader.ScanOptions.all())) {
            iter.forEachRemaining(c -> {
                totalRows.addAndGet(c.rowCount());
                chunkCount.incrementAndGet();
                for (Chunk.Column col : c.columns().values()) {
                    if (!(col.array() instanceof UnknownArray)) {
                        allUnknown.set(false);
                    }
                }
            });
        }

        // Then — every column value is UnknownArray; file was readable without throwing
        assertThat(chunkCount).hasPositiveValue();
        assertThat(totalRows).hasValue(10_000L);
        assertThat(allUnknown).describedAs("every column must be UnknownArray").isTrue();
    }

    @Test
    void strictMode_emptyRegistry_throwsVortexException(@TempDir Path tmp) throws IOException {
        // Given
        Path file = tmp.resolve("test.vtx");
        JniFiles.writeIdsAndRandomValues(file, 1_000);
        ReadRegistry emptyRegistry = ReadRegistry.empty();

        // When / Then — strict mode throws rather than returning UnknownArray
        assertThatThrownBy(() -> scanAll(file, emptyRegistry))
                .isInstanceOf(VortexException.class);
    }

    @Test
    void allowUnknown_loadAllRegistry_noUnknownArrayForSupportedEncodings(@TempDir Path tmp) throws IOException {
        // Given — loadAll() has decoders for everything the JNI writer produces
        Path file = tmp.resolve("test.vtx");
        JniFiles.writeIdsAndRandomValues(file, 10_000);

        // When
        var chunkCount = new AtomicLong();
        var anyUnknown = new AtomicBoolean(false);
        try (VortexReader vf = VortexReader.open(file,
                ReadRegistry.builder().registerDefaults().allowUnknown().build());
             var iter = vf.scan(io.github.dfa1.vortex.reader.ScanOptions.all())) {
            iter.forEachRemaining(c -> {
                chunkCount.incrementAndGet();
                for (Chunk.Column col : c.columns().values()) {
                    if (col.array() instanceof UnknownArray) {
                        anyUnknown.set(true);
                    }
                }
            });
        }

        // Then — allowUnknown() is a no-op when all encodings are registered; no UnknownArray
        assertThat(chunkCount).hasPositiveValue();
        assertThat(anyUnknown).describedAs("fully-supported file should not produce UnknownArray").isFalse();
    }
}
