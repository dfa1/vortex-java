package io.github.dfa1.vortex.proto;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import javax.annotation.processing.Generated;

/// Generated from proto3 message {@code vortex.encodings.RunEndMetadata}.
/// Do not edit by hand — regenerate via {@code ./mvnw generate-sources -pl core -P regenerate-sources}.
/// @param ends_ptype field tag 1
/// @param num_runs field tag 2
/// @param offset field tag 3
@Generated("io.github.dfa1.vortex.protogen.CodeGen")
public record RunEndMetadata(
        PType ends_ptype,
        long num_runs,
        long offset
) {

    /// Decodes a {@code vortex.encodings.RunEndMetadata} from a slice of a memory segment.
    /// @param __seg backing segment
    /// @param __off start offset in bytes
    /// @param __len payload length in bytes
    /// @return decoded record
    /// @throws IOException if the slice is malformed or truncated
    public static RunEndMetadata decode(MemorySegment __seg, long __off, long __len) throws IOException {
        ProtoReader r = new ProtoReader(__seg, __off, __len);
        PType ends_ptype = PType.fromValue(0);
        long num_runs = 0;
        long offset = 0;
        while (r.hasMore()) {
            int tag = r.readVarint32();
            switch (tag >>> 3) {
                case 1 -> {
                    ends_ptype = PType.fromValue(r.readVarint32());
                }
                case 2 -> {
                    num_runs = r.readVarint64();
                }
                case 3 -> {
                    offset = r.readVarint64();
                }
                default -> r.skipField(tag & 7);
            }
        }
        return new RunEndMetadata(ends_ptype, num_runs, offset);
    }

    /// Encodes this record to a proto3-wire-format byte array.
    /// @return encoded bytes
    public byte[] encode() {
        ProtoWriter w = new ProtoWriter();
        if (ends_ptype.value() != 0) {
            w.writeTag(1, 0);
            w.writeVarint32(ends_ptype.value());
        }
        if (num_runs != 0L) {
            w.writeTag(2, 0);
            w.writeVarint64(num_runs);
        }
        if (offset != 0L) {
            w.writeTag(3, 0);
            w.writeVarint64(offset);
        }
        return w.toByteArray();
    }
}
