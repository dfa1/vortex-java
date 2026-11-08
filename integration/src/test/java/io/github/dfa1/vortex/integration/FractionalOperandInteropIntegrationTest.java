package io.github.dfa1.vortex.integration;

import dev.vortex.api.DataSource;
import dev.vortex.api.Expression;
import dev.vortex.api.Partition;
import dev.vortex.api.Scan;
import dev.vortex.api.ScanOptions;
import dev.vortex.api.Session;
import dev.vortex.arrow.ArrowAllocation;
import dev.vortex.jni.NativeLoader;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.DTypes;
import io.github.dfa1.vortex.reader.Chunk;
import io.github.dfa1.vortex.reader.ScanFilter;
import io.github.dfa1.vortex.reader.ScanIterator;
import io.github.dfa1.vortex.reader.VortexReader;
import io.github.dfa1.vortex.writer.WriteOptions;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.ipc.ArrowReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// A fractional operand against an integer column (#701). vortex-java truncated it to a long, so
/// `v >= 1.5` matched `v = 1`. Rust's compare requires both sides to share a dtype and refuses an
/// `i64` column against an `f64` literal; both readers must now refuse it.
class FractionalOperandInteropIntegrationTest {

    private static final Session SESSION = Session.create();
    private static final BufferAllocator ALLOCATOR = ArrowAllocation.rootAllocator();
    private static final ColumnName V = ColumnName.of("v");

    static {
        NativeLoader.loadJni();
    }

    @Test
    void fractionalOperandOnIntegerColumn_bothReadersReject(@TempDir Path tmp) throws Exception {
        // Given — 0, 1, 2, 3: 1.5 falls between two values, so a truncating compare would select
        // rows instead of failing
        Path file = tmp.resolve("ints.vortex");
        DType.Struct schema = DType.structBuilder().field(V, DTypes.I64).build();
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var w = io.github.dfa1.vortex.writer.VortexWriter.create(ch, schema, WriteOptions.defaults())) {
            w.writeChunk(Map.of(V, new long[]{0, 1, 2, 3}));
        }

        // When / Then
        assertThatThrownBy(() -> scanJni(file))
                .as("vortex-jni")
                .hasMessageContaining("Cannot compare different DTypes i64 and f64");
        assertThatThrownBy(() -> scanJava(file))
                .as("vortex-java")
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("an integer column compares only with an integer value");
    }

    private static void scanJava(Path file) throws Exception {
        try (VortexReader reader = VortexReader.open(file);
             ScanIterator scan = reader.scan(io.github.dfa1.vortex.reader.ScanOptions.all()
                     .withFilter(ScanFilter.gte(V, 1.5)))) {
            while (scan.hasNext()) {
                try (Chunk chunk = scan.next()) {
                    chunk.columns();
                }
            }
        }
    }

    private static void scanJni(Path file) throws Exception {
        ScanOptions opts = ScanOptions.builder()
                .projection(Expression.select(new String[]{"v"}, Expression.root()))
                .filter(Expression.binary(Expression.BinaryOp.GTE, Expression.column("v"), Expression.literal(1.5)))
                .build();
        DataSource ds = DataSource.open(SESSION, file.toAbsolutePath().toUri().toString());
        Scan scan = ds.scan(opts);
        while (scan.hasNext()) {
            Partition partition = scan.next();
            try (ArrowReader reader = partition.scanArrow(ALLOCATOR)) {
                while (reader.loadNextBatch()) {
                    reader.getVectorSchemaRoot();
                }
            }
        }
    }
}
