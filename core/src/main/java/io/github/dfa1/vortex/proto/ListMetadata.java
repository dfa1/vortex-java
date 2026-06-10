package io.github.dfa1.vortex.proto;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import javax.annotation.processing.Generated;

/// Generated from proto3 message {@code vortex.encodings.ListMetadata}.
/// Do not edit by hand — regenerate via {@code ./mvnw generate-sources -pl core -P regenerate-sources}.
/// @param elements_len field tag 1
/// @param offset_ptype field tag 2
@Generated("io.github.dfa1.vortex.protogen.CodeGen")
public record ListMetadata(
        long elements_len,
        PType offset_ptype
) {

    /// Decodes a {@code vortex.encodings.ListMetadata} from a slice of a memory segment.
    /// @param __seg backing segment
    /// @param __off start offset in bytes
    /// @param __len payload length in bytes
    /// @return decoded record
    /// @throws IOException if the slice is malformed or truncated
    public static ListMetadata decode(MemorySegment __seg, long __off, long __len) throws IOException {
        ProtoReader r = new ProtoReader(__seg, __off, __len);
        long elements_len = 0;
        PType offset_ptype = PType.U8;
        while (r.hasMore()) {
            int tag = r.readVarint32();
            switch (tag >>> 3) {
                case 1 -> {
                    elements_len = r.readVarint64();
                }
                case 2 -> {
                    int __ev = r.readVarint32();
                    try {
                        offset_ptype = PType.fromValue(__ev);
                    } catch (IllegalArgumentException __iae) {
                        throw new IOException("unknown PType value: " + __ev);
                    }
                }
                default -> r.skipField(tag & 7);
            }
        }
        return new ListMetadata(elements_len, offset_ptype);
    }

    /// Encodes this record to a proto3-wire-format byte array.
    /// @return encoded bytes
    public byte[] encode() {
        ProtoWriter w = new ProtoWriter();
        if (elements_len != 0L) {
            w.writeTag(1, 0);
            w.writeVarint64(elements_len);
        }
        if (offset_ptype.value() != 0) {
            w.writeTag(2, 0);
            w.writeVarint32(offset_ptype.value());
        }
        return w.toByteArray();
    }
}
