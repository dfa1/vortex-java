package io.github.dfa1.vortex.writer;

import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.DTypes;
import jdk.jfr.Recording;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// Recording is observation only: a write with every event enabled produces the same bytes.
class JfrByteIdentityTest {

    private static final ColumnName ID = ColumnName.of("id");
    private static final ColumnName NAME = ColumnName.of("name");
    private static final DType.Struct SCHEMA =
            new DType.Struct(List.of(ID, NAME), List.of(DTypes.I64, DTypes.UTF8), false);

    @Test
    void write_withAllEventsRecording_isByteIdentical(@TempDir Path tmp) throws IOException {
        // Given
        long[] ids = new long[20_000];
        String[] names = new String[ids.length];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = 1_000L + i / 100;
            names[i] = "name-" + (i % 37);
        }
        Path quiet = write(tmp.resolve("quiet.vtx"), ids, names);

        // When
        Path recorded;
        try (var recording = new Recording()) {
            for (String event : List.of("Compress", "Candidate", "Winner", "SegmentEncoded", "SegmentWait")) {
                recording.enable("io.github.dfa1.vortex." + event);
            }
            recording.start();
            recorded = write(tmp.resolve("recorded.vtx"), ids, names);
        }

        // Then
        assertThat(Files.readAllBytes(recorded)).isEqualTo(Files.readAllBytes(quiet));
    }

    private static Path write(Path file, long[] ids, String[] names) throws IOException {
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, SCHEMA, WriteOptions.defaults())) {
            sut.writeChunk(Map.of(ID, ids, NAME, names));
        }
        return file;
    }
}
