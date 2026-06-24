package io.github.dfa1.vortex.proto;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import javax.annotation.processing.Generated;

/// Generated from proto3 message {@code vortex.encodings.PatchesMetadata}.
/// Do not edit by hand — regenerate via {@code ./mvnw generate-sources -pl core -P regenerate-sources}.
/// @param len field tag 1
/// @param offset field tag 2
/// @param indices_ptype field tag 3
/// @param chunk_offsets_len field tag 4
/// @param chunk_offsets_ptype field tag 5
/// @param offset_within_chunk field tag 6
@Generated("io.github.dfa1.vortex.protogen.CodeGen")
public record ProtoPatchesMetadata(
        long len,
        long offset,
        ProtoPType indices_ptype,
        Long chunk_offsets_len,
        ProtoPType chunk_offsets_ptype,
        Long offset_within_chunk
) {

    /// Decodes a {@code vortex.encodings.PatchesMetadata} from a slice of a memory segment.
    /// @param __seg backing segment
    /// @param __off start offset in bytes
    /// @param __len payload length in bytes
    /// @return decoded record
    /// @throws IOException if the slice is malformed or truncated
    public static ProtoPatchesMetadata decode(MemorySegment __seg, long __off, long __len) throws IOException {
        ProtoReader r = new ProtoReader(__seg, __off, __len);
        long len = 0;
        long offset = 0;
        ProtoPType indices_ptype = ProtoPType.U8;
        Long chunk_offsets_len = null;
        ProtoPType chunk_offsets_ptype = null;
        Long offset_within_chunk = null;
        while (r.hasMore()) {
            int tag = r.readVarint32();
            switch (tag >>> 3) {
                case 1 -> {
                    len = r.readVarint64();
                }
                case 2 -> {
                    offset = r.readVarint64();
                }
                case 3 -> {
                    int __ev = r.readVarint32();
                    try {
                        indices_ptype = ProtoPType.fromValue(__ev);
                    } catch (IllegalArgumentException __iae) {
                        throw new IOException("unknown ProtoPType value: " + __ev);
                    }
                }
                case 4 -> {
                    chunk_offsets_len = r.readVarint64();
                }
                case 5 -> {
                    int __ev = r.readVarint32();
                    try {
                        chunk_offsets_ptype = ProtoPType.fromValue(__ev);
                    } catch (IllegalArgumentException __iae) {
                        throw new IOException("unknown ProtoPType value: " + __ev);
                    }
                }
                case 6 -> {
                    offset_within_chunk = r.readVarint64();
                }
                default -> r.skipField(tag & 7);
            }
        }
        return new ProtoPatchesMetadata(len, offset, indices_ptype, chunk_offsets_len, chunk_offsets_ptype, offset_within_chunk);
    }

    /// Encodes this record to a proto3-wire-format byte array.
    /// @return encoded bytes
    public byte[] encode() {
        ProtoWriter w = new ProtoWriter();
        encodeTo(w);
        return w.toByteArray();
    }

    void encodeTo(ProtoWriter w) {
        if (len != 0L) {
            w.writeTag(1, 0);
            w.writeVarint64(len);
        }
        if (offset != 0L) {
            w.writeTag(2, 0);
            w.writeVarint64(offset);
        }
        if (indices_ptype.value() != 0) {
            w.writeTag(3, 0);
            w.writeVarint32(indices_ptype.value());
        }
        if (chunk_offsets_len != null) {
            w.writeTag(4, 0);
            w.writeVarint64(chunk_offsets_len);
        }
        if (chunk_offsets_ptype != null) {
            w.writeTag(5, 0);
            w.writeVarint32(chunk_offsets_ptype.value());
        }
        if (offset_within_chunk != null) {
            w.writeTag(6, 0);
            w.writeVarint64(offset_within_chunk);
        }
    }
}
