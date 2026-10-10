package io.github.dfa1.vortex.writer;

import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.reader.ScanOptions;
import io.github.dfa1.vortex.reader.VortexReader;
import io.github.dfa1.vortex.reader.array.Float16Array;
import io.github.dfa1.vortex.reader.array.LazyConstantFloat16Array;
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

import static org.assertj.core.api.Assertions.assertThat;

/// #515: a constant F16 column hit `vortex.constant: unsupported ptype: F16` on write, because
/// the constant encoder and decoder had no F16 case.
class ConstantF16ColumnTest {

    private static final ColumnName COLUMN = ColumnName.of("h");
    private static final DType.Struct SCHEMA = new DType.Struct(List.of(COLUMN),
            List.of(new DType.Primitive(PType.F16, false)), false);

    @TempDir
    Path tmp;

    @Test
    void constantF16Column_roundTripsAsALazyConstant() throws IOException {
        // Given 1000 rows of 1.5, which the default cascade picks vortex.constant for
        short[] data = new short[1000];
        Arrays.fill(data, Float.floatToFloat16(1.5f));
        Path file = tmp.resolve("f16.vtx");
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, SCHEMA, WriteOptions.defaults())) {
            sut.writeChunk(Map.of(COLUMN, data));
        }

        // When
        List<Float16Array> result = new ArrayList<>();
        try (VortexReader reader = VortexReader.open(file)) {
            reader.scan(ScanOptions.all()).forEachRemaining(c -> result.add(c.column(COLUMN)));
        }

        // Then the column stayed one metadata-only constant, not 1000 stored halves
        assertThat(result).hasSize(1);
        assertThat(result.getFirst()).isInstanceOf(LazyConstantFloat16Array.class);
        assertThat(result.getFirst().length()).isEqualTo(1000L);
        assertThat(result.getFirst().getFloat(999)).isEqualTo(1.5f);
    }
}
