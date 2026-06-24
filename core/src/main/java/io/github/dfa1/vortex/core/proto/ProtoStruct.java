package io.github.dfa1.vortex.core.proto;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import javax.annotation.processing.Generated;

/// Generated from proto3 message {@code vortex.dtype.Struct}.
/// Do not edit by hand — regenerate via {@code ./mvnw generate-sources -pl core -P regenerate-sources}.
/// @param names field tag 1
/// @param dtypes field tag 2
/// @param nullable field tag 3
@Generated("io.github.dfa1.vortex.protogen.CodeGen")
public record ProtoStruct(
        java.util.List<String> names,
        java.util.List<ProtoDType> dtypes,
        boolean nullable
) {

    /// Decodes a {@code vortex.dtype.Struct} from a slice of a memory segment.
    /// @param __seg backing segment
    /// @param __off start offset in bytes
    /// @param __len payload length in bytes
    /// @return decoded record
    /// @throws IOException if the slice is malformed or truncated
    public static ProtoStruct decode(MemorySegment __seg, long __off, long __len) throws IOException {
        ProtoReader r = new ProtoReader(__seg, __off, __len);
        java.util.List<String> names = new java.util.ArrayList<>();
        java.util.List<ProtoDType> dtypes = new java.util.ArrayList<>();
        boolean nullable = false;
        while (r.hasMore()) {
            int tag = r.readVarint32();
            switch (tag >>> 3) {
                case 1 -> {
                    names.add(r.readString());
                }
                case 2 -> {
                    MemorySegment __slice = r.readLenDelimSegment();
                    dtypes.add(ProtoDType.decode(__slice, 0, __slice.byteSize()));
                }
                case 3 -> {
                    nullable = r.readBool();
                }
                default -> r.skipField(tag & 7);
            }
        }
        return new ProtoStruct(names, dtypes, nullable);
    }

    /// Encodes this record to a proto3-wire-format byte array.
    /// @return encoded bytes
    public byte[] encode() {
        ProtoWriter w = new ProtoWriter();
        encodeTo(w);
        return w.toByteArray();
    }

    void encodeTo(ProtoWriter w) {
        for (String __v : names) {
            w.writeTag(1, 2);
            w.writeString(__v);
        }
        for (ProtoDType __v : dtypes) {
            w.writeTag(2, 2);
            int __mark = w.beginLenDelim();
            __v.encodeTo(w);
            w.endLenDelim(__mark);
        }
        if (nullable) {
            w.writeTag(3, 0);
            w.writeBool(nullable);
        }
    }
}
