package io.github.dfa1.vortex.proto;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import javax.annotation.processing.Generated;

/// Generated from proto3 message {@code vortex.dtype.Primitive}.
/// Do not edit by hand — regenerate via {@code ./mvnw generate-sources -pl core -P regenerate-sources}.
/// @param type field tag 1
/// @param nullable field tag 2
@Generated("io.github.dfa1.vortex.protogen.CodeGen")
public record Primitive(
        PType type,
        boolean nullable
) {

    /// Decodes a {@code vortex.dtype.Primitive} from a slice of a memory segment.
    /// @param __seg backing segment
    /// @param __off start offset in bytes
    /// @param __len payload length in bytes
    /// @return decoded record
    /// @throws IOException if the slice is malformed or truncated
    public static Primitive decode(MemorySegment __seg, long __off, long __len) throws IOException {
        ProtoReader r = new ProtoReader(__seg, __off, __len);
        PType type = PType.fromValue(0);
        boolean nullable = false;
        while (r.hasMore()) {
            int tag = r.readVarint32();
            switch (tag >>> 3) {
                case 1 -> {
                    type = PType.fromValue(r.readVarint32());
                }
                case 2 -> {
                    nullable = r.readBool();
                }
                default -> r.skipField(tag & 7);
            }
        }
        return new Primitive(type, nullable);
    }

    /// Encodes this record to a proto3-wire-format byte array.
    /// @return encoded bytes
    public byte[] encode() {
        ProtoWriter w = new ProtoWriter();
        if (type.value() != 0) {
            w.writeTag(1, 0);
            w.writeVarint32(type.value());
        }
        if (nullable) {
            w.writeTag(2, 0);
            w.writeBool(nullable);
        }
        return w.toByteArray();
    }
}
