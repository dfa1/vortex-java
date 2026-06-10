package io.github.dfa1.vortex.proto;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import javax.annotation.processing.Generated;

/// Generated from proto3 message {@code vortex.encodings.FSSTMetadata}.
/// Do not edit by hand — regenerate via {@code ./mvnw generate-sources -pl core -P regenerate-sources}.
/// @param uncompressed_lengths_ptype field tag 1
/// @param codes_offsets_ptype field tag 2
@Generated("io.github.dfa1.vortex.protogen.CodeGen")
public record FSSTMetadata(
        PType uncompressed_lengths_ptype,
        PType codes_offsets_ptype
) {

    /// Decodes a {@code vortex.encodings.FSSTMetadata} from a slice of a memory segment.
    /// @param __seg backing segment
    /// @param __off start offset in bytes
    /// @param __len payload length in bytes
    /// @return decoded record
    /// @throws IOException if the slice is malformed or truncated
    public static FSSTMetadata decode(MemorySegment __seg, long __off, long __len) throws IOException {
        ProtoReader r = new ProtoReader(__seg, __off, __len);
        PType uncompressed_lengths_ptype = PType.U8;
        PType codes_offsets_ptype = PType.U8;
        while (r.hasMore()) {
            int tag = r.readVarint32();
            switch (tag >>> 3) {
                case 1 -> {
                    int __ev = r.readVarint32();
                    try {
                        uncompressed_lengths_ptype = PType.fromValue(__ev);
                    } catch (IllegalArgumentException __iae) {
                        throw new IOException("unknown PType value: " + __ev);
                    }
                }
                case 2 -> {
                    int __ev = r.readVarint32();
                    try {
                        codes_offsets_ptype = PType.fromValue(__ev);
                    } catch (IllegalArgumentException __iae) {
                        throw new IOException("unknown PType value: " + __ev);
                    }
                }
                default -> r.skipField(tag & 7);
            }
        }
        return new FSSTMetadata(uncompressed_lengths_ptype, codes_offsets_ptype);
    }

    /// Encodes this record to a proto3-wire-format byte array.
    /// @return encoded bytes
    public byte[] encode() {
        ProtoWriter w = new ProtoWriter();
        encodeTo(w);
        return w.toByteArray();
    }

    void encodeTo(ProtoWriter w) {
        if (uncompressed_lengths_ptype.value() != 0) {
            w.writeTag(1, 0);
            w.writeVarint32(uncompressed_lengths_ptype.value());
        }
        if (codes_offsets_ptype.value() != 0) {
            w.writeTag(2, 0);
            w.writeVarint32(codes_offsets_ptype.value());
        }
    }
}
