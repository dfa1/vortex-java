package io.github.dfa1.vortex.csv;

import io.github.dfa1.vortex.core.DType;

/// Options controlling CSV → Vortex import.
public record ImportOptions(
        char delimiter,
        int chunkSize,
        boolean hasHeader,
        DType.Struct schema,
        ProgressListener progressListener
) {
    public static ImportOptions defaults() {
        return new ImportOptions(',', 65_536, true, null, null);
    }

    /// Override the inferred schema. The struct's field names become column names;
    /// types control how each CSV column is parsed (positionally).
    public ImportOptions withSchema(DType.Struct overrideSchema) {
        return new ImportOptions(delimiter, chunkSize, hasHeader, overrideSchema, progressListener);
    }

    public ImportOptions withProgressListener(ProgressListener listener) {
        return new ImportOptions(delimiter, chunkSize, hasHeader, schema, listener);
    }
}
