package io.github.dfa1.vortex.core.proto;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import javax.annotation.processing.Generated;

/// Generated from proto3 message {@code vortex.dtype.Map}.
/// Do not edit by hand — regenerate via {@code ./mvnw generate-sources -pl core -P regenerate-sources}.
/// @param key_type field tag 1
/// @param value_type field tag 2
/// @param keys_sorted field tag 3
/// @param nullable field tag 4
@Generated("io.github.dfa1.vortex.protogen.CodeGen")
public record ProtoMap(
        ProtoDType key_type,
        ProtoDType value_type,
        boolean keys_sorted,
        boolean nullable
) {

    /// Decodes a {@code vortex.dtype.Map} from a slice of a memory segment.
    /// @param __seg backing segment
    /// @param __off start offset in bytes
    /// @param __len payload length in bytes
    /// @return decoded record
    /// @throws IOException if the slice is malformed or truncated
    public static ProtoMap decode(MemorySegment __seg, long __off, long __len) throws IOException {
        ProtoReader r = new ProtoReader(__seg, __off, __len);
        ProtoDType key_type = null;
        ProtoDType value_type = null;
        boolean keys_sorted = false;
        boolean nullable = false;
        while (r.hasMore()) {
            int tag = r.readVarint32();
            switch (tag >>> 3) {
                case 1 -> {
                    MemorySegment __slice = r.readLenDelimSegment();
                    key_type = ProtoDType.decode(__slice, 0, __slice.byteSize());
                }
                case 2 -> {
                    MemorySegment __slice = r.readLenDelimSegment();
                    value_type = ProtoDType.decode(__slice, 0, __slice.byteSize());
                }
                case 3 -> {
                    keys_sorted = r.readBool();
                }
                case 4 -> {
                    nullable = r.readBool();
                }
                default -> r.skipField(tag & 7);
            }
        }
        return new ProtoMap(key_type, value_type, keys_sorted, nullable);
    }

    /// Encodes this record to a proto3-wire-format byte array.
    /// @return encoded bytes
    public byte[] encode() {
        ProtoWriter w = new ProtoWriter();
        encodeTo(w);
        return w.toByteArray();
    }

    void encodeTo(ProtoWriter w) {
        if (key_type != null) {
            w.writeTag(1, 2);
            int __mark = w.beginLenDelim();
            key_type.encodeTo(w);
            w.endLenDelim(__mark);
        }
        if (value_type != null) {
            w.writeTag(2, 2);
            int __mark = w.beginLenDelim();
            value_type.encodeTo(w);
            w.endLenDelim(__mark);
        }
        if (keys_sorted) {
            w.writeTag(3, 0);
            w.writeBool(keys_sorted);
        }
        if (nullable) {
            w.writeTag(4, 0);
            w.writeBool(nullable);
        }
    }
}
