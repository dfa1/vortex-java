package io.github.dfa1.vortex.core.proto;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.util.Arrays;
import javax.annotation.processing.Generated;

/// Generated from proto3 message {@code vortex.encodings.PcoMetadata}.
/// Do not edit by hand — regenerate via {@code ./mvnw generate-sources -pl core -P regenerate-sources}.
/// @param header field tag 1
/// @param chunks field tag 2
@Generated("io.github.dfa1.vortex.protogen.CodeGen")
public record ProtoPcoMetadata(
        byte[] header,
        java.util.List<ProtoPcoChunkInfo> chunks
) {

    /// Decodes a {@code vortex.encodings.PcoMetadata} from a slice of a memory segment.
    /// @param __seg backing segment
    /// @param __off start offset in bytes
    /// @param __len payload length in bytes
    /// @return decoded record
    /// @throws IOException if the slice is malformed or truncated
    public static ProtoPcoMetadata decode(MemorySegment __seg, long __off, long __len) throws IOException {
        ProtoReader r = new ProtoReader(__seg, __off, __len);
        byte[] header = new byte[0];
        java.util.List<ProtoPcoChunkInfo> chunks = new java.util.ArrayList<>();
        while (r.hasMore()) {
            int tag = r.readVarint32();
            switch (tag >>> 3) {
                case 1 -> {
                    header = r.readBytes();
                }
                case 2 -> {
                    MemorySegment __slice = r.readLenDelimSegment();
                    chunks.add(ProtoPcoChunkInfo.decode(__slice, 0, __slice.byteSize()));
                }
                default -> r.skipField(tag & 7);
            }
        }
        return new ProtoPcoMetadata(header, chunks);
    }

    /// Encodes this record to a proto3-wire-format byte array.
    /// @return encoded bytes
    public byte[] encode() {
        ProtoWriter w = new ProtoWriter();
        encodeTo(w);
        return w.toByteArray();
    }

    void encodeTo(ProtoWriter w) {
        if (header != null && header.length != 0) {
            w.writeTag(1, 2);
            w.writeBytes(header);
        }
        for (ProtoPcoChunkInfo __v : chunks) {
            w.writeTag(2, 2);
            int __mark = w.beginLenDelim();
            __v.encodeTo(w);
            w.endLenDelim(__mark);
        }
    }

    @Override
    public boolean equals(Object __o) {
        if (this == __o) {
            return true;
        }
        if (!(__o instanceof ProtoPcoMetadata __that)) {
            return false;
        }
        return java.util.Arrays.equals(header, __that.header)
            && java.util.Objects.equals(chunks, __that.chunks);
    }

    @Override
    public int hashCode() {
        int __h = 1;
        __h = 31 * __h + java.util.Arrays.hashCode(header);
        __h = 31 * __h + java.util.Objects.hashCode(chunks);
        return __h;
    }
}
