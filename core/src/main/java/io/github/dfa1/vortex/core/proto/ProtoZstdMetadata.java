package io.github.dfa1.vortex.core.proto;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import javax.annotation.processing.Generated;

/// Generated from proto3 message {@code vortex.encodings.ZstdMetadata}.
/// Do not edit by hand — regenerate via {@code ./mvnw generate-sources -pl core -P regenerate-sources}.
/// @param dictionary_size field tag 1
/// @param frames field tag 2
@Generated("io.github.dfa1.vortex.protogen.CodeGen")
public record ProtoZstdMetadata(
        int dictionary_size,
        java.util.List<ProtoZstdFrameMetadata> frames
) {

    /// Decodes a {@code vortex.encodings.ZstdMetadata} from a slice of a memory segment.
    /// @param __seg backing segment
    /// @param __off start offset in bytes
    /// @param __len payload length in bytes
    /// @return decoded record
    /// @throws IOException if the slice is malformed or truncated
    public static ProtoZstdMetadata decode(MemorySegment __seg, long __off, long __len) throws IOException {
        ProtoReader r = new ProtoReader(__seg, __off, __len);
        int dictionary_size = 0;
        java.util.List<ProtoZstdFrameMetadata> frames = new java.util.ArrayList<>();
        while (r.hasMore()) {
            int tag = r.readVarint32();
            switch (tag >>> 3) {
                case 1 -> {
                    dictionary_size = r.readVarint32();
                }
                case 2 -> {
                    MemorySegment __slice = r.readLenDelimSegment();
                    frames.add(ProtoZstdFrameMetadata.decode(__slice, 0, __slice.byteSize()));
                }
                default -> r.skipField(tag & 7);
            }
        }
        return new ProtoZstdMetadata(dictionary_size, frames);
    }

    /// Encodes this record to a proto3-wire-format byte array.
    /// @return encoded bytes
    public byte[] encode() {
        ProtoWriter w = new ProtoWriter();
        encodeTo(w);
        return w.toByteArray();
    }

    void encodeTo(ProtoWriter w) {
        if (dictionary_size != 0) {
            w.writeTag(1, 0);
            w.writeVarint32(dictionary_size);
        }
        for (ProtoZstdFrameMetadata __v : frames) {
            w.writeTag(2, 2);
            int __mark = w.beginLenDelim();
            __v.encodeTo(w);
            w.endLenDelim(__mark);
        }
    }
}
