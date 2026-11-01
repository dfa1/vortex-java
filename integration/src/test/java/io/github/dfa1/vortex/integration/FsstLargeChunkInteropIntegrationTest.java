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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

/// An FSST column whose compressed codes pass 2 GiB in one chunk writes and reads back (#629): the
/// code offsets go 64-bit, as Rust's `fsst_compress` switches to `i64` offsets past `i32::MAX`; the
/// segment, whose length is a `u32` on the wire, is written past a `ByteBuffer`'s 2 GiB and read
/// back as one. Found on Raincloud's `peoples-speech-clean-validation`, whose audio column is
/// ~2.3 GB in one 8192-row block.
///
/// FSST only wins on bytes it can shrink, so the rows compress slightly (a repeated word in a tenth
/// of their 8-byte slots, the rest bytes FSST codes one for one) and ~2.46 GB of input is needed;
/// every row shares one array to keep the heap small. Tagged `large`: several GB of memory.
@Tag("large")
class FsstLargeChunkInteropIntegrationTest {

    private static final Session SESSION = Session.create();
    private static final BufferAllocator ALLOCATOR = ArrowAllocation.rootAllocator();
    private static final ColumnName BLOB = ColumnName.of("blob");
    /// One 8192-row block: the writer's repartition never splits it.
    private static final int ROWS = 8192;
    private static final int ROW_BYTES = 300_000;

    static {
        NativeLoader.loadJni();
    }

    @Test
    void codesPastTwoGibibytes_roundTripAndJniReads(@TempDir Path tmp) throws Exception {
        // Given
        byte[] row = slightlyCompressibleRow();
        byte[][] rows = new byte[ROWS][];
        Arrays.fill(rows, row);
        Path file = tmp.resolve("fsst.vortex");
        WriteOptions options = WriteOptions.defaults()
                .withColumnEncoding(BLOB, ColumnEncoding.candidates(EncodingId.VORTEX_FSST));

        // When
        DType.Struct schema = new DType.Struct(List.of(BLOB), List.of(new DType.Binary(false)), false);
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             VortexWriter writer = VortexWriter.create(channel, schema, options)) {
            writer.writeChunk(Map.of(BLOB, rows));
        }

        // Then — FSST was kept and its codes passed 2 GiB; the last rows, whose codes start past it,
        // decode to the input in vortex-jni and vortex-java
        assertThat(file.toFile().length()).isGreaterThan(Integer.MAX_VALUE).isLessThan((long) ROWS * ROW_BYTES);
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
