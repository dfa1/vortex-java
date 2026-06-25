package io.github.dfa1.vortex.core.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DTypeIsUnsignedTest {

    @ParameterizedTest
    @EnumSource(value = PType.class, names = {"U8", "U16", "U32", "U64"})
    void unsignedPrimitives_areUnsigned(PType pt) {
        // Given / When / Then
        assertThat(new DType.Primitive(pt, false).isUnsigned()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = PType.class, names = {"I8", "I16", "I32", "I64", "F16", "F32", "F64"})
    void signedAndFloatPrimitives_areNotUnsigned(PType pt) {
        // Given / When / Then
        assertThat(new DType.Primitive(pt, false).isUnsigned()).isFalse();
    }

    @Test
    void nonPrimitiveTypes_areNotUnsigned() {
        // Given — composite/extension types are never "unsigned", even one that wraps a U64 column
        List<DType> types = List.of(
                DType.BOOL, DType.UTF8, DType.BINARY, DType.NULL, DType.VARIANT,
                new DType.Decimal((byte) 10, (byte) 2, false),
                new DType.Struct(List.of("u"), List.of(DType.U64), false));

        // When / Then
        assertThat(types).hasSize(7).allSatisfy(t -> assertThat(t.isUnsigned()).isFalse());
    }
}
