package io.github.dfa1.vortex.proto;

import java.io.IOException;
import java.lang.foreign.MemorySegment;

/// Generated from proto3 message {@code vortex.dtype.FixedSizeList}.
/// Do not edit by hand — regenerate via {@code ./mvnw generate-sources -pl core -P regenerate-sources}.
/// @param element_type field tag 1
/// @param size field tag 2
/// @param nullable field tag 3
public record FixedSizeList(
        DType element_type,
        int size,
        boolean nullable
) {

    /// Decodes a {@code vortex.dtype.FixedSizeList} from a slice of a memory segment.
    /// @param __seg backing segment
    /// @param __off start offset in bytes
    /// @param __len payload length in bytes
    /// @return decoded record
    /// @throws IOException if the slice is malformed or truncated
    public static FixedSizeList decode(MemorySegment __seg, long __off, long __len) throws IOException {
        ProtoReader r = new ProtoReader(__seg, __off, __len);
        DType element_type = null;
        int size = 0;
        boolean nullable = false;
        while (r.hasMore()) {
            int tag = r.readVarint32();
            switch (tag >>> 3) {
                case 1 -> {
                    MemorySegment __slice = r.readLenDelimSegment();
                    element_type = DType.decode(__slice, 0, __slice.byteSize());
                }
                case 2 -> {
                    size = r.readVarint32();
                }
                case 3 -> {
                    nullable = r.readBool();
                }
                default -> r.skipField(tag & 7);
            }
        }
        return new FixedSizeList(element_type, size, nullable);
    }

    /// Encodes this record to a proto3-wire-format byte array.
    /// @return encoded bytes
    public byte[] encode() {
        ProtoWriter w = new ProtoWriter();
        if (element_type != null) {
            w.writeTag(1, 2);
            w.writeEmbedded(element_type.encode());
        }
        if (size != 0) {
            w.writeTag(2, 0);
            w.writeVarint32(size);
        }
        if (nullable) {
            w.writeTag(3, 0);
            w.writeBool(nullable);
        }
        return w.toByteArray();
    }
}
