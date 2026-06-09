package io.github.dfa1.vortex.proto;

import java.io.IOException;
import java.lang.foreign.MemorySegment;

/// Generated from proto3 message {@code vortex.encodings.PcoMetadata}.
/// Do not edit by hand — regenerate via {@code ./mvnw generate-sources -pl core -P regenerate-sources}.
/// @param header field tag 1
/// @param chunks field tag 2
public record PcoMetadata(
        byte[] header,
        java.util.List<PcoChunkInfo> chunks
) {

    /// Decodes a {@code vortex.encodings.PcoMetadata} from a slice of a memory segment.
    /// @param __seg backing segment
    /// @param __off start offset in bytes
    /// @param __len payload length in bytes
    /// @return decoded record
    /// @throws IOException if the slice is malformed or truncated
    public static PcoMetadata decode(MemorySegment __seg, long __off, long __len) throws IOException {
        ProtoReader r = new ProtoReader(__seg, __off, __len);
        byte[] header = new byte[0];
        java.util.List<PcoChunkInfo> chunks = new java.util.ArrayList<>();
        while (r.hasMore()) {
            int tag = r.readVarint32();
            switch (tag >>> 3) {
                case 1 -> {
                    header = r.readBytes();
                }
                case 2 -> {
                    MemorySegment __slice = r.readLenDelimSegment();
                    chunks.add(PcoChunkInfo.decode(__slice, 0, __slice.byteSize()));
                }
                default -> r.skipField(tag & 7);
            }
        }
        return new PcoMetadata(header, chunks);
    }

    /// Encodes this record to a proto3-wire-format byte array.
    /// @return encoded bytes
    public byte[] encode() {
        ProtoWriter w = new ProtoWriter();
        if (header.length != 0) {
            w.writeTag(1, 2);
            w.writeBytes(header);
        }
        for (PcoChunkInfo __v : chunks) {
            w.writeTag(2, 2);
            w.writeEmbedded(__v.encode());
        }
        return w.toByteArray();
    }
}
