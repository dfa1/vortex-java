package io.github.dfa1.vortex.writer;

import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.ScanOptions;
import io.github.dfa1.vortex.reader.VortexReader;
import io.github.dfa1.vortex.reader.array.LongArray;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SequenceCascadeTest {

    private static final ColumnName TIME = ColumnName.of("time");

    @TempDir
    Path tmp;

    @Test
    void anArithmeticSequenceIsWrittenAsVortexSequence() throws IOException {
        // Given: one row per minute, as an exchange's bar timestamps. Rust's SequenceScheme scores
        // an exact sequence at len / 2, so it beats frame-of-reference + bit-packing (~33 bits per
        // row here); without it in the default cascade the column cost 17 MB instead of a few bytes
        long[] times = new long[131_072];
        for (int i = 0; i < times.length; i++) {
            times[i] = 1_704_067_200_000L + i * 60_000L;
        }

        // When
        Path file = write(times);

        // Then
        try (VortexReader reader = VortexReader.open(file, ReadRegistry.loadAll())) {
            assertThat(reader.footer().arraySpecs()).contains(EncodingId.VORTEX_SEQUENCE);
            assertThat(readLongs(reader)).containsExactly(times);
        }
    }

    @Test
    void aNearSequenceIsNotWrittenAsVortexSequence() throws IOException {
        // Given: a sequence with a value off in every 1000 rows, so no repartitioned chunk of the
        // column is an exact sequence (one stray value would leave the other chunks exact)
        long[] times = new long[131_072];
        for (int i = 0; i < times.length; i++) {
            times[i] = 1_704_067_200_000L + i * 60_000L + (i % 1000 == 999 ? 1 : 0);
        }

        // When
        Path file = write(times);

        // Then
        try (VortexReader reader = VortexReader.open(file, ReadRegistry.loadAll())) {
            assertThat(reader.footer().arraySpecs()).doesNotContain(EncodingId.VORTEX_SEQUENCE);
            assertThat(readLongs(reader)).containsExactly(times);
        }
    }

    private Path write(long[] times) throws IOException {
        Path file = tmp.resolve("time.vtx");
        var schema = new DType.Struct(List.of(TIME), List.of(DType.I64), false);
        try (var channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(channel, schema, WriteOptions.defaults().withZoneMaps(false))) {
            sut.writeChunk(Map.of(TIME, times));
        }
        return file;
    }

    private static long[] readLongs(VortexReader reader) {
        List<Long> out = new java.util.ArrayList<>();
        try (var iter = reader.scan(ScanOptions.all())) {
            iter.forEachRemaining(chunk -> {
                LongArray column = chunk.column(TIME);
                for (long i = 0; i < column.length(); i++) {
                    out.add(column.getLong(i));
                }
            });
        }
        return out.stream().mapToLong(Long::longValue).toArray();
    }
}
