package io.github.dfa1.vortex.parquet;

import dev.hardwood.writer.WriterConfig;

import java.util.List;

/// Options controlling Vortex → Parquet export.
public record ExportOptions(
        List<String> columns,
        ProgressListener progressListener,
        WriterConfig writerConfig
) {
    public static ExportOptions defaults() {
        return new ExportOptions(List.of(), null, WriterConfig.defaults());
    }

    /// Restrict export to specific top-level columns, in the given order. Empty list = all columns.
    public ExportOptions withColumns(List<String> cols) {
        return new ExportOptions(List.copyOf(cols), progressListener, writerConfig);
    }

    public boolean hasProjection() {
        return !columns.isEmpty();
    }

    public ExportOptions withProgressListener(ProgressListener listener) {
        return new ExportOptions(columns, listener, writerConfig);
    }

    public ExportOptions withWriterConfig(WriterConfig config) {
        return new ExportOptions(columns, progressListener, config);
    }
}
