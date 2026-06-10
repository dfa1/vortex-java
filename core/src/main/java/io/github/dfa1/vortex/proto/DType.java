package io.github.dfa1.vortex.proto;

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
@Generated("io.github.dfa1.vortex.protogen.CodeGen")
public record DType(
        Null null_,
        Bool bool,
        Primitive primitive,
        Decimal decimal,
        Utf8 utf8,
        Binary binary,
        Struct struct,
        List list,
        Extension extension,
        FixedSizeList fixed_size_list,
        Variant variant,
        Union union
) {

    /// Decodes a {@code vortex.dtype.DType} from a slice of a memory segment.
    /// @param __seg backing segment
    /// @param __off start offset in bytes
    /// @param __len payload length in bytes
    /// @return decoded record
    /// @throws IOException if the slice is malformed or truncated
    public static DType decode(MemorySegment __seg, long __off, long __len) throws IOException {
        ProtoReader r = new ProtoReader(__seg, __off, __len);
        Null null_ = null;
        Bool bool = null;
        Primitive primitive = null;
        Decimal decimal = null;
        Utf8 utf8 = null;
        Binary binary = null;
        Struct struct = null;
        List list = null;
        Extension extension = null;
        FixedSizeList fixed_size_list = null;
        Variant variant = null;
        Union union = null;
        while (r.hasMore()) {
            int tag = r.readVarint32();
            switch (tag >>> 3) {
                case 1 -> {
                    MemorySegment __slice = r.readLenDelimSegment();
                    null_ = Null.decode(__slice, 0, __slice.byteSize());
                }
                case 2 -> {
                    MemorySegment __slice = r.readLenDelimSegment();
                    bool = Bool.decode(__slice, 0, __slice.byteSize());
                }
                case 3 -> {
                    MemorySegment __slice = r.readLenDelimSegment();
                    primitive = Primitive.decode(__slice, 0, __slice.byteSize());
                }
                case 4 -> {
                    MemorySegment __slice = r.readLenDelimSegment();
                    decimal = Decimal.decode(__slice, 0, __slice.byteSize());
                }
                case 5 -> {
                    MemorySegment __slice = r.readLenDelimSegment();
                    utf8 = Utf8.decode(__slice, 0, __slice.byteSize());
                }
                case 6 -> {
                    MemorySegment __slice = r.readLenDelimSegment();
                    binary = Binary.decode(__slice, 0, __slice.byteSize());
                }
                case 7 -> {
                    MemorySegment __slice = r.readLenDelimSegment();
                    struct = Struct.decode(__slice, 0, __slice.byteSize());
                }
                case 8 -> {
                    MemorySegment __slice = r.readLenDelimSegment();
                    list = List.decode(__slice, 0, __slice.byteSize());
                }
                case 9 -> {
                    MemorySegment __slice = r.readLenDelimSegment();
                    extension = Extension.decode(__slice, 0, __slice.byteSize());
                }
                case 10 -> {
                    MemorySegment __slice = r.readLenDelimSegment();
                    fixed_size_list = FixedSizeList.decode(__slice, 0, __slice.byteSize());
                }
                case 11 -> {
                    MemorySegment __slice = r.readLenDelimSegment();
                    variant = Variant.decode(__slice, 0, __slice.byteSize());
                }
                case 12 -> {
                    MemorySegment __slice = r.readLenDelimSegment();
                    union = Union.decode(__slice, 0, __slice.byteSize());
                }
                default -> r.skipField(tag & 7);
            }
        }
        return new DType(null_, bool, primitive, decimal, utf8, binary, struct, list, extension, fixed_size_list, variant, union);
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
    }

    /// Factory for oneof case {@code null} (field tag 1).
    /// @param value the value to set
    /// @return a record with only the {@code null} component set
    public static DType ofNull(Null value) {
        return new DType(value, null, null, null, null, null, null, null, null, null, null, null);
    }

    /// Factory for oneof case {@code bool} (field tag 2).
    /// @param value the value to set
    /// @return a record with only the {@code bool} component set
    public static DType ofBool(Bool value) {
        return new DType(null, value, null, null, null, null, null, null, null, null, null, null);
    }

    /// Factory for oneof case {@code primitive} (field tag 3).
    /// @param value the value to set
    /// @return a record with only the {@code primitive} component set
    public static DType ofPrimitive(Primitive value) {
        return new DType(null, null, value, null, null, null, null, null, null, null, null, null);
    }

    /// Factory for oneof case {@code decimal} (field tag 4).
    /// @param value the value to set
    /// @return a record with only the {@code decimal} component set
    public static DType ofDecimal(Decimal value) {
        return new DType(null, null, null, value, null, null, null, null, null, null, null, null);
    }

    /// Factory for oneof case {@code utf8} (field tag 5).
    /// @param value the value to set
    /// @return a record with only the {@code utf8} component set
    public static DType ofUtf8(Utf8 value) {
        return new DType(null, null, null, null, value, null, null, null, null, null, null, null);
    }

    /// Factory for oneof case {@code binary} (field tag 6).
    /// @param value the value to set
    /// @return a record with only the {@code binary} component set
    public static DType ofBinary(Binary value) {
        return new DType(null, null, null, null, null, value, null, null, null, null, null, null);
    }

    /// Factory for oneof case {@code struct} (field tag 7).
    /// @param value the value to set
    /// @return a record with only the {@code struct} component set
    public static DType ofStruct(Struct value) {
        return new DType(null, null, null, null, null, null, value, null, null, null, null, null);
    }

    /// Factory for oneof case {@code list} (field tag 8).
    /// @param value the value to set
    /// @return a record with only the {@code list} component set
    public static DType ofList(List value) {
        return new DType(null, null, null, null, null, null, null, value, null, null, null, null);
    }

    /// Factory for oneof case {@code extension} (field tag 9).
    /// @param value the value to set
    /// @return a record with only the {@code extension} component set
    public static DType ofExtension(Extension value) {
        return new DType(null, null, null, null, null, null, null, null, value, null, null, null);
    }

    /// Factory for oneof case {@code fixed_size_list} (field tag 10).
    /// @param value the value to set
    /// @return a record with only the {@code fixed_size_list} component set
    public static DType ofFixedSizeList(FixedSizeList value) {
        return new DType(null, null, null, null, null, null, null, null, null, value, null, null);
    }

    /// Factory for oneof case {@code variant} (field tag 11).
    /// @param value the value to set
    /// @return a record with only the {@code variant} component set
    public static DType ofVariant(Variant value) {
        return new DType(null, null, null, null, null, null, null, null, null, null, value, null);
    }

    /// Factory for oneof case {@code union} (field tag 12).
    /// @param value the value to set
    /// @return a record with only the {@code union} component set
    public static DType ofUnion(Union value) {
        return new DType(null, null, null, null, null, null, null, null, null, null, null, value);
    }
}
