package io.github.dfa1.vortex.it;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.encoding.DecoderRegistry;
import io.github.dfa1.vortex.io.VortexFile;
import io.github.dfa1.vortex.scan.ScanOptions;
import io.github.dfa1.vortex.scan.ScanResult;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// Cross-compatibility: Rust (JNI) writer → Java reader.
///
/// Tests are disabled until the JNI vortex bindings are available.
/// See TODO.md #8 and the commented-out `vortex-jni` dep in `it/pom.xml`.
@Disabled("blocked by JNI bindings — see TODO.md #8")
class RustWritesJavaReadsIT {

    private static final DType.Struct SCHEMA = new DType.Struct(
        List.of("id", "value"),
        List.of(new DType.Primitive(PType.I64, false),
                new DType.Primitive(PType.F64, false)),
        false);

    @Test
    void rustWriter_javaReader_singleChunk(@TempDir Path tmp) throws IOException {
        // Given
        Path   file   = tmp.resolve("rust_written.vtx");
        long[] ids    = {1L, 2L, 3L};
        double[] vals = {1.1, 2.2, 3.3};

        // TODO: replace with JNI call once bindings are available
        // VortexJni.write(file, SCHEMA, Map.of("id", ids, "value", vals));
        throw new UnsupportedOperationException("JNI writer not available");

        // When / Then
        // try (var vf = VortexFile.open(file, DecoderRegistry.loadAll())) {
        //     List<ScanResult> results = scanAll(vf, ScanOptions.all());
        //     assertThat(results).hasSize(1);
        //     assertThat(results.get(0).rowCount()).isEqualTo(3L);
        //     assertThat(results.get(0).columns()).containsKeys("id", "value");
        // }
    }

    @Test
    void rustWriter_javaReader_multipleChunks(@TempDir Path tmp) throws IOException {
        // Given
        Path file = tmp.resolve("rust_written_multi.vtx");

        // TODO: write two chunks via JNI
        // VortexJni.write(file, SCHEMA, chunk1, chunk2);
        throw new UnsupportedOperationException("JNI writer not available");

        // When / Then
        // try (var vf = VortexFile.open(file, DecoderRegistry.loadAll())) {
        //     List<ScanResult> results = scanAll(vf, ScanOptions.all());
        //     assertThat(results).hasSize(2);
        // }
    }

    @Test
    void rustWriter_javaReader_columnProjection(@TempDir Path tmp) throws IOException {
        // Given
        Path file = tmp.resolve("rust_written_proj.vtx");

        // TODO: write via JNI
        throw new UnsupportedOperationException("JNI writer not available");

        // When / Then
        // try (var vf = VortexFile.open(file, DecoderRegistry.loadAll())) {
        //     List<ScanResult> results = scanAll(vf, ScanOptions.columns("id"));
        //     assertThat(results.get(0).columns()).containsKey("id");
        //     assertThat(results.get(0).columns()).doesNotContainKey("value");
        // }
    }

    private static List<ScanResult> scanAll(VortexFile vf, ScanOptions opts) throws IOException {
        var results = new ArrayList<ScanResult>();
        var iter    = vf.scan(opts);
        while (iter.hasNext()) {
            results.add(iter.next());
        }
        return results;
    }
}
