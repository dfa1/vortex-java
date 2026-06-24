package io.github.dfa1.vortex.proto;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import javax.annotation.processing.Generated;

/// Generated from proto3 message {@code vortex.encodings.BitPackedMetadata}.
/// Do not edit by hand — regenerate via {@code ./mvnw generate-sources -pl core -P regenerate-sources}.
/// @param bit_width field tag 1
/// @param offset field tag 2
/// @param patches field tag 3
@Generated("io.github.dfa1.vortex.protogen.CodeGen")
public record ProtoBitPackedMetadata(
        int bit_width,
        int offset,
        ProtoPatchesMetadata patches
) {

    /// Decodes a {@code vortex.encodings.BitPackedMetadata} from a slice of a memory segment.
    /// @param __seg backing segment
    /// @param __off start offset in bytes
    /// @param __len payload length in bytes
    /// @return decoded record
    /// @throws IOException if the slice is malformed or truncated
    public static ProtoBitPackedMetadata decode(MemorySegment __seg, long __off, long __len) throws IOException {
        ProtoReader r = new ProtoReader(__seg, __off, __len);
        int bit_width = 0;
        int offset = 0;
        ProtoPatchesMetadata patches = null;
        while (r.hasMore()) {
            int tag = r.readVarint32();
            switch (tag >>> 3) {
                case 1 -> {
                    bit_width = r.readVarint32();
                }
                case 2 -> {
                    offset = r.readVarint32();
                }
                case 3 -> {
                    MemorySegment __slice = r.readLenDelimSegment();
                    patches = ProtoPatchesMetadata.decode(__slice, 0, __slice.byteSize());
                }
                default -> r.skipField(tag & 7);
            }
        }
        return new ProtoBitPackedMetadata(bit_width, offset, patches);
    }

    /// Encodes this record to a proto3-wire-format byte array.
    /// @return encoded bytes
    public byte[] encode() {
        ProtoWriter w = new ProtoWriter();
        encodeTo(w);
        return w.toByteArray();
    }

    void encodeTo(ProtoWriter w) {
        if (bit_width != 0) {
            w.writeTag(1, 0);
            w.writeVarint32(bit_width);
        }
        if (offset != 0) {
            w.writeTag(2, 0);
            w.writeVarint32(offset);
        }
        if (patches != null) {
            w.writeTag(3, 2);
            int __mark = w.beginLenDelim();
            patches.encodeTo(w);
            w.endLenDelim(__mark);
        }
    }
}
