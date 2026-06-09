package io.github.dfa1.vortex.proto;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import javax.annotation.processing.Generated;

/// Generated from proto3 message {@code vortex.encodings.PcoChunkInfo}.
/// Do not edit by hand — regenerate via {@code ./mvnw generate-sources -pl core -P regenerate-sources}.
/// @param pages field tag 1
@Generated("io.github.dfa1.vortex.protogen.CodeGen")
public record PcoChunkInfo(
        java.util.List<PcoPageInfo> pages
) {

    /// Decodes a {@code vortex.encodings.PcoChunkInfo} from a slice of a memory segment.
    /// @param __seg backing segment
    /// @param __off start offset in bytes
    /// @param __len payload length in bytes
    /// @return decoded record
    /// @throws IOException if the slice is malformed or truncated
    public static PcoChunkInfo decode(MemorySegment __seg, long __off, long __len) throws IOException {
        ProtoReader r = new ProtoReader(__seg, __off, __len);
        java.util.List<PcoPageInfo> pages = new java.util.ArrayList<>();
        while (r.hasMore()) {
            int tag = r.readVarint32();
            switch (tag >>> 3) {
                case 1 -> {
                    MemorySegment __slice = r.readLenDelimSegment();
                    pages.add(PcoPageInfo.decode(__slice, 0, __slice.byteSize()));
                }
                default -> r.skipField(tag & 7);
            }
        }
        return new PcoChunkInfo(pages);
    }

    /// Encodes this record to a proto3-wire-format byte array.
    /// @return encoded bytes
    public byte[] encode() {
        ProtoWriter w = new ProtoWriter();
        for (PcoPageInfo __v : pages) {
            w.writeTag(1, 2);
            w.writeEmbedded(__v.encode());
        }
        return w.toByteArray();
    }
}
