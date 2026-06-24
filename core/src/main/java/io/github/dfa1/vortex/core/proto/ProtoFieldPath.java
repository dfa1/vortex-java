package io.github.dfa1.vortex.core.proto;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import javax.annotation.processing.Generated;

/// Generated from proto3 message {@code vortex.dtype.FieldPath}.
/// Do not edit by hand — regenerate via {@code ./mvnw generate-sources -pl core -P regenerate-sources}.
/// @param path field tag 1
@Generated("io.github.dfa1.vortex.protogen.CodeGen")
public record ProtoFieldPath(
        java.util.List<ProtoField> path
) {

    /// Decodes a {@code vortex.dtype.FieldPath} from a slice of a memory segment.
    /// @param __seg backing segment
    /// @param __off start offset in bytes
    /// @param __len payload length in bytes
    /// @return decoded record
    /// @throws IOException if the slice is malformed or truncated
    public static ProtoFieldPath decode(MemorySegment __seg, long __off, long __len) throws IOException {
        ProtoReader r = new ProtoReader(__seg, __off, __len);
        java.util.List<ProtoField> path = new java.util.ArrayList<>();
        while (r.hasMore()) {
            int tag = r.readVarint32();
            switch (tag >>> 3) {
                case 1 -> {
                    MemorySegment __slice = r.readLenDelimSegment();
                    path.add(ProtoField.decode(__slice, 0, __slice.byteSize()));
                }
                default -> r.skipField(tag & 7);
            }
        }
        return new ProtoFieldPath(path);
    }

    /// Encodes this record to a proto3-wire-format byte array.
    /// @return encoded bytes
    public byte[] encode() {
        ProtoWriter w = new ProtoWriter();
        encodeTo(w);
        return w.toByteArray();
    }

    void encodeTo(ProtoWriter w) {
        for (ProtoField __v : path) {
            w.writeTag(1, 2);
            int __mark = w.beginLenDelim();
            __v.encodeTo(w);
            w.endLenDelim(__mark);
        }
    }
}
