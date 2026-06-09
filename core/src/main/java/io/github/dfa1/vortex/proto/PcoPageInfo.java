package io.github.dfa1.vortex.proto;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import javax.annotation.processing.Generated;

/// Generated from proto3 message {@code vortex.encodings.PcoPageInfo}.
/// Do not edit by hand — regenerate via {@code ./mvnw generate-sources -pl core -P regenerate-sources}.
/// @param n_values field tag 1
@Generated("io.github.dfa1.vortex.protogen.CodeGen")
public record PcoPageInfo(
        int n_values
) {

    /// Decodes a {@code vortex.encodings.PcoPageInfo} from a slice of a memory segment.
    /// @param __seg backing segment
    /// @param __off start offset in bytes
    /// @param __len payload length in bytes
    /// @return decoded record
    /// @throws IOException if the slice is malformed or truncated
    public static PcoPageInfo decode(MemorySegment __seg, long __off, long __len) throws IOException {
        ProtoReader r = new ProtoReader(__seg, __off, __len);
        int n_values = 0;
        while (r.hasMore()) {
            int tag = r.readVarint32();
            switch (tag >>> 3) {
                case 1 -> {
                    n_values = r.readVarint32();
                }
                default -> r.skipField(tag & 7);
            }
        }
        return new PcoPageInfo(n_values);
    }

    /// Encodes this record to a proto3-wire-format byte array.
    /// @return encoded bytes
    public byte[] encode() {
        ProtoWriter w = new ProtoWriter();
        if (n_values != 0) {
            w.writeTag(1, 0);
            w.writeVarint32(n_values);
        }
        return w.toByteArray();
    }
}
