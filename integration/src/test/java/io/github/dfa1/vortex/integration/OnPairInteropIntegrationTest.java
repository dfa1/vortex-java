package io.github.dfa1.vortex.integration;

import dev.vortex.api.DataSource;
import dev.vortex.api.Expression;
import dev.vortex.api.Partition;
import dev.vortex.api.Scan;
import dev.vortex.api.ScanOptions;
import dev.vortex.api.Session;
import dev.vortex.arrow.ArrowAllocation;
import dev.vortex.jni.NativeLoader;
import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.inspect.InspectorTree;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.VortexReader;
import io.github.dfa1.vortex.writer.VortexWriter;
import io.github.dfa1.vortex.writer.WriteOptions;
import io.github.dfa1.vortex.writer.encode.OnPairEncodingEncoder;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.ipc.ArrowReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/// vortex-java writes `vortex.onpair` (edition `core2026.08.1`, so default writes may pick it), vortex-jni reads it back (#425).
///
/// Beyond full scans, a pushed-down string equality runs Rust's compressed-domain compare kernel,
/// which tokenizes the needle with its own greedy longest-match and compares codes: it returns the
/// right rows only if the Java encoder segmented every row exactly that way over a sorted, complete
/// dictionary. A row range slices the codes window, which a full scan never exercises.
class OnPairInteropIntegrationTest {

    private static final Session SESSION = Session.create();
    private static final BufferAllocator ALLOCATOR = ArrowAllocation.rootAllocator();
    private static final DType.Struct SCHEMA = new DType.Struct(
            List.of(ColumnName.of("s")), List.of(DType.UTF8), false);

    static {
        NativeLoader.loadJni();
    }

    @Test
    void forcedOnPair_jniReadsFullFilteredAndRowRange(@TempDir Path tmp) throws IOException {
        // Given — OnPair as the only encoder, so the column is guaranteed to be vortex.onpair
        Path file = tmp.resolve("java_onpair.vtx");
        String[] data = urls(5_000, 1);
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, SCHEMA,
                     WriteOptions.defaults(),
                     List.of(new OnPairEncodingEncoder()))) {
            // When
            sut.writeChunk(Map.of(ColumnName.of("s"), data));
        }

        // Then
        assertThat(usedEncodings(file)).contains("vortex.onpair");
        assertJniMatches(file, data);
    }

    @Test
    void cascadingWithUnstableEdition_picksOnPair_jniReadsFullFilteredAndRowRange(@TempDir Path tmp)
            throws IOException {
        // Given — several chunks through the cascade: OnPair competes with FSST/dict/varbin on size
        // and its integer children are cascaded (bit-packed, FoR) independently.
        Path file = tmp.resolve("java_onpair_cascade.vtx");
        List<String[]> chunks = List.of(urls(4_000, 2), urls(4_000, 3), urls(1_234, 4));
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, SCHEMA,
                     WriteOptions.cascading(3))) {
            // When
            for (String[] chunk : chunks) {
                sut.writeChunk(Map.of(ColumnName.of("s"), chunk));
            }
        }

        // Then
        assertThat(usedEncodings(file)).contains("vortex.onpair");
        assertJniMatches(file, chunks.stream().flatMap(Arrays::stream).toArray(String[]::new));
    }

    @Test
    void cascadingWithAnEditionBeforeOnPair_neverPicksOnPair(@TempDir Path tmp) throws IOException {
        // Given — same data, targeting core2026.08.0, the edition just before OnPair joined core
        Path file = tmp.resolve("java_no_onpair.vtx");
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, SCHEMA,
                     WriteOptions.cascading(3).withEdition(io.github.dfa1.vortex.core.model.Editions.CORE_2026_08_0))) {
            // When
            sut.writeChunk(Map.of(ColumnName.of("s"), urls(4_000, 2)));
        }

        // Then
        assertThat(usedEncodings(file)).doesNotContain("vortex.onpair");
    }

    private static void assertJniMatches(Path file, String[] data) throws IOException {
        assertThat(read(file, ScanOptions.of())).containsExactlyInAnyOrder(data);

        String needle = data[data.length / 2];
        long expectedHits = Arrays.stream(data).filter(needle::equals).count();
        List<String> filtered = read(file, ScanOptions.builder()
                .filter(Expression.binary(Expression.BinaryOp.EQ, Expression.column("s"), Expression.literal(needle)))
                .build());
        assertThat(filtered).hasSize((int) expectedHits).containsOnly(needle);

        int begin = 1_501;
        int end = 3_333;
        List<String> range = read(file, ScanOptions.builder().rowRangeBegin(begin).rowRangeEnd(end).build());
        assertThat(range).containsExactlyInAnyOrder(Arrays.copyOfRange(data, begin, end));
    }

    /// URL-like strings over a small vocabulary: repetitive enough for OnPair to train merges and
    /// beat the other string encodings, with a few empty and multi-byte UTF-8 rows mixed in.
    private static String[] urls(int n, long seed) {
        Random random = new Random(seed);
        String[] hosts = {"example.com", "shop.example.org", "città.it", "data.example.net"};
        return IntStream.range(0, n)
                .mapToObj(i -> i % 97 == 0 ? ""
                        : "https://" + hosts[random.nextInt(hosts.length)] + "/item/" + random.nextInt(200)
                        + "?ref=" + (random.nextBoolean() ? "home" : "search"))
                .toArray(String[]::new);
    }

    private static List<String> read(Path file, ScanOptions opts) throws IOException {
        var out = new ArrayList<String>();
        Scan scan = DataSource.open(SESSION, file.toAbsolutePath().toUri().toString()).scan(opts);
        while (scan.hasNext()) {
            Partition partition = scan.next();
            try (ArrowReader reader = partition.scanArrow(ALLOCATOR)) {
                while (reader.loadNextBatch()) {
                    VectorSchemaRoot root = reader.getVectorSchemaRoot();
                    var vec = root.getVector("s");
                    for (int i = 0; i < root.getRowCount(); i++) {
                        out.add(vec.getObject(i).toString());
                    }
                }
            }
        }
        return out;
    }

    private static List<String> usedEncodings(Path file) throws IOException {
        try (VortexReader reader = VortexReader.open(file, ReadRegistry.loadAll())) {
            return new ArrayList<>(InspectorTree.build(reader).usedEncodings());
        }
    }
}
