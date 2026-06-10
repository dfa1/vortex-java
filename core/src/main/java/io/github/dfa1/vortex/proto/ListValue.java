package io.github.dfa1.vortex.proto;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import javax.annotation.processing.Generated;

/// Generated from proto3 message {@code vortex.scalar.ListValue}.
/// Do not edit by hand — regenerate via {@code ./mvnw generate-sources -pl core -P regenerate-sources}.
/// @param values field tag 1
@Generated("io.github.dfa1.vortex.protogen.CodeGen")
public record ListValue(
        java.util.List<ScalarValue> values
) {

    /// Decodes a {@code vortex.scalar.ListValue} from a slice of a memory segment.
    /// @param __seg backing segment
    /// @param __off start offset in bytes
    /// @param __len payload length in bytes
    /// @return decoded record
    /// @throws IOException if the slice is malformed or truncated
    public static ListValue decode(MemorySegment __seg, long __off, long __len) throws IOException {
        ProtoReader r = new ProtoReader(__seg, __off, __len);
        java.util.List<ScalarValue> values = new java.util.ArrayList<>();
        while (r.hasMore()) {
            int tag = r.readVarint32();
            switch (tag >>> 3) {
                case 1 -> {
                    MemorySegment __slice = r.readLenDelimSegment();
                    values.add(ScalarValue.decode(__slice, 0, __slice.byteSize()));
                }
                default -> r.skipField(tag & 7);
            }
        }
        return new ListValue(values);
    }

    /// Encodes this record to a proto3-wire-format byte array.
    /// @return encoded bytes
    public byte[] encode() {
        ProtoWriter w = new ProtoWriter();
        encodeTo(w);
        return w.toByteArray();
    }

    void encodeTo(ProtoWriter w) {
        for (ScalarValue __v : values) {
            w.writeTag(1, 2);
            int __mark = w.beginLenDelim();
            __v.encodeTo(w);
            w.endLenDelim(__mark);
        }
    }
}
