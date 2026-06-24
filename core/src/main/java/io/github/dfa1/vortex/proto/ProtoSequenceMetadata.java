package io.github.dfa1.vortex.proto;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import javax.annotation.processing.Generated;

/// Generated from proto3 message {@code vortex.encodings.SequenceMetadata}.
/// Do not edit by hand — regenerate via {@code ./mvnw generate-sources -pl core -P regenerate-sources}.
/// @param base field tag 1
/// @param multiplier field tag 2
@Generated("io.github.dfa1.vortex.protogen.CodeGen")
public record ProtoSequenceMetadata(
        ProtoScalarValue base,
        ProtoScalarValue multiplier
) {

    /// Decodes a {@code vortex.encodings.SequenceMetadata} from a slice of a memory segment.
    /// @param __seg backing segment
    /// @param __off start offset in bytes
    /// @param __len payload length in bytes
    /// @return decoded record
    /// @throws IOException if the slice is malformed or truncated
    public static ProtoSequenceMetadata decode(MemorySegment __seg, long __off, long __len) throws IOException {
        ProtoReader r = new ProtoReader(__seg, __off, __len);
        ProtoScalarValue base = null;
        ProtoScalarValue multiplier = null;
        while (r.hasMore()) {
            int tag = r.readVarint32();
            switch (tag >>> 3) {
                case 1 -> {
                    MemorySegment __slice = r.readLenDelimSegment();
                    base = ProtoScalarValue.decode(__slice, 0, __slice.byteSize());
                }
                case 2 -> {
                    MemorySegment __slice = r.readLenDelimSegment();
                    multiplier = ProtoScalarValue.decode(__slice, 0, __slice.byteSize());
                }
                default -> r.skipField(tag & 7);
            }
        }
        return new ProtoSequenceMetadata(base, multiplier);
    }

    /// Encodes this record to a proto3-wire-format byte array.
    /// @return encoded bytes
    public byte[] encode() {
        ProtoWriter w = new ProtoWriter();
        encodeTo(w);
        return w.toByteArray();
    }

    void encodeTo(ProtoWriter w) {
        if (base != null) {
            w.writeTag(1, 2);
            int __mark = w.beginLenDelim();
            base.encodeTo(w);
            w.endLenDelim(__mark);
        }
        if (multiplier != null) {
            w.writeTag(2, 2);
            int __mark = w.beginLenDelim();
            multiplier.encodeTo(w);
            w.endLenDelim(__mark);
        }
    }
}
