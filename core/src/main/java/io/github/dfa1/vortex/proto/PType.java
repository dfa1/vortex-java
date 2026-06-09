package io.github.dfa1.vortex.proto;

import javax.annotation.processing.Generated;

/// Generated from proto3 enum {@code vortex.dtype.PType}.
/// Do not edit by hand — regenerate via {@code ./mvnw generate-sources -pl core -P regenerate-sources}.
@Generated("io.github.dfa1.vortex.protogen.CodeGen")
public enum PType {
    U8(0),
    U16(1),
    U32(2),
    U64(3),
    I8(4),
    I16(5),
    I32(6),
    I64(7),
    F16(8),
    F32(9),
    F64(10);

    private final int value;

    PType(int value) {
        this.value = value;
    }

    /// @return the wire-format integer value
    public int value() {
        return value;
    }

    /// Resolves a wire-format integer back to its enum constant.
    /// @param value wire-format integer
    /// @return matching enum constant
    /// @throws IllegalArgumentException if no constant matches
    public static PType fromValue(int value) {
        for (PType v : values()) {
            if (v.value == value) {
                return v;
            }
        }
        throw new IllegalArgumentException("unknown PType value: " + value);
    }
}
