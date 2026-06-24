package io.github.dfa1.vortex.core.proto;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import javax.annotation.processing.Generated;

/// Generated from proto3 message {@code vortex.dtype.Decimal}.
/// Do not edit by hand — regenerate via {@code ./mvnw generate-sources -pl core -P regenerate-sources}.
/// @param precision field tag 1
/// @param scale field tag 2
/// @param nullable field tag 3
@Generated("io.github.dfa1.vortex.protogen.CodeGen")
public record ProtoDecimal(
        int precision,
        int scale,
        boolean nullable
) {

    /// Decodes a {@code vortex.dtype.Decimal} from a slice of a memory segment.
    /// @param __seg backing segment
    /// @param __off start offset in bytes
    /// @param __len payload length in bytes
    /// @return decoded record
    /// @throws IOException if the slice is malformed or truncated
    public static ProtoDecimal decode(MemorySegment __seg, long __off, long __len) throws IOException {
        ProtoReader r = new ProtoReader(__seg, __off, __len);
        int precision = 0;
        int scale = 0;
        boolean nullable = false;
        while (r.hasMore()) {
            int tag = r.readVarint32();
            switch (tag >>> 3) {
                case 1 -> {
                    precision = r.readVarint32();
                }
                case 2 -> {
                    scale = r.readVarint32();
                }
                case 3 -> {
                    nullable = r.readBool();
                }
                default -> r.skipField(tag & 7);
            }
        }
        return new ProtoDecimal(precision, scale, nullable);
    }

    /// Encodes this record to a proto3-wire-format byte array.
    /// @return encoded bytes
    public byte[] encode() {
        ProtoWriter w = new ProtoWriter();
        encodeTo(w);
        return w.toByteArray();
    }

    void encodeTo(ProtoWriter w) {
        if (precision != 0) {
            w.writeTag(1, 0);
            w.writeVarint32(precision);
        }
        if (scale != 0) {
            w.writeTag(2, 0);
            w.writeVarint32(scale);
        }
        if (nullable) {
            w.writeTag(3, 0);
            w.writeBool(nullable);
        }
    }
}
