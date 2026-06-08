package io.github.dfa1.vortex.io;

import io.github.dfa1.vortex.scan.ScanOptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Integration test: reads real-world Vortex files from local paths (pre-downloaded fixtures).
///
/// Run with: mvn test -pl reader -Dtest=RealWorldDataIT -Dvortex.fixtures.dir=/path/to/fixtures
///
/// Skipped automatically when the fixtures directory is not set or files are missing.
@Tag("integration")
class RealWorldDataIT {

    private static final String FIXTURES_DIR = System.getProperty("vortex.fixtures.dir");

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "clickbench_hits_5k.regular.vortex",
            "clickbench_hits_5k.compact.vortex",
            "tpch_lineitem.regular.vortex",
            "tpch_lineitem.compact.vortex",
            "tpch_orders.regular.vortex",
            "tpch_orders.compact.vortex",
    })
    void scan_realWorldFile_decodesAllRows(String filename) throws Exception {
        // Given
        assumeTrue(FIXTURES_DIR != null, "set -Dvortex.fixtures.dir to enable");
        Path file = Path.of(FIXTURES_DIR, filename);
        assumeTrue(file.toFile().exists(), "fixture not found: " + file);

        // When
        long totalRows = 0;
        int chunks = 0;
        try (var sut = VortexReader.open(file);
             var iter = sut.scan(ScanOptions.all())) {
            while (iter.hasNext()) {
                try (var c = iter.next()) {
                    totalRows += c.rowCount();
                    chunks++;
                }
            }
        }

        // Then
        assertThat(totalRows).as("rowCount for %s", filename).isGreaterThan(0);
        assertThat(chunks).as("chunkCount for %s", filename).isGreaterThan(0);
    }
}
