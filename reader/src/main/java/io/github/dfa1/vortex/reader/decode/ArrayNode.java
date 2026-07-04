package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.model.EncodingId;

import java.lang.foreign.MemorySegment;

/// Encoded array node as stored in a Flat layout segment — the in-file representation before
/// decoding.
///
/// The encoding id is carried typed, as either an [EncodingId.WellKnown] constant or an
/// [EncodingId.Custom] wrapping a raw wire string, so the Known/Unknown distinction lives in the id
/// itself rather than in a node subtype. Whether an id is decodable is a separate,
/// decode-time question owned by the [io.github.dfa1.vortex.reader.ReadRegistry], not a property of
/// the node: an [EncodingId.Custom] id, or a [EncodingId.WellKnown] id no registered decoder
/// claims, either decodes as a passthrough [io.github.dfa1.vortex.reader.array.UnknownArray] (when
/// [io.github.dfa1.vortex.reader.ReadRegistry#isAllowUnknown()] is set) or fails the decode.
///
/// @param encodingId    the encoding identifier, [EncodingId.WellKnown] or [EncodingId.Custom]
/// @param metadata      encoding-specific metadata bytes, or `null`
/// @param children      child nodes
/// @param bufferIndices segment buffer indices for this node
@SuppressWarnings("java:S6218") // internal data carrier; record components are arrays of immutable primitives or refs that flow through pipelines without ever being compared.
public record ArrayNode(
        EncodingId encodingId,
        MemorySegment metadata,
        ArrayNode[] children,
        int[] bufferIndices
) {
}
