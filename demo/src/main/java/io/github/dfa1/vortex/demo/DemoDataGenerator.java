package io.github.dfa1.vortex.demo;

import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.writer.VortexWriter;
import io.github.dfa1.vortex.writer.WriteOptions;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Random;

/// Generates a synthetic tick-style dataset — `timestamp`/`symbol`/`price`/`volume` — sorted by
/// symbol so each chunk is dominated by a single symbol.
///
/// That clustering is what makes [HttpRangeDemo]'s single-symbol filter dramatic: zone-map
/// pruning skips whole chunks whose stats can't match, so [RangeAwareFileServer] only ever
/// receives range requests for the handful of chunks holding the requested symbol. A real-world
/// archive partitioned or sorted by instrument (a common layout for exactly this kind of data)
/// behaves the same way.
final class DemoDataGenerator {

    static final String TIMESTAMP = "timestamp";
    static final String SYMBOL = "symbol";
    static final String PRICE = "price";
    static final String VOLUME = "volume";

    private static final long BASE_TIMESTAMP_MILLIS = 1_700_000_000_000L;
    private static final long SEED = 42L;

    private DemoDataGenerator() {
    }

    /// Writes `rowCount` rows spread evenly across `symbolCount` symbols to `out`, in chunks of
    /// `chunkSize` rows.
    ///
    /// @param out         destination path; created or truncated
    /// @param rowCount    total rows to generate
    /// @param symbolCount distinct symbols; rows are grouped by symbol, in order
    /// @param chunkSize   rows per written chunk
    /// @throws IOException if writing the file fails
    static void generate(Path out, long rowCount, int symbolCount, int chunkSize) throws IOException {
        DType.Struct schema = DType.structBuilder()
                .field(TIMESTAMP, DType.I64)
                .field(SYMBOL, DType.UTF8)
                .field(PRICE, DType.F64)
                .field(VOLUME, DType.I64)
                .build();

        ColumnName timestampCol = ColumnName.of(TIMESTAMP);
        ColumnName symbolCol = ColumnName.of(SYMBOL);
        ColumnName priceCol = ColumnName.of(PRICE);
        ColumnName volumeCol = ColumnName.of(VOLUME);

        String[] symbols = new String[symbolCount];
        for (int i = 0; i < symbolCount; i++) {
            symbols[i] = "SYM%03d".formatted(i);
        }
        long rowsPerSymbol = Math.max(1, rowCount / symbolCount);

        // globalDict(false): the default global dictionary pulls a low-cardinality Utf8 column's
        // codes out of the normal per-chunk cascade, and the per-chunk embedded stats
        // RowFilter-driven zone-map pruning reads (ScanIterator#canPruneChunk) go unpopulated for
        // those codes — the *separate* per-zone stats table (ScanIterator#columnZoneStats) stays
        // correct either way, but pruning doesn't consult it. With the default globalDict=true,
        // this demo's single-symbol filter provably matched zero chunks. Disabling it here keeps
        // "symbol" in the ordinary per-chunk dict cascade, which does emit per-chunk min/max.
        Random random = new Random(SEED);
        try (FileChannel channel = FileChannel.open(out, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
             VortexWriter writer = VortexWriter.create(channel, schema, WriteOptions.cascading(3).withGlobalDict(false))) {

            long written = 0;
            int symbolIndex = 0;
            long rowInSymbol = 0;
            while (written < rowCount) {
                int n = (int) Math.min(chunkSize, rowCount - written);
                long[] timestamps = new long[n];
                String[] symbolValues = new String[n];
                double[] prices = new double[n];
                long[] volumes = new long[n];
                for (int i = 0; i < n; i++) {
                    if (rowInSymbol >= rowsPerSymbol && symbolIndex < symbolCount - 1) {
                        symbolIndex++;
                        rowInSymbol = 0;
                    }
                    double basePrice = 50 + symbolIndex * 3.7;
                    timestamps[i] = BASE_TIMESTAMP_MILLIS + (written + i) * 1000L;
                    symbolValues[i] = symbols[symbolIndex];
                    prices[i] = basePrice + random.nextDouble() * 2 - 1;
                    volumes[i] = 100 + random.nextInt(10_000);
                    rowInSymbol++;
                }
                writer.writeChunk(c -> c.put(timestampCol, timestamps)
                        .put(symbolCol, symbolValues)
                        .put(priceCol, prices)
                        .put(volumeCol, volumes));
                written += n;
            }
        }
    }
}
