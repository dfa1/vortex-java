package io.github.dfa1.vortex.writer;

import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.VortexReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/// Rust's file writer keeps integers out of the data cascade (`IntDictScheme` is excluded) because
/// its `DictStrategy` dictionary-encodes suitable columns as a layout. Our counterpart is the global
/// dictionary, so the cascade may only drop the per-chunk integer dictionary while that is on.
class DictCascadeScopeTest {

    private static final ColumnName CODE = ColumnName.of("code");

    @TempDir
    Path tmp;

    @Test
    void withoutTheGlobalDictionary_aLowCardinalityIntegerColumnStillGetsADictionary() throws IOException {
        // Given: ten distinct values over 200,000 shuffled rows, as a streaming CSV import writes
        // them (it cannot buffer a column, so it turns the global dictionary off). Dropping the
        // per-chunk dictionary there left the column bit-packed at 23 bits per row: 4x the size
        long[] values = lowCardinality(200_000);
        WriteOptions options = WriteOptions.defaults().withGlobalDict(false).withZoneMaps(false);

        // When
        Path file = write(values, options);

        // Then
        try (VortexReader reader = VortexReader.open(file, ReadRegistry.loadAll())) {
            assertThat(reader.footer().arraySpecs()).contains(EncodingId.VORTEX_DICT);
        }
        assertThat(Files.size(file)).isLessThan(200_000L);
    }

    @Test
    void withTheGlobalDictionary_theColumnIsStillDictionaryEncoded() throws IOException {
        // Given: the same column under the default options, where the global dictionary takes it
        long[] values = lowCardinality(200_000);
        WriteOptions options = WriteOptions.defaults().withZoneMaps(false);

        // When
        Path file = write(values, options);

        // Then
        assertThat(Files.size(file)).isLessThan(200_000L);
    }

    private Path write(long[] values, WriteOptions options) throws IOException {
        Path file = tmp.resolve("code.vtx");
        var schema = new DType.Struct(List.of(CODE), List.of(DType.I64), false);
        try (var channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(channel, schema, options)) {
            sut.writeChunk(Map.of(CODE, values));
        }
        return file;
    }

    private static long[] lowCardinality(int rows) {
        long[] pool = {10, 20, 30, 40, 50, 60, 70, 80, 1_000_003, 5_000_011};
        Random random = new Random(5);
        long[] values = new long[rows];
        for (int i = 0; i < rows; i++) {
            values[i] = pool[random.nextInt(pool.length)];
        }
        return values;
    }
}
