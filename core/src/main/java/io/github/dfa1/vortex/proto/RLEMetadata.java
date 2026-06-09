package io.github.dfa1.vortex.proto;

import java.io.IOException;
import java.lang.foreign.MemorySegment;

/// Generated from proto3 message {@code vortex.encodings.RLEMetadata}.
/// Do not edit by hand — regenerate via {@code ./mvnw generate-sources -pl core -P regenerate-sources}.
/// @param values_len field tag 1
/// @param indices_len field tag 2
/// @param indices_ptype field tag 3
/// @param values_idx_offsets_len field tag 4
/// @param values_idx_offsets_ptype field tag 5
/// @param offset field tag 6
public record RLEMetadata(
        long values_len,
        long indices_len,
        PType indices_ptype,
        long values_idx_offsets_len,
        PType values_idx_offsets_ptype,
        long offset
) {

    /// Decodes a {@code vortex.encodings.RLEMetadata} from a slice of a memory segment.
    /// @param __seg backing segment
    /// @param __off start offset in bytes
    /// @param __len payload length in bytes
    /// @return decoded record
    /// @throws IOException if the slice is malformed or truncated
    public static RLEMetadata decode(MemorySegment __seg, long __off, long __len) throws IOException {
        ProtoReader r = new ProtoReader(__seg, __off, __len);
        long values_len = 0;
        long indices_len = 0;
        PType indices_ptype = PType.fromValue(0);
        long values_idx_offsets_len = 0;
        PType values_idx_offsets_ptype = PType.fromValue(0);
        long offset = 0;
        while (r.hasMore()) {
            int tag = r.readVarint32();
            switch (tag >>> 3) {
                case 1 -> {
                    values_len = r.readVarint64();
                }
                case 2 -> {
                    indices_len = r.readVarint64();
                }
                case 3 -> {
                    indices_ptype = PType.fromValue(r.readVarint32());
                }
                case 4 -> {
                    values_idx_offsets_len = r.readVarint64();
                }
                case 5 -> {
                    values_idx_offsets_ptype = PType.fromValue(r.readVarint32());
                }
                case 6 -> {
                    offset = r.readVarint64();
                }
                default -> r.skipField(tag & 7);
            }
        }
        return new RLEMetadata(values_len, indices_len, indices_ptype, values_idx_offsets_len, values_idx_offsets_ptype, offset);
    }

    /// Encodes this record to a proto3-wire-format byte array.
    /// @return encoded bytes
    public byte[] encode() {
        ProtoWriter w = new ProtoWriter();
        if (values_len != 0L) {
            w.writeTag(1, 0);
            w.writeVarint64(values_len);
        }
        if (indices_len != 0L) {
            w.writeTag(2, 0);
            w.writeVarint64(indices_len);
        }
        if (indices_ptype.value() != 0) {
            w.writeTag(3, 0);
            w.writeVarint32(indices_ptype.value());
        }
        if (values_idx_offsets_len != 0L) {
            w.writeTag(4, 0);
            w.writeVarint64(values_idx_offsets_len);
        }
        if (values_idx_offsets_ptype.value() != 0) {
            w.writeTag(5, 0);
            w.writeVarint32(values_idx_offsets_ptype.value());
        }
        if (offset != 0L) {
            w.writeTag(6, 0);
            w.writeVarint64(offset);
        }
        return w.toByteArray();
    }
}
