package io.github.dfa1.vortex.core.proto;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import javax.annotation.processing.Generated;

/// Generated from proto3 message {@code vortex.dtype.DType}.
/// Do not edit by hand — regenerate via {@code ./mvnw generate-sources -pl core -P regenerate-sources}.
/// @param null_ field tag 1
/// @param bool field tag 2
/// @param primitive field tag 3
/// @param decimal field tag 4
/// @param utf8 field tag 5
/// @param binary field tag 6
/// @param struct field tag 7
/// @param list field tag 8
/// @param extension field tag 9
/// @param fixed_size_list field tag 10
/// @param variant field tag 11
/// @param union field tag 12
/// @param map field tag 13
@Generated("io.github.dfa1.vortex.protogen.CodeGen")
public record ProtoDType(
        ProtoNull null_,
        ProtoBool bool,
        ProtoPrimitive primitive,
        ProtoDecimal decimal,
        ProtoUtf8 utf8,
        ProtoBinary binary,
        ProtoStruct struct,
        ProtoList list,
        ProtoExtension extension,
        ProtoFixedSizeList fixed_size_list,
        ProtoVariant variant,
        ProtoUnion union,
        ProtoMap map
) {

    /// Decodes a {@code vortex.dtype.DType} from a slice of a memory segment.
    /// @param __seg backing segment
    /// @param __off start offset in bytes
    /// @param __len payload length in bytes
    /// @return decoded record
    /// @throws IOException if the slice is malformed or truncated
    public static ProtoDType decode(MemorySegment __seg, long __off, long __len) throws IOException {
        ProtoReader r = new ProtoReader(__seg, __off, __len);
        ProtoNull null_ = null;
        ProtoBool bool = null;
        ProtoPrimitive primitive = null;
        ProtoDecimal decimal = null;
        ProtoUtf8 utf8 = null;
        ProtoBinary binary = null;
        ProtoStruct struct = null;
        ProtoList list = null;
        ProtoExtension extension = null;
        ProtoFixedSizeList fixed_size_list = null;
        ProtoVariant variant = null;
        ProtoUnion union = null;
        ProtoMap map = null;
        while (r.hasMore()) {
            int tag = r.readVarint32();
            switch (tag >>> 3) {
                case 1 -> {
                    MemorySegment __slice = r.readLenDelimSegment();
                    null_ = ProtoNull.decode(__slice, 0, __slice.byteSize());
                }
                case 2 -> {
                    MemorySegment __slice = r.readLenDelimSegment();
                    bool = ProtoBool.decode(__slice, 0, __slice.byteSize());
                }
                case 3 -> {
                    MemorySegment __slice = r.readLenDelimSegment();
                    primitive = ProtoPrimitive.decode(__slice, 0, __slice.byteSize());
                }
                case 4 -> {
                    MemorySegment __slice = r.readLenDelimSegment();
                    decimal = ProtoDecimal.decode(__slice, 0, __slice.byteSize());
                }
                case 5 -> {
                    MemorySegment __slice = r.readLenDelimSegment();
                    utf8 = ProtoUtf8.decode(__slice, 0, __slice.byteSize());
                }
                case 6 -> {
                    MemorySegment __slice = r.readLenDelimSegment();
                    binary = ProtoBinary.decode(__slice, 0, __slice.byteSize());
                }
                case 7 -> {
                    MemorySegment __slice = r.readLenDelimSegment();
                    struct = ProtoStruct.decode(__slice, 0, __slice.byteSize());
                }
                case 8 -> {
                    MemorySegment __slice = r.readLenDelimSegment();
                    list = ProtoList.decode(__slice, 0, __slice.byteSize());
                }
                case 9 -> {
                    MemorySegment __slice = r.readLenDelimSegment();
                    extension = ProtoExtension.decode(__slice, 0, __slice.byteSize());
                }
                case 10 -> {
                    MemorySegment __slice = r.readLenDelimSegment();
                    fixed_size_list = ProtoFixedSizeList.decode(__slice, 0, __slice.byteSize());
                }
                case 11 -> {
                    MemorySegment __slice = r.readLenDelimSegment();
                    variant = ProtoVariant.decode(__slice, 0, __slice.byteSize());
                }
                case 12 -> {
                    MemorySegment __slice = r.readLenDelimSegment();
                    union = ProtoUnion.decode(__slice, 0, __slice.byteSize());
                }
                case 13 -> {
                    MemorySegment __slice = r.readLenDelimSegment();
                    map = ProtoMap.decode(__slice, 0, __slice.byteSize());
                }
                default -> r.skipField(tag & 7);
            }
        }
        return new ProtoDType(null_, bool, primitive, decimal, utf8, binary, struct, list, extension, fixed_size_list, variant, union, map);
    }

    /// Encodes this record to a proto3-wire-format byte array.
    /// @return encoded bytes
    public byte[] encode() {
        ProtoWriter w = new ProtoWriter();
        encodeTo(w);
        return w.toByteArray();
    }

    void encodeTo(ProtoWriter w) {
        if (null_ != null) {
            w.writeTag(1, 2);
            int __mark = w.beginLenDelim();
            null_.encodeTo(w);
            w.endLenDelim(__mark);
        }
        if (bool != null) {
            w.writeTag(2, 2);
            int __mark = w.beginLenDelim();
            bool.encodeTo(w);
            w.endLenDelim(__mark);
        }
        if (primitive != null) {
            w.writeTag(3, 2);
            int __mark = w.beginLenDelim();
            primitive.encodeTo(w);
            w.endLenDelim(__mark);
        }
        if (decimal != null) {
            w.writeTag(4, 2);
            int __mark = w.beginLenDelim();
            decimal.encodeTo(w);
            w.endLenDelim(__mark);
        }
        if (utf8 != null) {
            w.writeTag(5, 2);
            int __mark = w.beginLenDelim();
            utf8.encodeTo(w);
            w.endLenDelim(__mark);
        }
        if (binary != null) {
            w.writeTag(6, 2);
            int __mark = w.beginLenDelim();
            binary.encodeTo(w);
            w.endLenDelim(__mark);
        }
        if (struct != null) {
            w.writeTag(7, 2);
            int __mark = w.beginLenDelim();
            struct.encodeTo(w);
            w.endLenDelim(__mark);
        }
        if (list != null) {
            w.writeTag(8, 2);
            int __mark = w.beginLenDelim();
            list.encodeTo(w);
            w.endLenDelim(__mark);
        }
        if (extension != null) {
            w.writeTag(9, 2);
            int __mark = w.beginLenDelim();
            extension.encodeTo(w);
            w.endLenDelim(__mark);
        }
        if (fixed_size_list != null) {
            w.writeTag(10, 2);
            int __mark = w.beginLenDelim();
            fixed_size_list.encodeTo(w);
            w.endLenDelim(__mark);
        }
        if (variant != null) {
            w.writeTag(11, 2);
            int __mark = w.beginLenDelim();
            variant.encodeTo(w);
            w.endLenDelim(__mark);
        }
        if (union != null) {
            w.writeTag(12, 2);
            int __mark = w.beginLenDelim();
            union.encodeTo(w);
            w.endLenDelim(__mark);
        }
        if (map != null) {
            w.writeTag(13, 2);
            int __mark = w.beginLenDelim();
            map.encodeTo(w);
            w.endLenDelim(__mark);
        }
    }

    /// Factory for oneof case {@code null} (field tag 1).
    /// @param value the value to set
    /// @return a record with only the {@code null} component set
    public static ProtoDType ofNull(ProtoNull value) {
        return new ProtoDType(value, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    /// Factory for oneof case {@code bool} (field tag 2).
    /// @param value the value to set
    /// @return a record with only the {@code bool} component set
    public static ProtoDType ofBool(ProtoBool value) {
        return new ProtoDType(null, value, null, null, null, null, null, null, null, null, null, null, null);
    }

    /// Factory for oneof case {@code primitive} (field tag 3).
    /// @param value the value to set
    /// @return a record with only the {@code primitive} component set
    public static ProtoDType ofPrimitive(ProtoPrimitive value) {
        return new ProtoDType(null, null, value, null, null, null, null, null, null, null, null, null, null);
    }

    /// Factory for oneof case {@code decimal} (field tag 4).
    /// @param value the value to set
    /// @return a record with only the {@code decimal} component set
    public static ProtoDType ofDecimal(ProtoDecimal value) {
        return new ProtoDType(null, null, null, value, null, null, null, null, null, null, null, null, null);
    }

    /// Factory for oneof case {@code utf8} (field tag 5).
    /// @param value the value to set
    /// @return a record with only the {@code utf8} component set
    public static ProtoDType ofUtf8(ProtoUtf8 value) {
        return new ProtoDType(null, null, null, null, value, null, null, null, null, null, null, null, null);
    }

    /// Factory for oneof case {@code binary} (field tag 6).
    /// @param value the value to set
    /// @return a record with only the {@code binary} component set
    public static ProtoDType ofBinary(ProtoBinary value) {
        return new ProtoDType(null, null, null, null, null, value, null, null, null, null, null, null, null);
    }

    /// Factory for oneof case {@code struct} (field tag 7).
    /// @param value the value to set
    /// @return a record with only the {@code struct} component set
    public static ProtoDType ofStruct(ProtoStruct value) {
        return new ProtoDType(null, null, null, null, null, null, value, null, null, null, null, null, null);
    }

    /// Factory for oneof case {@code list} (field tag 8).
    /// @param value the value to set
    /// @return a record with only the {@code list} component set
    public static ProtoDType ofList(ProtoList value) {
        return new ProtoDType(null, null, null, null, null, null, null, value, null, null, null, null, null);
    }

    /// Factory for oneof case {@code extension} (field tag 9).
    /// @param value the value to set
    /// @return a record with only the {@code extension} component set
    public static ProtoDType ofExtension(ProtoExtension value) {
        return new ProtoDType(null, null, null, null, null, null, null, null, value, null, null, null, null);
    }

    /// Factory for oneof case {@code fixed_size_list} (field tag 10).
    /// @param value the value to set
    /// @return a record with only the {@code fixed_size_list} component set
    public static ProtoDType ofFixedSizeList(ProtoFixedSizeList value) {
        return new ProtoDType(null, null, null, null, null, null, null, null, null, value, null, null, null);
    }

    /// Factory for oneof case {@code variant} (field tag 11).
    /// @param value the value to set
    /// @return a record with only the {@code variant} component set
    public static ProtoDType ofVariant(ProtoVariant value) {
        return new ProtoDType(null, null, null, null, null, null, null, null, null, null, value, null, null);
    }

    /// Factory for oneof case {@code union} (field tag 12).
    /// @param value the value to set
    /// @return a record with only the {@code union} component set
    public static ProtoDType ofUnion(ProtoUnion value) {
        return new ProtoDType(null, null, null, null, null, null, null, null, null, null, null, value, null);
    }

    /// Factory for oneof case {@code map} (field tag 13).
    /// @param value the value to set
    /// @return a record with only the {@code map} component set
    public static ProtoDType ofMap(ProtoMap value) {
        return new ProtoDType(null, null, null, null, null, null, null, null, null, null, null, null, value);
    }
}
