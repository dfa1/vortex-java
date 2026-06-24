package io.github.dfa1.vortex.proto;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import javax.annotation.processing.Generated;

/// Generated from proto3 message {@code vortex.encodings.DateTimePartsMetadata}.
/// Do not edit by hand — regenerate via {@code ./mvnw generate-sources -pl core -P regenerate-sources}.
/// @param days_ptype field tag 1
/// @param seconds_ptype field tag 2
/// @param subseconds_ptype field tag 3
@Generated("io.github.dfa1.vortex.protogen.CodeGen")
public record ProtoDateTimePartsMetadata(
        ProtoPType days_ptype,
        ProtoPType seconds_ptype,
        ProtoPType subseconds_ptype
) {

    /// Decodes a {@code vortex.encodings.DateTimePartsMetadata} from a slice of a memory segment.
    /// @param __seg backing segment
    /// @param __off start offset in bytes
    /// @param __len payload length in bytes
    /// @return decoded record
    /// @throws IOException if the slice is malformed or truncated
    public static ProtoDateTimePartsMetadata decode(MemorySegment __seg, long __off, long __len) throws IOException {
        ProtoReader r = new ProtoReader(__seg, __off, __len);
        ProtoPType days_ptype = ProtoPType.U8;
        ProtoPType seconds_ptype = ProtoPType.U8;
        ProtoPType subseconds_ptype = ProtoPType.U8;
        while (r.hasMore()) {
            int tag = r.readVarint32();
            switch (tag >>> 3) {
                case 1 -> {
                    int __ev = r.readVarint32();
                    try {
                        days_ptype = ProtoPType.fromValue(__ev);
                    } catch (IllegalArgumentException __iae) {
                        throw new IOException("unknown ProtoPType value: " + __ev);
                    }
                }
                case 2 -> {
                    int __ev = r.readVarint32();
                    try {
                        seconds_ptype = ProtoPType.fromValue(__ev);
                    } catch (IllegalArgumentException __iae) {
                        throw new IOException("unknown ProtoPType value: " + __ev);
                    }
                }
                case 3 -> {
                    int __ev = r.readVarint32();
                    try {
                        subseconds_ptype = ProtoPType.fromValue(__ev);
                    } catch (IllegalArgumentException __iae) {
                        throw new IOException("unknown ProtoPType value: " + __ev);
                    }
                }
                default -> r.skipField(tag & 7);
            }
        }
        return new ProtoDateTimePartsMetadata(days_ptype, seconds_ptype, subseconds_ptype);
    }

    /// Encodes this record to a proto3-wire-format byte array.
    /// @return encoded bytes
    public byte[] encode() {
        ProtoWriter w = new ProtoWriter();
        encodeTo(w);
        return w.toByteArray();
    }

    void encodeTo(ProtoWriter w) {
        if (days_ptype.value() != 0) {
            w.writeTag(1, 0);
            w.writeVarint32(days_ptype.value());
        }
        if (seconds_ptype.value() != 0) {
            w.writeTag(2, 0);
            w.writeVarint32(seconds_ptype.value());
        }
        if (subseconds_ptype.value() != 0) {
            w.writeTag(3, 0);
            w.writeVarint32(subseconds_ptype.value());
        }
    }
}
