package io.github.dfa1.vortex.proto;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import javax.annotation.processing.Generated;

/// Generated from proto3 message {@code vortex.encodings.ALPRDMetadata}.
/// Do not edit by hand — regenerate via {@code ./mvnw generate-sources -pl core -P regenerate-sources}.
/// @param right_bit_width field tag 1
/// @param dict_len field tag 2
/// @param dict field tag 3
/// @param left_parts_ptype field tag 4
/// @param patches field tag 5
@Generated("io.github.dfa1.vortex.protogen.CodeGen")
public record ALPRDMetadata(
        int right_bit_width,
        int dict_len,
        java.util.List<Integer> dict,
        PType left_parts_ptype,
        PatchesMetadata patches
) {

    /// Decodes a {@code vortex.encodings.ALPRDMetadata} from a slice of a memory segment.
    /// @param __seg backing segment
    /// @param __off start offset in bytes
    /// @param __len payload length in bytes
    /// @return decoded record
    /// @throws IOException if the slice is malformed or truncated
    public static ALPRDMetadata decode(MemorySegment __seg, long __off, long __len) throws IOException {
        ProtoReader r = new ProtoReader(__seg, __off, __len);
        int right_bit_width = 0;
        int dict_len = 0;
        java.util.List<Integer> dict = new java.util.ArrayList<>();
        PType left_parts_ptype = PType.U8;
        PatchesMetadata patches = null;
        while (r.hasMore()) {
            int tag = r.readVarint32();
            switch (tag >>> 3) {
                case 1 -> {
                    right_bit_width = r.readVarint32();
                }
                case 2 -> {
                    dict_len = r.readVarint32();
                }
                case 3 -> {
                    int wt = tag & 7;
                    if (wt == 2) {
                        int len = r.readVarint32();
                        java.util.List<Integer> __target = dict;
                        r.readPacked(len, reader -> __target.add(reader.readVarint32()));
                    } else {
                        dict.add(r.readVarint32());
                    }
                }
                case 4 -> {
                    int __ev = r.readVarint32();
                    try {
                        left_parts_ptype = PType.fromValue(__ev);
                    } catch (IllegalArgumentException __iae) {
                        throw new IOException("unknown PType value: " + __ev);
                    }
                }
                case 5 -> {
                    MemorySegment __slice = r.readLenDelimSegment();
                    patches = PatchesMetadata.decode(__slice, 0, __slice.byteSize());
                }
                default -> r.skipField(tag & 7);
            }
        }
        return new ALPRDMetadata(right_bit_width, dict_len, dict, left_parts_ptype, patches);
    }

    /// Encodes this record to a proto3-wire-format byte array.
    /// @return encoded bytes
    public byte[] encode() {
        ProtoWriter w = new ProtoWriter();
        encodeTo(w);
        return w.toByteArray();
    }

    void encodeTo(ProtoWriter w) {
        if (right_bit_width != 0) {
            w.writeTag(1, 0);
            w.writeVarint32(right_bit_width);
        }
        if (dict_len != 0) {
            w.writeTag(2, 0);
            w.writeVarint32(dict_len);
        }
        if (!dict.isEmpty()) {
            w.writeTag(3, 2);
            int __mark = w.beginLenDelim();
            for (Integer __v : dict) {
                w.writeVarint32(__v);
            }
            w.endLenDelim(__mark);
        }
        if (left_parts_ptype.value() != 0) {
            w.writeTag(4, 0);
            w.writeVarint32(left_parts_ptype.value());
        }
        if (patches != null) {
            w.writeTag(5, 2);
            int __mark = w.beginLenDelim();
            patches.encodeTo(w);
            w.endLenDelim(__mark);
        }
    }
}
