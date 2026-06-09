package io.github.dfa1.vortex.proto;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import javax.annotation.processing.Generated;

/// Generated from proto3 message {@code vortex.encodings.VarBinMetadata}.
/// Do not edit by hand — regenerate via {@code ./mvnw generate-sources -pl core -P regenerate-sources}.
/// @param offsets_ptype field tag 1
@Generated("io.github.dfa1.vortex.protogen.CodeGen")
public record VarBinMetadata(
        PType offsets_ptype
) {

    /// Decodes a {@code vortex.encodings.VarBinMetadata} from a slice of a memory segment.
    /// @param __seg backing segment
    /// @param __off start offset in bytes
    /// @param __len payload length in bytes
    /// @return decoded record
    /// @throws IOException if the slice is malformed or truncated
    public static VarBinMetadata decode(MemorySegment __seg, long __off, long __len) throws IOException {
        ProtoReader r = new ProtoReader(__seg, __off, __len);
        PType offsets_ptype = PType.fromValue(0);
        while (r.hasMore()) {
            int tag = r.readVarint32();
            switch (tag >>> 3) {
                case 1 -> {
                    offsets_ptype = PType.fromValue(r.readVarint32());
                }
                default -> r.skipField(tag & 7);
            }
        }
        return new VarBinMetadata(offsets_ptype);
    }

    /// Encodes this record to a proto3-wire-format byte array.
    /// @return encoded bytes
    public byte[] encode() {
        ProtoWriter w = new ProtoWriter();
        if (offsets_ptype.value() != 0) {
            w.writeTag(1, 0);
            w.writeVarint32(offsets_ptype.value());
        }
        return w.toByteArray();
    }
}
