package io.github.dfa1.vortex.calcite;

import io.github.dfa1.vortex.core.model.Editions;
import io.github.dfa1.vortex.core.testing.OhlcData;
import io.github.dfa1.vortex.writer.VortexWriter;
import io.github.dfa1.vortex.writer.WriteOptions;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/// Writes the shared [OhlcData] batches to a Vortex file with the in-house Java writer, using a
/// small chunk size so the file carries several zones (making zone-map push-down visible).
final class OhlcGenerator {

    private OhlcGenerator() {
    }

    static void write(Path file, int totalRows, int chunkSize) throws IOException {
        WriteOptions opts = WriteOptions.cascading(0).withZoneMaps(true).withGlobalDict(true).withEdition(Editions.CORE_2025_10_0);
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var writer = VortexWriter.create(ch, OhlcData.SCHEMA, opts)) {
            for (OhlcData.Batch batch : OhlcData.generate(totalRows, chunkSize)) {
                writer.writeChunk(batch.columns());
            }
        }
    }
}
