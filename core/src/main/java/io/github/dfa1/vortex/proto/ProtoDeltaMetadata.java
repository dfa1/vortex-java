package io.github.dfa1.vortex.proto;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import javax.annotation.processing.Generated;

/// Generated from proto3 message {@code vortex.encodings.DeltaMetadata}.
/// Do not edit by hand — regenerate via {@code ./mvnw generate-sources -pl core -P regenerate-sources}.
/// @param deltas_len field tag 1
/// @param offset field tag 2
@Generated("io.github.dfa1.vortex.protogen.CodeGen")
public record ProtoDeltaMetadata(
        long deltas_len,
        int offset
) {

    /// Decodes a {@code vortex.encodings.DeltaMetadata} from a slice of a memory segment.
    /// @param __seg backing segment
    /// @param __off start offset in bytes
    /// @param __len payload length in bytes
    /// @return decoded record
    /// @throws IOException if the slice is malformed or truncated
    public static ProtoDeltaMetadata decode(MemorySegment __seg, long __off, long __len) throws IOException {
        ProtoReader r = new ProtoReader(__seg, __off, __len);
        long deltas_len = 0;
        int offset = 0;
        while (r.hasMore()) {
            int tag = r.readVarint32();
            switch (tag >>> 3) {
                case 1 -> {
                    deltas_len = r.readVarint64();
                }
                case 2 -> {
                    offset = r.readVarint32();
                }
                default -> r.skipField(tag & 7);
            }
        }
        return new ProtoDeltaMetadata(deltas_len, offset);
    }

    /// Encodes this record to a proto3-wire-format byte array.
    /// @return encoded bytes
    public byte[] encode() {
        ProtoWriter w = new ProtoWriter();
        encodeTo(w);
        return w.toByteArray();
    }

    void encodeTo(ProtoWriter w) {
        if (deltas_len != 0L) {
            w.writeTag(1, 0);
            w.writeVarint64(deltas_len);
        }
        if (offset != 0) {
            w.writeTag(2, 0);
            w.writeVarint32(offset);
        }
    }
}
