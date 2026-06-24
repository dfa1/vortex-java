package io.github.dfa1.vortex.proto;

import javax.annotation.processing.Generated;

/// Generated from proto3 enum {@code google.protobuf.NullValue}.
/// Do not edit by hand — regenerate via {@code ./mvnw generate-sources -pl core -P regenerate-sources}.
@Generated("io.github.dfa1.vortex.protogen.CodeGen")
public enum ProtoNullValue {
    NULL_VALUE(0);

    private final int value;

    ProtoNullValue(int value) {
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
    public static ProtoNullValue fromValue(int value) {
        for (ProtoNullValue v : values()) {
            if (v.value == value) {
                return v;
            }
        }
        throw new IllegalArgumentException("unknown ProtoNullValue value: " + value);
    }
}
