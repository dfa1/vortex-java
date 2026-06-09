package io.github.dfa1.vortex.proto;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import javax.annotation.processing.Generated;

/// Generated from proto3 message {@code vortex.encodings.DecimalBytePartsMetadata}.
/// Do not edit by hand — regenerate via {@code ./mvnw generate-sources -pl core -P regenerate-sources}.
/// @param zeroth_child_ptype field tag 1
/// @param lower_part_count field tag 2
@Generated("io.github.dfa1.vortex.protogen.CodeGen")
public record DecimalBytePartsMetadata(
        PType zeroth_child_ptype,
        int lower_part_count
) {

    /// Decodes a {@code vortex.encodings.DecimalBytePartsMetadata} from a slice of a memory segment.
    /// @param __seg backing segment
    /// @param __off start offset in bytes
    /// @param __len payload length in bytes
    /// @return decoded record
    /// @throws IOException if the slice is malformed or truncated
    public static DecimalBytePartsMetadata decode(MemorySegment __seg, long __off, long __len) throws IOException {
        ProtoReader r = new ProtoReader(__seg, __off, __len);
        PType zeroth_child_ptype = PType.fromValue(0);
        int lower_part_count = 0;
        while (r.hasMore()) {
            int tag = r.readVarint32();
            switch (tag >>> 3) {
                case 1 -> {
                    zeroth_child_ptype = PType.fromValue(r.readVarint32());
                }
                case 2 -> {
                    lower_part_count = r.readVarint32();
                }
                default -> r.skipField(tag & 7);
            }
        }
        return new DecimalBytePartsMetadata(zeroth_child_ptype, lower_part_count);
    }

    /// Encodes this record to a proto3-wire-format byte array.
    /// @return encoded bytes
    public byte[] encode() {
        ProtoWriter w = new ProtoWriter();
        if (zeroth_child_ptype.value() != 0) {
            w.writeTag(1, 0);
            w.writeVarint32(zeroth_child_ptype.value());
        }
        if (lower_part_count != 0) {
            w.writeTag(2, 0);
            w.writeVarint32(lower_part_count);
        }
        return w.toByteArray();
    }
}
