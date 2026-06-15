package io.github.dfa1.vortex.writer.encode;

import java.util.List;

/// Input data for encoding a chunked array.
/// Each element of {@code chunks} is the raw data for one chunk, in the same format
/// the inner encoding expects (e.g. {@code long[]} for I64, {@link StructData} for Struct).
/// {@code chunkLengths[i]} is the row count of {@code chunks.get(i)}.
///
/// @param chunks       list of raw chunk data in the format the inner encoding expects
/// @param chunkLengths row count for each chunk; must have the same length as {@code chunks}
@SuppressWarnings("java:S6218") // internal data carrier; record components are arrays of immutable primitives or refs that flow through pipelines without ever being compared.
public record ChunkedData(List<Object> chunks, long[] chunkLengths) {
    /// Validates that {@code chunks} and {@code chunkLengths} have the same size,
    /// then makes defensive copies of both.
    public ChunkedData {
        if (chunks.size() != chunkLengths.length) {
            throw new IllegalArgumentException(
                    "chunks.size() %d != chunkLengths.length %d".formatted(chunks.size(), chunkLengths.length));
        }
        chunks = List.copyOf(chunks);
        chunkLengths = chunkLengths.clone();
    }
}
