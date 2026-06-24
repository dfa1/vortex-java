package io.github.dfa1.vortex.proto;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import javax.annotation.processing.Generated;

/// Generated from proto3 message {@code vortex.dtype.Field}.
/// Do not edit by hand — regenerate via {@code ./mvnw generate-sources -pl core -P regenerate-sources}.
/// @param name field tag 1
@Generated("io.github.dfa1.vortex.protogen.CodeGen")
public record ProtoField(
        String name
) {

    /// Decodes a {@code vortex.dtype.Field} from a slice of a memory segment.
    /// @param __seg backing segment
    /// @param __off start offset in bytes
    /// @param __len payload length in bytes
    /// @return decoded record
    /// @throws IOException if the slice is malformed or truncated
    public static ProtoField decode(MemorySegment __seg, long __off, long __len) throws IOException {
        ProtoReader r = new ProtoReader(__seg, __off, __len);
        String name = null;
        while (r.hasMore()) {
            int tag = r.readVarint32();
            switch (tag >>> 3) {
                case 1 -> {
                    name = r.readString();
                }
                default -> r.skipField(tag & 7);
            }
        }
        return new ProtoField(name);
    }

    /// Encodes this record to a proto3-wire-format byte array.
    /// @return encoded bytes
    public byte[] encode() {
        ProtoWriter w = new ProtoWriter();
        encodeTo(w);
        return w.toByteArray();
    }

    void encodeTo(ProtoWriter w) {
        if (name != null) {
            w.writeTag(1, 2);
            w.writeString(name);
        }
    }

    /// Factory for oneof case {@code name} (field tag 1).
    /// @param value the value to set
    /// @return a record with only the {@code name} component set
    public static ProtoField ofName(String value) {
        return new ProtoField(value);
    }
}
