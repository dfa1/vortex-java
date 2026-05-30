package io.github.dfa1.vortex.csv;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.writer.WriteOptions;

/// Options controlling CSV → Vortex import.
public record ImportOptions(
        char delimiter,
        int chunkSize,
        boolean hasHeader,
        DType.Struct schema,
        ProgressListener progressListener,
        WriteOptions writeOptions
) {
    public static ImportOptions defaults() {
        return new ImportOptions(',', 65_536, true, null, null, WriteOptions.cascading(3));
    }

    /// Override the inferred schema. The struct's field names become column names;
    /// types control how each CSV column is parsed (positionally).
    public ImportOptions withSchema(DType.Struct overrideSchema) {
        return new ImportOptions(delimiter, chunkSize, hasHeader, overrideSchema, progressListener, writeOptions);
    }

    public ImportOptions withProgressListener(ProgressListener listener) {
        return new ImportOptions(delimiter, chunkSize, hasHeader, schema, listener, writeOptions);
    }

    public ImportOptions withWriteOptions(WriteOptions options) {
        return new ImportOptions(delimiter, chunkSize, hasHeader, schema, progressListener, options);
    }
}
