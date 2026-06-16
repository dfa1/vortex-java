package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.encoding.EncodingId;

import java.lang.foreign.MemorySegment;
import java.util.List;

/// Output of encoding an array to bytes for one flat segment.
///
/// @param rootNode the root encode node describing the encoding tree structure
/// @param buffers  flat list of data buffers in the order referenced by `rootNode`
/// @param statsMin serialised minimum value bytes for zone-map pruning, or `null`
/// @param statsMax serialised maximum value bytes for zone-map pruning, or `null`
@SuppressWarnings("java:S6218") // internal data carrier; record components are arrays of immutable primitives or refs that flow through pipelines without ever being compared.
public record EncodeResult(
        EncodeNode rootNode,
        List<MemorySegment> buffers,
        byte[] statsMin,
        byte[] statsMax
) {
    /// Convenience factory for single-buffer leaf encodings with stats.
    ///
    /// @param encodingId the encoding identifier for the leaf node
    /// @param data       the single data buffer
    /// @param min        serialised minimum stat bytes, or `null`
    /// @param max        serialised maximum stat bytes, or `null`
    /// @return an {@link EncodeResult} backed by a single-buffer leaf node
    public static EncodeResult simple(EncodingId encodingId, MemorySegment data, byte[] min, byte[] max) {
        return new EncodeResult(EncodeNode.leaf(encodingId, 0), List.of(data), min, max);
    }

    /// Convenience factory for single-buffer leaf encodings without stats.
    ///
    /// @param encodingId the encoding identifier for the leaf node
    /// @param data       the single data buffer
    /// @return an {@link EncodeResult} backed by a single-buffer leaf node with no stats
    public static EncodeResult simple(EncodingId encodingId, MemorySegment data) {
        return simple(encodingId, data, null, null);
    }

    /// Returns `true` if both `statsMin` and `statsMax` are present.
    ///
    /// @return `true` if zone-map statistics are available for this result
    public boolean hasStats() {
        return statsMin != null && statsMax != null;
    }
}
