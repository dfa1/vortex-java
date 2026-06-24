package io.github.dfa1.vortex.proto;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import javax.annotation.processing.Generated;

/// Generated from proto3 message {@code vortex.dtype.Union}.
/// Do not edit by hand — regenerate via {@code ./mvnw generate-sources -pl core -P regenerate-sources}.
/// @param nullable field tag 4
@Generated("io.github.dfa1.vortex.protogen.CodeGen")
public record ProtoUnion(
        boolean nullable
) {

    /// Decodes a {@code vortex.dtype.Union} from a slice of a memory segment.
    /// @param __seg backing segment
    /// @param __off start offset in bytes
    /// @param __len payload length in bytes
    /// @return decoded record
    /// @throws IOException if the slice is malformed or truncated
    public static ProtoUnion decode(MemorySegment __seg, long __off, long __len) throws IOException {
        ProtoReader r = new ProtoReader(__seg, __off, __len);
        boolean nullable = false;
        while (r.hasMore()) {
            int tag = r.readVarint32();
            switch (tag >>> 3) {
                case 4 -> {
                    nullable = r.readBool();
                }
                default -> r.skipField(tag & 7);
            }
        }
        return new ProtoUnion(nullable);
    }

    /// Encodes this record to a proto3-wire-format byte array.
    /// @return encoded bytes
    public byte[] encode() {
        ProtoWriter w = new ProtoWriter();
        encodeTo(w);
        return w.toByteArray();
    }

    void encodeTo(ProtoWriter w) {
        if (nullable) {
            w.writeTag(4, 0);
            w.writeBool(nullable);
        }
    }
}
