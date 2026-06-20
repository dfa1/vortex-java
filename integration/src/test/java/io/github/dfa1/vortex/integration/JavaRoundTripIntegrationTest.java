package io.github.dfa1.vortex.integration;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.ScanOptions;
import io.github.dfa1.vortex.reader.VortexReader;
import io.github.dfa1.vortex.reader.array.IntArray;
import io.github.dfa1.vortex.writer.VortexWriter;
import io.github.dfa1.vortex.writer.WriteOptions;
import io.github.dfa1.vortex.writer.encode.PatchedEncodingEncoder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// Java writer → Java reader round-trips for encodings the bundled `vortex-jni` build cannot read
/// back, so they have no Java→Rust coverage. This is still a real cross-module integration test: it
/// drives the writer's encode, the on-disk file format, and the reader's decode end to end.
///
/// `vortex.patched` is the case here — the JNI reader rejects a standalone patched array with
/// "Unknown encoding: vortex.patched", so the round-trip is asserted on the Java side instead.
class JavaRoundTripIntegrationTest {

    private static final DType.Struct I32_SCHEMA = new DType.Struct(
            List.of("v"),
            List.of(new DType.Primitive(PType.I32, false)),
            false);

    @Test
    void patched_i32_javaWriteJavaRead(@TempDir Path tmp) throws IOException {
        // Given — most values fit ~6 bits; four large outliers (< n/20) force the patch path
        // (base inner array + patch index / patch value children). Non-negative, so the bit width
        // is computed from the value itself rather than sign-extension.
        Path file = tmp.resolve("java_patched_i32.vtx");
        int[] data = new int[120];
        for (int i = 0; i < data.length; i++) {
            data[i] = i % 50;
        }
        data[7] = 5_000_000;
        data[23] = 6_000_000;
        data[61] = 7_000_000;
        data[88] = 8_000_000;
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, I32_SCHEMA, WriteOptions.defaults(),
                     List.of(new PatchedEncodingEncoder()))) {
            // When
            sut.writeChunk(Map.of("v", data));
        }

        // Then — the Java reader reconstructs base values + patched outliers exactly
        int[] decoded = readIntColumn(file, "v");
        assertThat(decoded).containsExactly(data);
    }

    private static int[] readIntColumn(Path file, String column) throws IOException {
        try (var vf = VortexReader.open(file, ReadRegistry.loadAll());
             var iter = vf.scan(ScanOptions.columns(column))) {
            var ints = new ArrayList<Integer>();
            iter.forEachRemaining(c -> {
                IntArray arr = (IntArray) c.columns().get(column);
                for (long i = 0; i < arr.length(); i++) {
                    ints.add(arr.getInt(i));
                }
            });
            return ints.stream().mapToInt(Integer::intValue).toArray();
        }
    }
}
