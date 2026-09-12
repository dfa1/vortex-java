package io.github.dfa1.vortex.demo.fakedata;

import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.writer.VortexWriter;
import io.github.dfa1.vortex.writer.WriteOptions;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/// Generates a Vortex file of synthetic data from a list of [ColumnDescriptor]s. The library
/// entry point behind [FakeDataGeneratorCli]; callable directly by other code (e.g. a demo
/// module) without shelling out to the CLI.
///
/// Generation streams: each chunk is generated and written in turn, so memory stays bounded by
/// one chunk regardless of row count. There is no "sort by column" option — real data never
/// arrives pre-sorted by the column a query happens to filter on, so faking that clustering here
/// would misrepresent the workload a consumer of the generated file is actually meant to
/// exercise. A `series(start,step)` column is naturally ordered by row position without any
/// sorting, which is enough on its own for zone-map pruning on a time-range-style query — see
/// `vortex-demo`'s default filter.
public final class FakeDataGenerator {

    private FakeDataGenerator() {
    }

    /// @param columns   column descriptors, in schema order
    /// @param rows      total number of rows to generate
    /// @param seed      seed for the shared random source (deterministic across runs)
    /// @param chunkSize rows per written chunk
    /// @param cascading write compression cascade depth (see `WriteOptions#cascading`)
    /// @param out       destination path; created or truncated
    /// @throws IOException if writing fails
    public static void generate(List<ColumnDescriptor> columns, int rows, long seed,
            int chunkSize, int cascading, Path out) throws IOException {
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("at least one column descriptor is required");
        }
        DType.Struct schema = buildSchema(columns);
        WriteOptions options = WriteOptions.cascading(cascading);

        Random random = new Random(seed);
        ProgressBar progress = new ProgressBar(rows);
        try (FileChannel channel = FileChannel.open(out, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
             VortexWriter writer = VortexWriter.create(channel, schema, options)) {
            progress.update(0);
            for (int start = 0; start < rows; start += chunkSize) {
                int n = Math.min(chunkSize, rows - start);
                Map<ColumnName, Object> chunk = new LinkedHashMap<>();
                for (ColumnDescriptor col : columns) {
                    chunk.put(col.name(), ColumnMaterializer.materialize(col, start, n, random));
                }
                writer.writeChunk(chunk);
                progress.update(start + n);
            }
        }
    }

    private static DType.Struct buildSchema(List<ColumnDescriptor> columns) {
        DType.StructBuilder builder = DType.structBuilder();
        for (ColumnDescriptor col : columns) {
            builder.field(col.name(), col.dtype());
        }
        return builder.build();
    }
}
