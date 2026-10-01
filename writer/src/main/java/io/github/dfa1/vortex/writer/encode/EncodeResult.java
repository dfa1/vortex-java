package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.model.EncodingId;

import java.lang.foreign.MemorySegment;
import java.util.List;

/// Output of encoding an array to bytes for one flat segment.
///
/// @param rootNode the root encode node describing the encoding tree structure
/// @param encodedBuffers flat list of data buffers in the order referenced by `rootNode`
/// @param statsMin serialized minimum value bytes for zone-map pruning, or `null`
/// @param statsMax serialized maximum value bytes for zone-map pruning, or `null`
@SuppressWarnings("java:S6218") // internal data carrier; record components are arrays of immutable primitives or refs that flow through pipelines without ever being compared.
public record EncodeResult(
        EncodeNode rootNode,
        List<EncodedBuffer> encodedBuffers,
        byte[] statsMin,
        byte[] statsMax
) {
    /// The raw bytes of [#encodedBuffers()], in the same order.
    ///
    /// @return the buffer bytes
    public List<MemorySegment> buffers() {
        return encodedBuffers.stream().map(EncodedBuffer::data).toList();
    }

    /// Convenience factory for single-buffer leaf encodings with stats.
    ///
    /// @param encodingId the encoding identifier for the leaf node
    /// @param data       the single data buffer
    /// @param min        serialized minimum stat bytes, or `null`
    /// @param max        serialized maximum stat bytes, or `null`
    /// @return an [EncodeResult] backed by a single-buffer leaf node
    public static EncodeResult simple(EncodingId encodingId, EncodedBuffer data, byte[] min, byte[] max) {
        return new EncodeResult(EncodeNode.leaf(encodingId, 0), List.of(data), min, max);
    }

    /// Convenience factory for single-buffer leaf encodings without stats.
    ///
    /// @param encodingId the encoding identifier for the leaf node
    /// @param data       the single data buffer
    /// @return an [EncodeResult] backed by a single-buffer leaf node with no stats
    public static EncodeResult simple(EncodingId encodingId, EncodedBuffer data) {
        return simple(encodingId, data, null, null);
    }

    /// This result with zone-map bounds attached, for an encoder that computes them at its entry
    /// point but assembles the result deeper down. Saves threading two nullable `byte[]` through
    /// every private helper on the way, which is how several encodings ended up shipping with no
    /// bounds at all.
    ///
    /// @param stats a `{min, max}` pair as returned by the encoders' `minMaxStats` helpers, or
    ///              `null` when the input carried nothing to bound
    /// @return a copy of this result carrying `stats`
    public EncodeResult withStats(byte[][] stats) {
        return stats == null
                ? this
                : new EncodeResult(rootNode, encodedBuffers, stats[0], stats[1]);
    }

    /// Returns `true` if both `statsMin` and `statsMax` are present.
    ///
    /// @return `true` if zone-map statistics are available for this result
    public boolean hasStats() {
        return statsMin != null && statsMax != null;
    }
}
