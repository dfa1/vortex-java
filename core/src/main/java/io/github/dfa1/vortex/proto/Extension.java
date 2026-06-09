package io.github.dfa1.vortex.proto;

import java.io.IOException;
import java.lang.foreign.MemorySegment;

/// Generated from proto3 message {@code vortex.dtype.Extension}.
/// Do not edit by hand — regenerate via {@code ./mvnw generate-sources -pl core -P regenerate-sources}.
/// @param id field tag 1
/// @param storage_dtype field tag 2
/// @param metadata field tag 3
public record Extension(
        String id,
        DType storage_dtype,
        byte[] metadata
) {

    /// Decodes a {@code vortex.dtype.Extension} from a slice of a memory segment.
    /// @param __seg backing segment
    /// @param __off start offset in bytes
    /// @param __len payload length in bytes
    /// @return decoded record
    /// @throws IOException if the slice is malformed or truncated
    public static Extension decode(MemorySegment __seg, long __off, long __len) throws IOException {
        ProtoReader r = new ProtoReader(__seg, __off, __len);
        String id = "";
        DType storage_dtype = null;
        byte[] metadata = null;
        while (r.hasMore()) {
            int tag = r.readVarint32();
            switch (tag >>> 3) {
                case 1 -> {
                    id = r.readString();
                }
                case 2 -> {
                    MemorySegment __slice = r.readLenDelimSegment();
                    storage_dtype = DType.decode(__slice, 0, __slice.byteSize());
                }
                case 3 -> {
                    metadata = r.readBytes();
                }
                default -> r.skipField(tag & 7);
            }
        }
        return new Extension(id, storage_dtype, metadata);
    }

    /// Encodes this record to a proto3-wire-format byte array.
    /// @return encoded bytes
    public byte[] encode() {
        ProtoWriter w = new ProtoWriter();
        if (!id.isEmpty()) {
            w.writeTag(1, 2);
            w.writeString(id);
        }
        if (storage_dtype != null) {
            w.writeTag(2, 2);
            w.writeEmbedded(storage_dtype.encode());
        }
        if (metadata != null) {
            w.writeTag(3, 2);
            w.writeBytes(metadata);
        }
        return w.toByteArray();
    }
}
