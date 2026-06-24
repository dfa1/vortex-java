package io.github.dfa1.vortex.core.proto;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.util.Arrays;
import javax.annotation.processing.Generated;

/// Generated from proto3 message {@code vortex.dtype.Extension}.
/// Do not edit by hand — regenerate via {@code ./mvnw generate-sources -pl core -P regenerate-sources}.
/// @param id field tag 1
/// @param storage_dtype field tag 2
/// @param metadata field tag 3
@Generated("io.github.dfa1.vortex.protogen.CodeGen")
public record ProtoExtension(
        String id,
        ProtoDType storage_dtype,
        byte[] metadata
) {

    /// Decodes a {@code vortex.dtype.Extension} from a slice of a memory segment.
    /// @param __seg backing segment
    /// @param __off start offset in bytes
    /// @param __len payload length in bytes
    /// @return decoded record
    /// @throws IOException if the slice is malformed or truncated
    public static ProtoExtension decode(MemorySegment __seg, long __off, long __len) throws IOException {
        ProtoReader r = new ProtoReader(__seg, __off, __len);
        String id = "";
        ProtoDType storage_dtype = null;
        byte[] metadata = null;
        while (r.hasMore()) {
            int tag = r.readVarint32();
            switch (tag >>> 3) {
                case 1 -> {
                    id = r.readString();
                }
                case 2 -> {
                    MemorySegment __slice = r.readLenDelimSegment();
                    storage_dtype = ProtoDType.decode(__slice, 0, __slice.byteSize());
                }
                case 3 -> {
                    metadata = r.readBytes();
                }
                default -> r.skipField(tag & 7);
            }
        }
        return new ProtoExtension(id, storage_dtype, metadata);
    }

    /// Encodes this record to a proto3-wire-format byte array.
    /// @return encoded bytes
    public byte[] encode() {
        ProtoWriter w = new ProtoWriter();
        encodeTo(w);
        return w.toByteArray();
    }

    void encodeTo(ProtoWriter w) {
        if (id != null && !id.isEmpty()) {
            w.writeTag(1, 2);
            w.writeString(id);
        }
        if (storage_dtype != null) {
            w.writeTag(2, 2);
            int __mark = w.beginLenDelim();
            storage_dtype.encodeTo(w);
            w.endLenDelim(__mark);
        }
        if (metadata != null) {
            w.writeTag(3, 2);
            w.writeBytes(metadata);
        }
    }

    @Override
    public boolean equals(Object __o) {
        if (this == __o) {
            return true;
        }
        if (!(__o instanceof ProtoExtension __that)) {
            return false;
        }
        return java.util.Objects.equals(id, __that.id)
            && java.util.Objects.equals(storage_dtype, __that.storage_dtype)
            && java.util.Arrays.equals(metadata, __that.metadata);
    }

    @Override
    public int hashCode() {
        int __h = 1;
        __h = 31 * __h + java.util.Objects.hashCode(id);
        __h = 31 * __h + java.util.Objects.hashCode(storage_dtype);
        __h = 31 * __h + java.util.Arrays.hashCode(metadata);
        return __h;
    }
}
