package io.github.dfa1.vortex.proto;

import java.io.IOException;
import java.lang.foreign.MemorySegment;

/// Generated from proto3 message {@code vortex.encodings.ZstdFrameMetadata}.
/// Do not edit by hand — regenerate via {@code ./mvnw generate-sources -pl core -P regenerate-sources}.
/// @param uncompressed_size field tag 1
/// @param n_values field tag 2
public record ZstdFrameMetadata(
        long uncompressed_size,
        long n_values
) {

    /// Decodes a {@code vortex.encodings.ZstdFrameMetadata} from a slice of a memory segment.
    /// @param __seg backing segment
    /// @param __off start offset in bytes
    /// @param __len payload length in bytes
    /// @return decoded record
    /// @throws IOException if the slice is malformed or truncated
    public static ZstdFrameMetadata decode(MemorySegment __seg, long __off, long __len) throws IOException {
        ProtoReader r = new ProtoReader(__seg, __off, __len);
        long uncompressed_size = 0;
        long n_values = 0;
        while (r.hasMore()) {
            int tag = r.readVarint32();
            switch (tag >>> 3) {
                case 1 -> {
                    uncompressed_size = r.readVarint64();
                }
                case 2 -> {
                    n_values = r.readVarint64();
                }
                default -> r.skipField(tag & 7);
            }
        }
        return new ZstdFrameMetadata(uncompressed_size, n_values);
    }

    /// Encodes this record to a proto3-wire-format byte array.
    /// @return encoded bytes
    public byte[] encode() {
        ProtoWriter w = new ProtoWriter();
        if (uncompressed_size != 0L) {
            w.writeTag(1, 0);
            w.writeVarint64(uncompressed_size);
        }
        if (n_values != 0L) {
            w.writeTag(2, 0);
            w.writeVarint64(n_values);
        }
        return w.toByteArray();
    }
}
