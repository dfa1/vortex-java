package io.github.dfa1.vortex.core.proto;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import javax.annotation.processing.Generated;

/// Generated from proto3 message {@code vortex.encodings.ParquetVariantMetadata}.
/// Do not edit by hand — regenerate via {@code ./mvnw generate-sources -pl core -P regenerate-sources}.
/// @param has_value field tag 1
/// @param typed_value_dtype field tag 2
/// @param value_nullable field tag 3
@Generated("io.github.dfa1.vortex.protogen.CodeGen")
public record ProtoParquetVariantMetadata(
        boolean has_value,
        ProtoDType typed_value_dtype,
        boolean value_nullable
) {

    /// Decodes a {@code vortex.encodings.ParquetVariantMetadata} from a slice of a memory segment.
    /// @param __seg backing segment
    /// @param __off start offset in bytes
    /// @param __len payload length in bytes
    /// @return decoded record
    /// @throws IOException if the slice is malformed or truncated
    public static ProtoParquetVariantMetadata decode(MemorySegment __seg, long __off, long __len) throws IOException {
        ProtoReader r = new ProtoReader(__seg, __off, __len);
        boolean has_value = false;
        ProtoDType typed_value_dtype = null;
        boolean value_nullable = false;
        while (r.hasMore()) {
            int tag = r.readVarint32();
            switch (tag >>> 3) {
                case 1 -> {
                    has_value = r.readBool();
                }
                case 2 -> {
                    MemorySegment __slice = r.readLenDelimSegment();
                    typed_value_dtype = ProtoDType.decode(__slice, 0, __slice.byteSize());
                }
                case 3 -> {
                    value_nullable = r.readBool();
                }
                default -> r.skipField(tag & 7);
            }
        }
        return new ProtoParquetVariantMetadata(has_value, typed_value_dtype, value_nullable);
    }

    /// Encodes this record to a proto3-wire-format byte array.
    /// @return encoded bytes
    public byte[] encode() {
        ProtoWriter w = new ProtoWriter();
        encodeTo(w);
        return w.toByteArray();
    }

    void encodeTo(ProtoWriter w) {
        if (has_value) {
            w.writeTag(1, 0);
            w.writeBool(has_value);
        }
        if (typed_value_dtype != null) {
            w.writeTag(2, 2);
            int __mark = w.beginLenDelim();
            typed_value_dtype.encodeTo(w);
            w.endLenDelim(__mark);
        }
        if (value_nullable) {
            w.writeTag(3, 0);
            w.writeBool(value_nullable);
        }
    }
}
