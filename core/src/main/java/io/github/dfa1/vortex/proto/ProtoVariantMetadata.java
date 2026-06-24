package io.github.dfa1.vortex.proto;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import javax.annotation.processing.Generated;

/// Generated from proto3 message {@code vortex.encodings.VariantMetadata}.
/// Do not edit by hand — regenerate via {@code ./mvnw generate-sources -pl core -P regenerate-sources}.
/// @param shredded_dtype field tag 1
@Generated("io.github.dfa1.vortex.protogen.CodeGen")
public record ProtoVariantMetadata(
        ProtoDType shredded_dtype
) {

    /// Decodes a {@code vortex.encodings.VariantMetadata} from a slice of a memory segment.
    /// @param __seg backing segment
    /// @param __off start offset in bytes
    /// @param __len payload length in bytes
    /// @return decoded record
    /// @throws IOException if the slice is malformed or truncated
    public static ProtoVariantMetadata decode(MemorySegment __seg, long __off, long __len) throws IOException {
        ProtoReader r = new ProtoReader(__seg, __off, __len);
        ProtoDType shredded_dtype = null;
        while (r.hasMore()) {
            int tag = r.readVarint32();
            switch (tag >>> 3) {
                case 1 -> {
                    MemorySegment __slice = r.readLenDelimSegment();
                    shredded_dtype = ProtoDType.decode(__slice, 0, __slice.byteSize());
                }
                default -> r.skipField(tag & 7);
            }
        }
        return new ProtoVariantMetadata(shredded_dtype);
    }

    /// Encodes this record to a proto3-wire-format byte array.
    /// @return encoded bytes
    public byte[] encode() {
        ProtoWriter w = new ProtoWriter();
        encodeTo(w);
        return w.toByteArray();
    }

    void encodeTo(ProtoWriter w) {
        if (shredded_dtype != null) {
            w.writeTag(1, 2);
            int __mark = w.beginLenDelim();
            shredded_dtype.encodeTo(w);
            w.endLenDelim(__mark);
        }
    }
}
