package io.github.dfa1.vortex.calcite;

import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.testing.OhlcData;
import io.github.dfa1.vortex.writer.VortexWriter;
import io.github.dfa1.vortex.writer.WriteOptions;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

/// Writes the shared [OhlcData] batches to a Vortex file with the in-house Java writer, using a
/// small chunk size so the file carries several zones (making zone-map push-down visible).
final class OhlcGenerator {

    private OhlcGenerator() {
    }

    static void write(Path file, int totalRows, int chunkSize) throws IOException {
        WriteOptions opts = new WriteOptions(chunkSize, true, 0.90, 0, true, false, 256L * 1024 * 1024, Map.of());
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var writer = VortexWriter.create(ch, OhlcData.SCHEMA, opts)) {
            for (OhlcData.Batch batch : OhlcData.generate(totalRows, chunkSize)) {
                Map<ColumnName, Object> columns = new LinkedHashMap<>();
                columns.put(ColumnName.of("date"), batch.date());
                columns.put(ColumnName.of("symbol"), batch.symbol());
                columns.put(ColumnName.of("open"), batch.open());
                columns.put(ColumnName.of("high"), batch.high());
                columns.put(ColumnName.of("low"), batch.low());
                columns.put(ColumnName.of("close"), batch.close());
                columns.put(ColumnName.of("volume"), batch.volume());
                writer.writeChunk(columns);
            }
        }
    }
}
