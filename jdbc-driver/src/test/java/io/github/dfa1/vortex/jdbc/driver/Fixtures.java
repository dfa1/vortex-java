package io.github.dfa1.vortex.jdbc.driver;

import io.github.dfa1.vortex.core.model.Editions;
import io.github.dfa1.vortex.core.testing.OhlcData;
import io.github.dfa1.vortex.writer.VortexWriter;
import io.github.dfa1.vortex.writer.WriteOptions;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/// Writes a small single-chunk OHLC Vortex file for the driver tests.
final class Fixtures {

    private Fixtures() {
    }

    static void write(Path file, int rows) throws IOException {
        WriteOptions options = WriteOptions.cascading(0).withEdition(Editions.CORE_2025_10_0);
        try (var channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var writer = VortexWriter.create(channel, OhlcData.SCHEMA, options)) {
            for (OhlcData.Batch batch : OhlcData.generate(rows, rows)) {
                writer.writeChunk(batch.columns());
            }
        }
    }
}
