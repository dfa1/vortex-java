package io.github.dfa1.vortex.performance;

import dev.hardwood.InputFile;
import dev.hardwood.metadata.RepetitionType;
import dev.hardwood.reader.ParquetFileReader;
import dev.hardwood.reader.RowReader;
import dev.hardwood.schema.ColumnSchema;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/// Dumps the NYC taxi parquet file (cached at /tmp/yellow_tripdata_2024-01.parquet) to CSV
/// and reports the resulting byte size — to compare against Vortex and Parquet on disk.
public final class TaxiCsvSize {

    private static final Path PARQUET =
            Path.of(System.getProperty("java.io.tmpdir"), "yellow_tripdata_2024-01.parquet");

    private TaxiCsvSize() {
    }

    public static void main(String[] args) throws Exception {
        if (!Files.exists(PARQUET)) {
            System.err.println("Missing: " + PARQUET);
            System.exit(1);
        }
        Path csv = Files.createTempFile("taxi-", ".csv");
        try (ParquetFileReader parquet = ParquetFileReader.open(InputFile.of(PARQUET));
             RowReader rows = parquet.buildRowReader().build();
             BufferedWriter w = Files.newBufferedWriter(csv, StandardCharsets.UTF_8)) {
            List<ColumnSchema> cols = parquet.getFileSchema().getColumns();
            String[] names = cols.stream().map(ColumnSchema::name).toArray(String[]::new);
            boolean[] optional = new boolean[cols.size()];
            for (int i = 0; i < cols.size(); i++) {
                optional[i] = cols.get(i).repetitionType() == RepetitionType.OPTIONAL;
            }
            // Header
            for (int i = 0; i < names.length; i++) {
                if (i > 0) {
                    w.write(',');
                }
                w.write(names[i]);
            }
            w.write('\n');

            long rowCount = 0;
            while (rows.hasNext()) {
                rows.next();
                for (int c = 0; c < cols.size(); c++) {
                    if (c > 0) {
                        w.write(',');
                    }
                    String name = names[c];
                    if (optional[c] && rows.isNull(name)) {
                        continue;
                    }
                    switch (cols.get(c).type()) {
                        case BOOLEAN -> w.write(Boolean.toString(rows.getBoolean(name)));
                        case FLOAT -> w.write(Float.toString(rows.getFloat(name)));
                        case DOUBLE -> w.write(Double.toString(rows.getDouble(name)));
                        case INT32 -> w.write(Integer.toString(rows.getInt(name)));
                        case INT64 -> w.write(Long.toString(rows.getLong(name)));
                        case BYTE_ARRAY -> w.write(rows.getString(name));
                        default -> throw new UnsupportedOperationException("ptype: " + cols.get(c).type());
                    }
                }
                w.write('\n');
                rowCount++;
            }
            w.flush();
            long size = Files.size(csv);
            System.out.printf("CSV: %,d bytes (%.1f MB) for %,d rows%n",
                    size, size / 1_048_576.0, rowCount);
        } finally {
            Files.deleteIfExists(csv);
        }
    }
}
