package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.proto.ProtoScalarValue;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.EncodingId;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/// Output of encoding an array to bytes for one flat segment.
///
/// @param rootNode the root encode node describing the encoding tree structure
/// @param encodedBuffers flat list of data buffers in the order referenced by `rootNode`
/// @param statsMin minimum value for zone-map pruning, or `null`
/// @param statsMax maximum value for zone-map pruning, or `null`
// Stats are the generated ProtoScalarValue, which core only qualified-exports; suppress the exports-leak lint
// rather than publish the whole generated codec (as VariantData does).
@SuppressWarnings("exports")
public record EncodeResult(
        EncodeNode rootNode,
        List<EncodedBuffer> encodedBuffers,
        ProtoScalarValue statsMin,
        ProtoScalarValue statsMax
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
    /// @param min        minimum stat, or `null`
    /// @param max        maximum stat, or `null`
    /// @return an [EncodeResult] backed by a single-buffer leaf node
    public static EncodeResult simple(EncodingId encodingId, EncodedBuffer data, ProtoScalarValue min, ProtoScalarValue max) {
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
    public EncodeResult withStats(MinMax stats) {
        return stats == null
                ? this
                : new EncodeResult(rootNode, encodedBuffers, stats.min(), stats.max());
    }

    /// This result with `validity` appended to the root as its last child, a plain `vortex.bool`:
    /// where Rust's primitive and bit-packed arrays keep the validity of a nullable array, which
    /// the encodings stacked above them (FoR, ZigZag, dict codes) pass down unchanged.
    ///
    /// @param validity per-row validity
    /// @param ctx      the encode context
    /// @return a copy of this result with the validity child and its buffer
    EncodeResult withValidity(boolean[] validity, EncodeContext ctx) {
        EncodeResult bool = new BoolEncodingEncoder().encode(DType.BOOL, validity, ctx);
        EncodeNode[] children = Arrays.copyOf(rootNode.children(), rootNode.children().length + 1);
        children[children.length - 1] = EncodeNode.remapBufferIndices(bool.rootNode(), encodedBuffers.size());
        List<EncodedBuffer> buffers = new ArrayList<>(encodedBuffers);
        buffers.addAll(bool.encodedBuffers());
        EncodeNode root = new EncodeNode(rootNode.encodingId(), rootNode.metadata(), children, rootNode.bufferIndices());
        return new EncodeResult(root, List.copyOf(buffers), statsMin, statsMax);
    }

    /// Returns `true` if both `statsMin` and `statsMax` are present.
    ///
    /// @return `true` if zone-map statistics are available for this result
    public boolean hasStats() {
        return statsMin != null && statsMax != null;
    }
}
