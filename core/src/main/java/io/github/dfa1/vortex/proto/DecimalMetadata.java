package io.github.dfa1.vortex.proto;

import java.io.IOException;
import java.lang.foreign.MemorySegment;

/// Generated from proto3 message {@code vortex.encodings.DecimalMetadata}.
/// Do not edit by hand — regenerate via {@code ./mvnw generate-sources -pl core -P regenerate-sources}.
/// @param values_type field tag 1
public record DecimalMetadata(
        int values_type
) {

    /// Decodes a {@code vortex.encodings.DecimalMetadata} from a slice of a memory segment.
    /// @param __seg backing segment
    /// @param __off start offset in bytes
    /// @param __len payload length in bytes
    /// @return decoded record
    /// @throws IOException if the slice is malformed or truncated
    public static DecimalMetadata decode(MemorySegment __seg, long __off, long __len) throws IOException {
        ProtoReader r = new ProtoReader(__seg, __off, __len);
        int values_type = 0;
        while (r.hasMore()) {
            int tag = r.readVarint32();
            switch (tag >>> 3) {
                case 1 -> {
                    values_type = r.readVarint32();
                }
                default -> r.skipField(tag & 7);
            }
        }
        return new DecimalMetadata(values_type);
    }

    /// Encodes this record to a proto3-wire-format byte array.
    /// @return encoded bytes
    public byte[] encode() {
        ProtoWriter w = new ProtoWriter();
        if (values_type != 0) {
            w.writeTag(1, 0);
            w.writeVarint32(values_type);
        }
        return w.toByteArray();
    }
}
