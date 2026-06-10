package io.github.dfa1.vortex.proto;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.util.Arrays;
import javax.annotation.processing.Generated;

/// Generated from proto3 message {@code vortex.scalar.ScalarValue}.
/// Do not edit by hand — regenerate via {@code ./mvnw generate-sources -pl core -P regenerate-sources}.
/// @param null_value field tag 1
/// @param bool_value field tag 2
/// @param int64_value field tag 3
/// @param uint64_value field tag 4
/// @param f32_value field tag 5
/// @param f64_value field tag 6
/// @param string_value field tag 7
/// @param bytes_value field tag 8
/// @param list_value field tag 9
/// @param f16_value field tag 10
/// @param variant_value field tag 11
@Generated("io.github.dfa1.vortex.protogen.CodeGen")
public record ScalarValue(
        NullValue null_value,
        Boolean bool_value,
        Long int64_value,
        Long uint64_value,
        Float f32_value,
        Double f64_value,
        String string_value,
        byte[] bytes_value,
        ListValue list_value,
        Long f16_value,
        Scalar variant_value
) {

    /// Decodes a {@code vortex.scalar.ScalarValue} from a slice of a memory segment.
    /// @param __seg backing segment
    /// @param __off start offset in bytes
    /// @param __len payload length in bytes
    /// @return decoded record
    /// @throws IOException if the slice is malformed or truncated
    public static ScalarValue decode(MemorySegment __seg, long __off, long __len) throws IOException {
        ProtoReader r = new ProtoReader(__seg, __off, __len);
        NullValue null_value = null;
        Boolean bool_value = null;
        Long int64_value = null;
        Long uint64_value = null;
        Float f32_value = null;
        Double f64_value = null;
        String string_value = null;
        byte[] bytes_value = null;
        ListValue list_value = null;
        Long f16_value = null;
        Scalar variant_value = null;
        while (r.hasMore()) {
            int tag = r.readVarint32();
            switch (tag >>> 3) {
                case 1 -> {
                    int __ev = r.readVarint32();
                    try {
                        null_value = NullValue.fromValue(__ev);
                    } catch (IllegalArgumentException __iae) {
                        throw new IOException("unknown NullValue value: " + __ev);
                    }
                }
                case 2 -> {
                    bool_value = r.readBool();
                }
                case 3 -> {
                    int64_value = r.readSint64();
                }
                case 4 -> {
                    uint64_value = r.readVarint64();
                }
                case 5 -> {
                    f32_value = r.readFloat();
                }
                case 6 -> {
                    f64_value = r.readDouble();
                }
                case 7 -> {
                    string_value = r.readString();
                }
                case 8 -> {
                    bytes_value = r.readBytes();
                }
                case 9 -> {
                    MemorySegment __slice = r.readLenDelimSegment();
                    list_value = ListValue.decode(__slice, 0, __slice.byteSize());
                }
                case 10 -> {
                    f16_value = r.readVarint64();
                }
                case 11 -> {
                    MemorySegment __slice = r.readLenDelimSegment();
                    variant_value = Scalar.decode(__slice, 0, __slice.byteSize());
                }
                default -> r.skipField(tag & 7);
            }
        }
        return new ScalarValue(null_value, bool_value, int64_value, uint64_value, f32_value, f64_value, string_value, bytes_value, list_value, f16_value, variant_value);
    }

    /// Encodes this record to a proto3-wire-format byte array.
    /// @return encoded bytes
    public byte[] encode() {
        ProtoWriter w = new ProtoWriter();
        encodeTo(w);
        return w.toByteArray();
    }

    void encodeTo(ProtoWriter w) {
        if (null_value != null) {
            w.writeTag(1, 0);
            w.writeVarint32(null_value.value());
        }
        if (bool_value != null) {
            w.writeTag(2, 0);
            w.writeBool(bool_value);
        }
        if (int64_value != null) {
            w.writeTag(3, 0);
            w.writeSint64(int64_value);
        }
        if (uint64_value != null) {
            w.writeTag(4, 0);
            w.writeVarint64(uint64_value);
        }
        if (f32_value != null) {
            w.writeTag(5, 5);
            w.writeFloat(f32_value);
        }
        if (f64_value != null) {
            w.writeTag(6, 1);
            w.writeDouble(f64_value);
        }
        if (string_value != null) {
            w.writeTag(7, 2);
            w.writeString(string_value);
        }
        if (bytes_value != null) {
            w.writeTag(8, 2);
            w.writeBytes(bytes_value);
        }
        if (list_value != null) {
            w.writeTag(9, 2);
            int __mark = w.beginLenDelim();
            list_value.encodeTo(w);
            w.endLenDelim(__mark);
        }
        if (f16_value != null) {
            w.writeTag(10, 0);
            w.writeVarint64(f16_value);
        }
        if (variant_value != null) {
            w.writeTag(11, 2);
            int __mark = w.beginLenDelim();
            variant_value.encodeTo(w);
            w.endLenDelim(__mark);
        }
    }

    /// Factory for oneof case {@code null_value} (field tag 1).
    /// @param value the value to set
    /// @return a record with only the {@code null_value} component set
    public static ScalarValue ofNullValue(NullValue value) {
        return new ScalarValue(value, null, null, null, null, null, null, null, null, null, null);
    }

    /// Factory for oneof case {@code bool_value} (field tag 2).
    /// @param value the value to set
    /// @return a record with only the {@code bool_value} component set
    public static ScalarValue ofBoolValue(Boolean value) {
        return new ScalarValue(null, value, null, null, null, null, null, null, null, null, null);
    }

    /// Factory for oneof case {@code int64_value} (field tag 3).
    /// @param value the value to set
    /// @return a record with only the {@code int64_value} component set
    public static ScalarValue ofInt64Value(Long value) {
        return new ScalarValue(null, null, value, null, null, null, null, null, null, null, null);
    }

    /// Factory for oneof case {@code uint64_value} (field tag 4).
    /// @param value the value to set
    /// @return a record with only the {@code uint64_value} component set
    public static ScalarValue ofUint64Value(Long value) {
        return new ScalarValue(null, null, null, value, null, null, null, null, null, null, null);
    }

    /// Factory for oneof case {@code f32_value} (field tag 5).
    /// @param value the value to set
    /// @return a record with only the {@code f32_value} component set
    public static ScalarValue ofF32Value(Float value) {
        return new ScalarValue(null, null, null, null, value, null, null, null, null, null, null);
    }

    /// Factory for oneof case {@code f64_value} (field tag 6).
    /// @param value the value to set
    /// @return a record with only the {@code f64_value} component set
    public static ScalarValue ofF64Value(Double value) {
        return new ScalarValue(null, null, null, null, null, value, null, null, null, null, null);
    }

    /// Factory for oneof case {@code string_value} (field tag 7).
    /// @param value the value to set
    /// @return a record with only the {@code string_value} component set
    public static ScalarValue ofStringValue(String value) {
        return new ScalarValue(null, null, null, null, null, null, value, null, null, null, null);
    }

    /// Factory for oneof case {@code bytes_value} (field tag 8).
    /// @param value the value to set
    /// @return a record with only the {@code bytes_value} component set
    public static ScalarValue ofBytesValue(byte[] value) {
        return new ScalarValue(null, null, null, null, null, null, null, value, null, null, null);
    }

    /// Factory for oneof case {@code list_value} (field tag 9).
    /// @param value the value to set
    /// @return a record with only the {@code list_value} component set
    public static ScalarValue ofListValue(ListValue value) {
        return new ScalarValue(null, null, null, null, null, null, null, null, value, null, null);
    }

    /// Factory for oneof case {@code f16_value} (field tag 10).
    /// @param value the value to set
    /// @return a record with only the {@code f16_value} component set
    public static ScalarValue ofF16Value(Long value) {
        return new ScalarValue(null, null, null, null, null, null, null, null, null, value, null);
    }

    /// Factory for oneof case {@code variant_value} (field tag 11).
    /// @param value the value to set
    /// @return a record with only the {@code variant_value} component set
    public static ScalarValue ofVariantValue(Scalar value) {
        return new ScalarValue(null, null, null, null, null, null, null, null, null, null, value);
    }

    @Override
    public boolean equals(Object __o) {
        if (this == __o) {
            return true;
        }
        if (!(__o instanceof ScalarValue __that)) {
            return false;
        }
        return java.util.Objects.equals(null_value, __that.null_value)
            && java.util.Objects.equals(bool_value, __that.bool_value)
            && java.util.Objects.equals(int64_value, __that.int64_value)
            && java.util.Objects.equals(uint64_value, __that.uint64_value)
            && java.util.Objects.equals(f32_value, __that.f32_value)
            && java.util.Objects.equals(f64_value, __that.f64_value)
            && java.util.Objects.equals(string_value, __that.string_value)
            && java.util.Arrays.equals(bytes_value, __that.bytes_value)
            && java.util.Objects.equals(list_value, __that.list_value)
            && java.util.Objects.equals(f16_value, __that.f16_value)
            && java.util.Objects.equals(variant_value, __that.variant_value);
    }

    @Override
    public int hashCode() {
        int __h = 1;
        __h = 31 * __h + java.util.Objects.hashCode(null_value);
        __h = 31 * __h + java.util.Objects.hashCode(bool_value);
        __h = 31 * __h + java.util.Objects.hashCode(int64_value);
        __h = 31 * __h + java.util.Objects.hashCode(uint64_value);
        __h = 31 * __h + java.util.Objects.hashCode(f32_value);
        __h = 31 * __h + java.util.Objects.hashCode(f64_value);
        __h = 31 * __h + java.util.Objects.hashCode(string_value);
        __h = 31 * __h + java.util.Arrays.hashCode(bytes_value);
        __h = 31 * __h + java.util.Objects.hashCode(list_value);
        __h = 31 * __h + java.util.Objects.hashCode(f16_value);
        __h = 31 * __h + java.util.Objects.hashCode(variant_value);
        return __h;
    }
}
