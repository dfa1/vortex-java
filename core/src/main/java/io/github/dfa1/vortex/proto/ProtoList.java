package io.github.dfa1.vortex.proto;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import javax.annotation.processing.Generated;

/// Generated from proto3 message {@code vortex.dtype.List}.
/// Do not edit by hand — regenerate via {@code ./mvnw generate-sources -pl core -P regenerate-sources}.
/// @param element_type field tag 1
/// @param nullable field tag 2
@Generated("io.github.dfa1.vortex.protogen.CodeGen")
public record ProtoList(
        ProtoDType element_type,
        boolean nullable
) {

    /// Decodes a {@code vortex.dtype.List} from a slice of a memory segment.
    /// @param __seg backing segment
    /// @param __off start offset in bytes
    /// @param __len payload length in bytes
    /// @return decoded record
    /// @throws IOException if the slice is malformed or truncated
    public static ProtoList decode(MemorySegment __seg, long __off, long __len) throws IOException {
        ProtoReader r = new ProtoReader(__seg, __off, __len);
        ProtoDType element_type = null;
        boolean nullable = false;
        while (r.hasMore()) {
            int tag = r.readVarint32();
            switch (tag >>> 3) {
                case 1 -> {
                    MemorySegment __slice = r.readLenDelimSegment();
                    element_type = ProtoDType.decode(__slice, 0, __slice.byteSize());
                }
                case 2 -> {
                    nullable = r.readBool();
                }
                default -> r.skipField(tag & 7);
            }
        }
        return new ProtoList(element_type, nullable);
    }

    /// Encodes this record to a proto3-wire-format byte array.
    /// @return encoded bytes
    public byte[] encode() {
        ProtoWriter w = new ProtoWriter();
        encodeTo(w);
        return w.toByteArray();
    }

    void encodeTo(ProtoWriter w) {
        if (element_type != null) {
            w.writeTag(1, 2);
            int __mark = w.beginLenDelim();
            element_type.encodeTo(w);
            w.endLenDelim(__mark);
        }
        if (nullable) {
            w.writeTag(2, 0);
            w.writeBool(nullable);
        }
    }
}
