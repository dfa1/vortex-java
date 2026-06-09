package io.github.dfa1.vortex.proto;

import java.io.IOException;
import java.lang.foreign.MemorySegment;

/// Generated from proto3 message {@code vortex.encodings.SequenceMetadata}.
/// Do not edit by hand — regenerate via {@code ./mvnw generate-sources -pl core -P regenerate-sources}.
/// @param base field tag 1
/// @param multiplier field tag 2
public record SequenceMetadata(
        ScalarValue base,
        ScalarValue multiplier
) {

    /// Decodes a {@code vortex.encodings.SequenceMetadata} from a slice of a memory segment.
    /// @param __seg backing segment
    /// @param __off start offset in bytes
    /// @param __len payload length in bytes
    /// @return decoded record
    /// @throws IOException if the slice is malformed or truncated
    public static SequenceMetadata decode(MemorySegment __seg, long __off, long __len) throws IOException {
        ProtoReader r = new ProtoReader(__seg, __off, __len);
        ScalarValue base = null;
        ScalarValue multiplier = null;
        while (r.hasMore()) {
            int tag = r.readVarint32();
            switch (tag >>> 3) {
                case 1 -> {
                    MemorySegment __slice = r.readLenDelimSegment();
                    base = ScalarValue.decode(__slice, 0, __slice.byteSize());
                }
                case 2 -> {
                    MemorySegment __slice = r.readLenDelimSegment();
                    multiplier = ScalarValue.decode(__slice, 0, __slice.byteSize());
                }
                default -> r.skipField(tag & 7);
            }
        }
        return new SequenceMetadata(base, multiplier);
    }

    /// Encodes this record to a proto3-wire-format byte array.
    /// @return encoded bytes
    public byte[] encode() {
        ProtoWriter w = new ProtoWriter();
        if (base != null) {
            w.writeTag(1, 2);
            w.writeEmbedded(base.encode());
        }
        if (multiplier != null) {
            w.writeTag(2, 2);
            w.writeEmbedded(multiplier.encode());
        }
        return w.toByteArray();
    }
}
