package io.github.dfa1.vortex.csv;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.writer.WriteOptions;

/// Options controlling CSV → Vortex import.
///
/// @param delimiter the field delimiter
/// @param chunkSize the number of rows buffered per encoded chunk
/// @param hasHeader whether the input's first row is a header naming columns
/// @param schema the inferred or overridden schema, or null to infer from the header/data
/// @param progressListener callback invoked periodically with `(rowsDone, rowsTotal)`, or null
/// @param writeOptions the writer options used to encode the resulting Vortex file
public record ImportOptions(
        char delimiter,
        int chunkSize,
        boolean hasHeader,
        DType.Struct schema,
        ProgressListener progressListener,
        WriteOptions writeOptions
) {
    /// Default options.
    ///
    /// Global dictionary is disabled because CSV import is streaming — enabling it
    /// would buffer every column's raw data across all chunks until close, consuming
    /// O(total rows) heap. Per-chunk dict encoding still applies inside each chunk.
    ///
    /// @return the default options
    public static ImportOptions defaults() {
        return new ImportOptions(',', 65_536, true, null, null, WriteOptions.cascading(3).withGlobalDict(false));
    }

    /// Override the inferred schema. The struct's field names become column names;
    /// types control how each CSV column is parsed (positionally).
    ///
    /// @param overrideSchema the schema to use instead of inference
    /// @return a copy of this options with the schema applied
    public ImportOptions withSchema(DType.Struct overrideSchema) {
        return new ImportOptions(delimiter, chunkSize, hasHeader, overrideSchema, progressListener, writeOptions);
    }

    /// Override the field delimiter.
    ///
    /// @param separator the delimiter character
    /// @return a copy of this options with the delimiter applied
    public ImportOptions withDelimiter(char separator) {
        return new ImportOptions(separator, chunkSize, hasHeader, schema, progressListener, writeOptions);
    }

    /// Attach a progress callback invoked periodically with `(rowsDone, rowsTotal)`.
    ///
    /// @param listener the progress callback
    /// @return a copy of this options with the listener attached
    public ImportOptions withProgressListener(ProgressListener listener) {
        return new ImportOptions(delimiter, chunkSize, hasHeader, schema, listener, writeOptions);
    }

    /// Override the writer options used to encode the resulting Vortex file.
    ///
    /// @param options the writer options
    /// @return a copy of this options with the writer options applied
    public ImportOptions withWriteOptions(WriteOptions options) {
        return new ImportOptions(delimiter, chunkSize, hasHeader, schema, progressListener, options);
    }
}
