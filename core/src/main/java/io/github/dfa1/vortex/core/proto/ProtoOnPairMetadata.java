package io.github.dfa1.vortex.core.proto;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import javax.annotation.processing.Generated;

/// Generated from proto3 message {@code vortex.encodings.OnPairMetadata}.
/// Do not edit by hand — regenerate via {@code ./mvnw generate-sources -pl core -P regenerate-sources}.
/// @param uncompressed_lengths_ptype field tag 1
/// @param dict_size field tag 3
/// @param codes_len field tag 4
/// @param dict_offsets_ptype field tag 5
/// @param codes_ptype field tag 6
/// @param codes_offsets_ptype field tag 7
@Generated("io.github.dfa1.vortex.protogen.CodeGen")
public record ProtoOnPairMetadata(
        ProtoPType uncompressed_lengths_ptype,
        int dict_size,
        long codes_len,
        ProtoPType dict_offsets_ptype,
        ProtoPType codes_ptype,
        ProtoPType codes_offsets_ptype
) {

    /// Decodes a {@code vortex.encodings.OnPairMetadata} from a slice of a memory segment.
    /// @param __seg backing segment
    /// @param __off start offset in bytes
    /// @param __len payload length in bytes
    /// @return decoded record
    /// @throws IOException if the slice is malformed or truncated
    public static ProtoOnPairMetadata decode(MemorySegment __seg, long __off, long __len) throws IOException {
        ProtoReader r = new ProtoReader(__seg, __off, __len);
        ProtoPType uncompressed_lengths_ptype = ProtoPType.U8;
        int dict_size = 0;
        long codes_len = 0;
        ProtoPType dict_offsets_ptype = ProtoPType.U8;
        ProtoPType codes_ptype = ProtoPType.U8;
        ProtoPType codes_offsets_ptype = ProtoPType.U8;
        while (r.hasMore()) {
            int tag = r.readVarint32();
            switch (tag >>> 3) {
                case 1 -> {
                    int __ev = r.readVarint32();
                    try {
                        uncompressed_lengths_ptype = ProtoPType.fromValue(__ev);
                    } catch (IllegalArgumentException __iae) {
                        throw new IOException("unknown ProtoPType value: " + __ev);
                    }
                }
                case 3 -> {
                    dict_size = r.readVarint32();
                }
                case 4 -> {
                    codes_len = r.readVarint64();
                }
                case 5 -> {
                    int __ev = r.readVarint32();
                    try {
                        dict_offsets_ptype = ProtoPType.fromValue(__ev);
                    } catch (IllegalArgumentException __iae) {
                        throw new IOException("unknown ProtoPType value: " + __ev);
                    }
                }
                case 6 -> {
                    int __ev = r.readVarint32();
                    try {
                        codes_ptype = ProtoPType.fromValue(__ev);
                    } catch (IllegalArgumentException __iae) {
                        throw new IOException("unknown ProtoPType value: " + __ev);
                    }
                }
                case 7 -> {
                    int __ev = r.readVarint32();
                    try {
                        codes_offsets_ptype = ProtoPType.fromValue(__ev);
                    } catch (IllegalArgumentException __iae) {
                        throw new IOException("unknown ProtoPType value: " + __ev);
                    }
                }
                default -> r.skipField(tag & 7);
            }
        }
        return new ProtoOnPairMetadata(uncompressed_lengths_ptype, dict_size, codes_len, dict_offsets_ptype, codes_ptype, codes_offsets_ptype);
    }

    /// Encodes this record to a proto3-wire-format byte array.
    /// @return encoded bytes
    public byte[] encode() {
        ProtoWriter w = new ProtoWriter();
        encodeTo(w);
        return w.toByteArray();
    }

    void encodeTo(ProtoWriter w) {
        if (uncompressed_lengths_ptype.value() != 0) {
            w.writeTag(1, 0);
            w.writeVarint32(uncompressed_lengths_ptype.value());
        }
        if (dict_size != 0) {
            w.writeTag(3, 0);
            w.writeVarint32(dict_size);
        }
        if (codes_len != 0L) {
            w.writeTag(4, 0);
            w.writeVarint64(codes_len);
        }
        if (dict_offsets_ptype.value() != 0) {
            w.writeTag(5, 0);
            w.writeVarint32(dict_offsets_ptype.value());
        }
        if (codes_ptype.value() != 0) {
            w.writeTag(6, 0);
            w.writeVarint32(codes_ptype.value());
        }
        if (codes_offsets_ptype.value() != 0) {
            w.writeTag(7, 0);
            w.writeVarint32(codes_offsets_ptype.value());
        }
    }
}
