package io.github.dfa1.vortex.encoding;

import java.lang.foreign.MemorySegment;
import java.util.List;

/// Output of encoding an array to bytes for one flat segment.
///
/// @param rootNode the root encode node describing the encoding tree structure
/// @param buffers  flat list of data buffers in the order referenced by {@code rootNode}
/// @param statsMin serialised minimum value bytes for zone-map pruning, or {@code null}
/// @param statsMax serialised maximum value bytes for zone-map pruning, or {@code null}
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
    /// @param min        serialised minimum stat bytes, or {@code null}
    /// @param max        serialised maximum stat bytes, or {@code null}
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

    /// Returns {@code true} if both {@code statsMin} and {@code statsMax} are present.
    ///
    /// @return {@code true} if zone-map statistics are available for this result
    public boolean hasStats() {
        return statsMin != null && statsMax != null;
    }
}
