package io.github.dfa1.vortex.proto;

import java.io.IOException;
import java.lang.foreign.MemorySegment;

/// Generated from proto3 message {@code vortex.encodings.DictMetadata}.
/// Do not edit by hand — regenerate via {@code ./mvnw generate-sources -pl core -P regenerate-sources}.
/// @param values_len field tag 1
/// @param codes_ptype field tag 2
/// @param is_nullable_codes field tag 3
/// @param all_values_referenced field tag 4
public record DictMetadata(
        int values_len,
        PType codes_ptype,
        Boolean is_nullable_codes,
        Boolean all_values_referenced
) {

    /// Decodes a {@code vortex.encodings.DictMetadata} from a slice of a memory segment.
    /// @param __seg backing segment
    /// @param __off start offset in bytes
    /// @param __len payload length in bytes
    /// @return decoded record
    /// @throws IOException if the slice is malformed or truncated
    public static DictMetadata decode(MemorySegment __seg, long __off, long __len) throws IOException {
        ProtoReader r = new ProtoReader(__seg, __off, __len);
        int values_len = 0;
        PType codes_ptype = PType.fromValue(0);
        Boolean is_nullable_codes = null;
        Boolean all_values_referenced = null;
        while (r.hasMore()) {
            int tag = r.readVarint32();
            switch (tag >>> 3) {
                case 1 -> {
                    values_len = r.readVarint32();
                }
                case 2 -> {
                    codes_ptype = PType.fromValue(r.readVarint32());
                }
                case 3 -> {
                    is_nullable_codes = r.readBool();
                }
                case 4 -> {
                    all_values_referenced = r.readBool();
                }
                default -> r.skipField(tag & 7);
            }
        }
        return new DictMetadata(values_len, codes_ptype, is_nullable_codes, all_values_referenced);
    }

    /// Encodes this record to a proto3-wire-format byte array.
    /// @return encoded bytes
    public byte[] encode() {
        ProtoWriter w = new ProtoWriter();
        if (values_len != 0) {
            w.writeTag(1, 0);
            w.writeVarint32(values_len);
        }
        if (codes_ptype.value() != 0) {
            w.writeTag(2, 0);
            w.writeVarint32(codes_ptype.value());
        }
        if (is_nullable_codes != null) {
            w.writeTag(3, 0);
            w.writeBool(is_nullable_codes);
        }
        if (all_values_referenced != null) {
            w.writeTag(4, 0);
            w.writeBool(all_values_referenced);
        }
        return w.toByteArray();
    }
}
