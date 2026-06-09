package io.github.dfa1.vortex.proto;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import javax.annotation.processing.Generated;

/// Generated from proto3 message {@code vortex.scalar.Scalar}.
/// Do not edit by hand — regenerate via {@code ./mvnw generate-sources -pl core -P regenerate-sources}.
/// @param dtype field tag 1
/// @param value field tag 2
@Generated("io.github.dfa1.vortex.protogen.CodeGen")
public record Scalar(
        DType dtype,
        ScalarValue value
) {

    /// Decodes a {@code vortex.scalar.Scalar} from a slice of a memory segment.
    /// @param __seg backing segment
    /// @param __off start offset in bytes
    /// @param __len payload length in bytes
    /// @return decoded record
    /// @throws IOException if the slice is malformed or truncated
    public static Scalar decode(MemorySegment __seg, long __off, long __len) throws IOException {
        ProtoReader r = new ProtoReader(__seg, __off, __len);
        DType dtype = null;
        ScalarValue value = null;
        while (r.hasMore()) {
            int tag = r.readVarint32();
            switch (tag >>> 3) {
                case 1 -> {
                    MemorySegment __slice = r.readLenDelimSegment();
                    dtype = DType.decode(__slice, 0, __slice.byteSize());
                }
                case 2 -> {
                    MemorySegment __slice = r.readLenDelimSegment();
                    value = ScalarValue.decode(__slice, 0, __slice.byteSize());
                }
                default -> r.skipField(tag & 7);
            }
        }
        return new Scalar(dtype, value);
    }

    /// Encodes this record to a proto3-wire-format byte array.
    /// @return encoded bytes
    public byte[] encode() {
        ProtoWriter w = new ProtoWriter();
        if (dtype != null) {
            w.writeTag(1, 2);
            w.writeEmbedded(dtype.encode());
        }
        if (value != null) {
            w.writeTag(2, 2);
            w.writeEmbedded(value.encode());
        }
        return w.toByteArray();
    }
}
