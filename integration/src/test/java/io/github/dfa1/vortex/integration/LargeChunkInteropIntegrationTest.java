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
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.reader.Chunk;
import io.github.dfa1.vortex.reader.ScanIterator;
import io.github.dfa1.vortex.reader.VortexReader;
import io.github.dfa1.vortex.reader.array.VarBinArray;
import io.github.dfa1.vortex.writer.ColumnEncoding;
import io.github.dfa1.vortex.writer.VortexWriter;
import io.github.dfa1.vortex.writer.WriteOptions;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.ipc.ArrowReader;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/// A binary column whose bytes pass 2 GiB in one chunk writes and reads back in every encoding that
/// can hold it: FSST's code offsets go 64-bit as Rust's `fsst_compress` does past `i32::MAX`, and the
/// segment, a `u32` length on the wire, is written and read past a `ByteBuffer`'s 2 GiB (#629);
/// `vortex.varbin` copies its rows per row past 2 GiB, with `i64` offsets; `vortex.varbinview`
/// spreads its rows over data buffers below 2 GiB each; `vortex.zstd` sums its length-prefixed
/// payload in a `long` and closes a frame before 1 GiB (#633). Found on Raincloud's
/// `peoples-speech-clean-validation` (2.3 GB of audio in one 8192-row block) and `mmmu` (2.95 GB of
/// images).
///
/// Not covered here: reading Rust's `u32` varbin offsets past 2 GiB (`mmmu`). Java writes `i64`
/// offsets, and a Rust-written fixture that size is too big to commit, so `VarBinArrayTest` pins the
/// unsigned read and Raincloud's `mmmu` is its cross-check.
///
/// Each case forces one encoding on ~2.46 GB of slightly compressible rows (a repeated word in a tenth
/// of their 8-byte slots, the rest bytes FSST codes one for one), so FSST and zstd both win and keep
/// their encoding; every row shares one array to keep the heap small. Tagged `large`: several GB of
/// memory.
@Tag("large")
class LargeChunkInteropIntegrationTest {

    private static final Session SESSION = Session.create();
    private static final BufferAllocator ALLOCATOR = ArrowAllocation.rootAllocator();
    private static final ColumnName BLOB = ColumnName.of("blob");
    /// One 8192-row block: the writer's repartition never splits it.
    private static final int ROWS = 8192;
    private static final int ROW_BYTES = 300_000;

    static {
        NativeLoader.loadJni();
    }

    @ParameterizedTest
    @ValueSource(strings = {"vortex.fsst", "vortex.varbin", "vortex.varbinview", "vortex.zstd"})
    void chunkPastTwoGibibytes_roundTripsAndJniReads(String encoding, @TempDir Path tmp) throws Exception {
        // Given
        byte[] row = slightlyCompressibleRow();
        byte[][] rows = new byte[ROWS][];
        Arrays.fill(rows, row);
        Path file = tmp.resolve("large.vortex");
        EncodingId id = EncodingId.parse(encoding);
        WriteOptions options = WriteOptions.defaults().withColumnEncoding(BLOB, ColumnEncoding.candidates(id));

        // When
        DType.Struct schema = new DType.Struct(List.of(BLOB), List.of(new DType.Binary(false)), false);
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             VortexWriter writer = VortexWriter.create(channel, schema, options)) {
            writer.writeChunk(Map.of(BLOB, rows));
        }

        // Then — the forced encoding was kept, and the last rows, whose bytes start past 2 GiB,
        // decode to the input in vortex-jni and vortex-java
        assertThat(dataEncoding(file)).isEqualTo(encoding);
        if (id != EncodingId.VORTEX_ZSTD) {
            // zstd shrinks the repeated row to almost nothing; its 2 GiB is the payload it compresses
            assertThat(file.toFile().length()).isGreaterThan(Integer.MAX_VALUE);
        }
        assertThat(readTailWithJni(file, ROWS - 4, ROWS)).hasSize(4).allSatisfy(b -> assertThat(b).isEqualTo(row));
        assertThat(readLastWithJava(file)).isEqualTo(row);
    }

    private static byte[] slightlyCompressibleRow() {
        byte[] word = "AUDIOHDR".getBytes(StandardCharsets.US_ASCII);
        Random random = new Random(629);
        byte[] row = new byte[ROW_BYTES];
        for (int at = 0; at < ROW_BYTES; at += word.length) {
            if (random.nextInt(10) == 0) {
                System.arraycopy(word, 0, row, at, word.length);
            } else {
                for (int i = at; i < at + word.length; i++) {
                    row[i] = (byte) random.nextInt(200);
                }
            }
        }
        return row;
    }

    /// The root encoding of the blob column's data: the array under its zoned layout's first flat.
    private static String dataEncoding(Path file) throws IOException {
        List<String> tree = EncodingTrees.of(file);
        int flat = tree.indexOf("    vortex.flat");
        assertThat(flat).as("the blob column's data flat in %s", tree).isPositive();
        return tree.get(flat + 1).strip();
    }

    private static List<byte[]> readTailWithJni(Path file, long begin, long end) throws IOException {
        ScanOptions opts = ScanOptions.builder()
                .projection(Expression.select(new String[]{"blob"}, Expression.root()))
                .rowRangeBegin(begin)
                .rowRangeEnd(end)
                .build();
        List<byte[]> out = new ArrayList<>();
        DataSource ds = DataSource.open(SESSION, file.toAbsolutePath().toUri().toString());
        Scan scan = ds.scan(opts);
        while (scan.hasNext()) {
            Partition partition = scan.next();
            try (ArrowReader reader = partition.scanArrow(ALLOCATOR)) {
                while (reader.loadNextBatch()) {
                    VectorSchemaRoot root = reader.getVectorSchemaRoot();
                    FieldVector vec = root.getVector("blob");
                    for (int i = 0; i < root.getRowCount(); i++) {
                        out.add((byte[]) vec.getObject(i));
                    }
                }
            }
        }
        return out;
    }

    private static byte[] readLastWithJava(Path file) throws IOException {
        try (VortexReader reader = VortexReader.open(file);
             ScanIterator iter = reader.scan(io.github.dfa1.vortex.reader.ScanOptions.all());
             Chunk chunk = iter.next()) {
            VarBinArray blob = chunk.column(BLOB);
            return blob.getBytes(chunk.rowCount() - 1);
        }
    }
}
