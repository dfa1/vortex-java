package io.github.dfa1.vortex.core.proto;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import javax.annotation.processing.Generated;

/// Generated from proto3 message {@code vortex.encodings.ALPMetadata}.
/// Do not edit by hand — regenerate via {@code ./mvnw generate-sources -pl core -P regenerate-sources}.
/// @param exp_e field tag 1
/// @param exp_f field tag 2
/// @param patches field tag 3
@Generated("io.github.dfa1.vortex.protogen.CodeGen")
public record ProtoALPMetadata(
        int exp_e,
        int exp_f,
        ProtoPatchesMetadata patches
) {

    /// Decodes a {@code vortex.encodings.ALPMetadata} from a slice of a memory segment.
    /// @param __seg backing segment
    /// @param __off start offset in bytes
    /// @param __len payload length in bytes
    /// @return decoded record
    /// @throws IOException if the slice is malformed or truncated
    public static ProtoALPMetadata decode(MemorySegment __seg, long __off, long __len) throws IOException {
        ProtoReader r = new ProtoReader(__seg, __off, __len);
        int exp_e = 0;
        int exp_f = 0;
        ProtoPatchesMetadata patches = null;
        while (r.hasMore()) {
            int tag = r.readVarint32();
            switch (tag >>> 3) {
                case 1 -> {
                    exp_e = r.readVarint32();
                }
                case 2 -> {
                    exp_f = r.readVarint32();
                }
                case 3 -> {
                    MemorySegment __slice = r.readLenDelimSegment();
                    patches = ProtoPatchesMetadata.decode(__slice, 0, __slice.byteSize());
                }
                default -> r.skipField(tag & 7);
            }
        }
        return new ProtoALPMetadata(exp_e, exp_f, patches);
    }

    /// Encodes this record to a proto3-wire-format byte array.
    /// @return encoded bytes
    public byte[] encode() {
        ProtoWriter w = new ProtoWriter();
        encodeTo(w);
        return w.toByteArray();
    }

    void encodeTo(ProtoWriter w) {
        if (exp_e != 0) {
            w.writeTag(1, 0);
            w.writeVarint32(exp_e);
        }
        if (exp_f != 0) {
            w.writeTag(2, 0);
            w.writeVarint32(exp_f);
        }
        if (patches != null) {
            w.writeTag(3, 2);
            int __mark = w.beginLenDelim();
            patches.encodeTo(w);
            w.endLenDelim(__mark);
        }
    }
}
