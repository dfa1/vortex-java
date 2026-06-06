package io.github.dfa1.vortex.writer;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.encoding.DictEncoding;
import io.github.dfa1.vortex.encoding.EncodingRegistry;
import io.github.dfa1.vortex.encoding.PrimitiveEncoding;
import io.github.dfa1.vortex.io.VortexReader;
import io.github.dfa1.vortex.scan.ScanOptions;
import io.github.dfa1.vortex.scan.ScanResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DictEncodingTest {

    private static final DType.Struct SCHEMA = new DType.Struct(
            List.of("category"),
            List.of(new DType.Primitive(PType.I32, false)),
            false);

    private static List<ScanResult> scanAll(VortexReader vf, ScanOptions opts) throws IOException {
        var results = new ArrayList<ScanResult>();
        var iter = vf.scan(opts);
        while (iter.hasNext()) {
            results.add(iter.next());
        }
        return results;
    }

    private static EncodingRegistry dictRegistry() {
        var registry = EncodingRegistry.empty();
        registry.register(new DictEncoding());
        registry.register(new PrimitiveEncoding());
        return registry;
    }

    @Test
    void roundTrip_lowCardinality_valuesExpandCorrectly(@TempDir Path tmp) throws IOException {
        // Given
        Path file = tmp.resolve("dict.vtx");
        int[] data = {1, 2, 1, 3, 2, 1};

        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, SCHEMA, WriteOptions.defaults(),
                     List.of(new DictEncoding()))) {
            // When
            sut.writeChunk(Map.of("category", data));
        }

        // Then
        var registry = dictRegistry();
        try (var vf = VortexReader.open(file, registry)) {
            List<ScanResult> results = scanAll(vf, ScanOptions.all());
            assertThat(results).hasSize(1);
            Array arr = results.getFirst().columns().get("category");
            assertThat(arr.length()).isEqualTo(6L);
            var layout = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
            var buf = arr.buffer(0);
            assertThat(buf.get(layout, 0)).isEqualTo(1);
            assertThat(buf.get(layout, 4)).isEqualTo(2);
            assertThat(buf.get(layout, 8)).isEqualTo(1);
            assertThat(buf.get(layout, 12)).isEqualTo(3);
            assertThat(buf.get(layout, 16)).isEqualTo(2);
            assertThat(buf.get(layout, 20)).isEqualTo(1);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    @Test
    void roundTrip_singleUniqueValue_u8Codes(@TempDir Path tmp) throws IOException {
        // Given — all same value, dict has 1 entry, codes are U8(0)
        Path file = tmp.resolve("dict_single.vtx");
        int[] data = {42, 42, 42, 42};

        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, SCHEMA, WriteOptions.defaults(),
                     List.of(new DictEncoding()))) {
            // When
            sut.writeChunk(Map.of("category", data));
        }

        // Then
        var registry = dictRegistry();
        try (var vf = VortexReader.open(file, registry)) {
            List<ScanResult> results = scanAll(vf, ScanOptions.all());
            assertThat(results).hasSize(1);
            Array arr = results.getFirst().columns().get("category");
            assertThat(arr.length()).isEqualTo(4L);
            var layout = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
            var buf = arr.buffer(0);
            for (int i = 0; i < 4; i++) {
                assertThat(buf.get(layout, (long) i * 4)).isEqualTo(42);
            }
        }
    }

    @Test
    void roundTrip_multipleChunks(@TempDir Path tmp) throws IOException {
        // Given
        Path file = tmp.resolve("dict_multi.vtx");
        int[] chunk1 = {10, 20, 10};
        int[] chunk2 = {30, 10, 20, 30};

        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, SCHEMA, WriteOptions.defaults(),
                     List.of(new DictEncoding()))) {
            // When
            sut.writeChunk(Map.of("category", chunk1));
            sut.writeChunk(Map.of("category", chunk2));
        }

        // Then — process each chunk before advancing; hasNext() closes the previous chunk's arena
        try (var vf = VortexReader.open(file, dictRegistry());
             var iter = vf.scan(ScanOptions.all())) {
            var layout = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

            assertThat(iter.hasNext()).isTrue();
            Array a1 = iter.next().columns().get("category");
            assertThat(a1.length()).isEqualTo(3L);
            assertThat(a1.buffer(0).get(layout, 0)).isEqualTo(10);
            assertThat(a1.buffer(0).get(layout, 4)).isEqualTo(20);
            assertThat(a1.buffer(0).get(layout, 8)).isEqualTo(10);

            assertThat(iter.hasNext()).isTrue();
            Array a2 = iter.next().columns().get("category");
            assertThat(a2.length()).isEqualTo(4L);
            assertThat(a2.buffer(0).get(layout, 0)).isEqualTo(30);
            assertThat(a2.buffer(0).get(layout, 4)).isEqualTo(10);
            assertThat(a2.buffer(0).get(layout, 8)).isEqualTo(20);
            assertThat(a2.buffer(0).get(layout, 12)).isEqualTo(30);

            assertThat(iter.hasNext()).isFalse();
        }
    }
}
