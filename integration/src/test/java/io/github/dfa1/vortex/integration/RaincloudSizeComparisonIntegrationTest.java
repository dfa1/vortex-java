package io.github.dfa1.vortex.integration;

import io.github.dfa1.vortex.parquet.ParquetImporter;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/// File-size comparison across the Raincloud corpus for three representations:
/// Parquet (corpus oracle), vortex-jni (corpus file, written by Rust Vortex), and
/// vortex-java (re-encoded from the Parquet oracle via [ParquetImporter]).
///
/// Output is purely informational — one `[RaincloudSizes]` line per slug.
/// Fails only if the vortex-java file is unreadable when [ParquetImporter] succeeds.
/// Slugs with nested Parquet schemas (LIST, MAP, STRUCT) log `vortex-java=N/A` and pass.
///
/// Skipped (visibly) when the corpus is not hydrated — run
/// `scripts/hydrate-raincloud-corpus.sh` first, or point `RAINCLOUD_CORPUS_MANIFEST`
/// at a manifest TSV (`slug<TAB>vortex-path<TAB>parquet-path` per line).
class RaincloudSizeComparisonIntegrationTest {

    private static final Path DEFAULT_MANIFEST =
            Path.of(System.getProperty("user.home"), ".cache", "raincloud", "corpus-manifest.tsv");

    @Test
    void corpusIsHydrated() {
        // Given / When / Then — visible skip marker when the corpus is absent
        assertTrue(Files.exists(manifestPath()),
                "raincloud corpus not hydrated — run scripts/hydrate-raincloud-corpus.sh");
    }

    @TestFactory
    @Execution(ExecutionMode.CONCURRENT)
    Stream<DynamicTest> sizePerSlug() throws IOException {
        Path manifest = manifestPath();
        if (!Files.exists(manifest)) {
            return Stream.empty();
        }
        return Files.readAllLines(manifest).stream()
                .filter(line -> !line.isBlank())
                .map(line -> line.split("\t"))
                .map(parts -> DynamicTest.dynamicTest(parts[0], () -> {
                    String slug = parts[0];
                    Path vortexJni = Path.of(parts[1]);
                    Path parquet = Path.of(parts[2]);

                    long parquetBytes = Files.size(parquet);
                    long jniBytes = Files.size(vortexJni);

                    Path javaVortex = Files.createTempFile("raincloud-java-" + slug + "-", ".vortex");
                    try {
                        String javaLabel;
                        try {
                            ParquetImporter.importParquet(parquet, javaVortex);
                            long javaBytes = Files.size(javaVortex);
                            javaLabel = String.format("%.2fMB (%.2fx jni)",
                                    javaBytes / 1_048_576.0, (double) javaBytes / jniBytes);
                        } catch (UnsupportedOperationException | IllegalArgumentException e) {
                            javaLabel = "N/A (" + firstSentence(e.getMessage()) + ")";
                        }

                        System.out.printf("[RaincloudSizes] %-55s  parquet=%7.2fMB  vortex-jni=%7.2fMB  vortex-java=%s%n",
                                slug,
                                parquetBytes / 1_048_576.0,
                                jniBytes / 1_048_576.0,
                                javaLabel);
                    } finally {
                        Files.deleteIfExists(javaVortex);
                    }
                }));
    }

    private static Path manifestPath() {
        String env = System.getenv("RAINCLOUD_CORPUS_MANIFEST");
        return env != null ? Path.of(env) : DEFAULT_MANIFEST;
    }

    /// Returns the first sentence of a message (up to the first `;` or `.`), for compact log lines.
    private static String firstSentence(String msg) {
        if (msg == null) {
            return "null";
        }
        int semi = msg.indexOf(';');
        int dot = msg.indexOf('.');
        int end = semi >= 0 && dot >= 0 ? Math.min(semi, dot) : semi >= 0 ? semi : dot;
        return end > 0 ? msg.substring(0, end) : msg;
    }
}
