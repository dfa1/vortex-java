package io.github.dfa1.vortex.csv;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.writer.WriteOptions;

/// Options controlling CSV → Vortex import.
public final class ImportOptions {
    private final char delimiter;
    private final int chunkSize;
    private final boolean header;
    private final DType.Struct schema;
    private final ProgressListener progressListener;
    private final WriteOptions writeOptions;

    private ImportOptions(char delimiter, int chunkSize, boolean header, DType.Struct schema, ProgressListener progressListener, WriteOptions writeOptions) {
        this.delimiter = delimiter;
        this.chunkSize = chunkSize;
        this.header = header;
        this.schema = schema;
        this.progressListener = progressListener;
        this.writeOptions = writeOptions;
    }

    /// The field delimiter.
    ///
    /// @return the delimiter
    public char delimiter() {
        return delimiter;
    }

    /// The number of rows buffered per encoded chunk.
    ///
    /// @return the chunk size
    public int chunkSize() {
        return chunkSize;
    }

    /// Whether the input's first row is a header naming columns.
    ///
    /// @return `true` if the first row is a header
    public boolean header() {
        return header;
    }

    /// The schema overriding inference.
    ///
    /// @return the schema, or `null` to infer it from the header and data
    public DType.Struct schema() {
        return schema;
    }

    /// The callback invoked periodically with `(rowsDone, rowsTotal)`.
    ///
    /// @return the listener, or `null`
    public ProgressListener progressListener() {
        return progressListener;
    }

    /// The writer options used to encode the resulting Vortex file.
    ///
    /// @return the write options
    public WriteOptions writeOptions() {
        return writeOptions;
    }

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
        return new ImportOptions(delimiter, chunkSize, header, overrideSchema, progressListener, writeOptions);
    }

    /// Override the field delimiter.
    ///
    /// @param separator the delimiter character
    /// @return a copy of this options with the delimiter applied
    public ImportOptions withDelimiter(char separator) {
        return new ImportOptions(separator, chunkSize, header, schema, progressListener, writeOptions);
    }

    /// Attach a progress callback invoked periodically with `(rowsDone, rowsTotal)`.
    ///
    /// @param listener the progress callback
    /// @return a copy of this options with the listener attached
    public ImportOptions withProgressListener(ProgressListener listener) {
        return new ImportOptions(delimiter, chunkSize, header, schema, listener, writeOptions);
    }

    /// Override the writer options used to encode the resulting Vortex file.
    ///
    /// @param options the writer options
    /// @return a copy of this options with the writer options applied
    public ImportOptions withWriteOptions(WriteOptions options) {
        return new ImportOptions(delimiter, chunkSize, header, schema, progressListener, options);
    }

    /// Override whether the input's first row is a header naming columns.
    ///
    /// @param header `true` if the first row is a header
    /// @return a copy of this options with the header flag applied
    public ImportOptions withHeader(boolean header) {
        return new ImportOptions(delimiter, chunkSize, header, schema, progressListener, writeOptions);
    }
}
