package io.github.dfa1.vortex.proto;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import javax.annotation.processing.Generated;

/// Generated from proto3 message {@code vortex.encodings.PatchedMetadata}.
/// Do not edit by hand — regenerate via {@code ./mvnw generate-sources -pl core -P regenerate-sources}.
/// @param n_patches field tag 1
/// @param n_lanes field tag 2
/// @param offset field tag 3
@Generated("io.github.dfa1.vortex.protogen.CodeGen")
public record PatchedMetadata(
        int n_patches,
        int n_lanes,
        int offset
) {

    /// Decodes a {@code vortex.encodings.PatchedMetadata} from a slice of a memory segment.
    /// @param __seg backing segment
    /// @param __off start offset in bytes
    /// @param __len payload length in bytes
    /// @return decoded record
    /// @throws IOException if the slice is malformed or truncated
    public static PatchedMetadata decode(MemorySegment __seg, long __off, long __len) throws IOException {
        ProtoReader r = new ProtoReader(__seg, __off, __len);
        int n_patches = 0;
        int n_lanes = 0;
        int offset = 0;
        while (r.hasMore()) {
            int tag = r.readVarint32();
            switch (tag >>> 3) {
                case 1 -> {
                    n_patches = r.readVarint32();
                }
                case 2 -> {
                    n_lanes = r.readVarint32();
                }
                case 3 -> {
                    offset = r.readVarint32();
                }
                default -> r.skipField(tag & 7);
            }
        }
        return new PatchedMetadata(n_patches, n_lanes, offset);
    }

    /// Encodes this record to a proto3-wire-format byte array.
    /// @return encoded bytes
    public byte[] encode() {
        ProtoWriter w = new ProtoWriter();
        encodeTo(w);
        return w.toByteArray();
    }

    void encodeTo(ProtoWriter w) {
        if (n_patches != 0) {
            w.writeTag(1, 0);
            w.writeVarint32(n_patches);
        }
        if (n_lanes != 0) {
            w.writeTag(2, 0);
            w.writeVarint32(n_lanes);
        }
        if (offset != 0) {
            w.writeTag(3, 0);
            w.writeVarint32(offset);
        }
    }
}
