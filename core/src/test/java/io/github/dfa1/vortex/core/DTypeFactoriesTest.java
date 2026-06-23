package io.github.dfa1.vortex.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// Static factories + `nullable()` shortcut (ADR 0009 part 1).
class DTypeFactoriesTest {

    @Test
    void primitiveFactories_areNonNullable() {
        // Given / When
        DType.Primitive sut = DType.I64;

        // Then
        assertThat(sut.ptype()).isEqualTo(PType.I64);
        assertThat(sut.nullable()).isFalse();
    }

    @Test
    void nullableShortcut_marksType_nullable() {
        // Given
        DType sut = DType.UTF8;

        // When
        DType result = sut.asNullable();

        // Then
        assertThat(result).isInstanceOf(DType.Utf8.class);
        assertThat(result.nullable()).isTrue();
        // Original instance must remain non-nullable
        assertThat(sut.nullable()).isFalse();
    }

    @Test
    void allPrimitiveFactories_returnExpectedPType() {
        // Given / When / Then
        assertThat(DType.I8.ptype()).isEqualTo(PType.I8);
        assertThat(DType.I16.ptype()).isEqualTo(PType.I16);
        assertThat(DType.I32.ptype()).isEqualTo(PType.I32);
        assertThat(DType.I64.ptype()).isEqualTo(PType.I64);
        assertThat(DType.U8.ptype()).isEqualTo(PType.U8);
        assertThat(DType.U16.ptype()).isEqualTo(PType.U16);
        assertThat(DType.U32.ptype()).isEqualTo(PType.U32);
        assertThat(DType.U64.ptype()).isEqualTo(PType.U64);
        assertThat(DType.F16.ptype()).isEqualTo(PType.F16);
        assertThat(DType.F32.ptype()).isEqualTo(PType.F32);
        assertThat(DType.F64.ptype()).isEqualTo(PType.F64);
    }

    @Test
    void bool_utf8_binary_null_variant_factories_areNonNullable() {
        // Given / When / Then
        assertThat(DType.BOOL.nullable()).isFalse();
        assertThat(DType.UTF8.nullable()).isFalse();
        assertThat(DType.BINARY.nullable()).isFalse();
        assertThat(DType.NULL.nullable()).isFalse();
        assertThat(DType.VARIANT.nullable()).isFalse();
    }

    @Test
    void decimalFactory_setsPrecisionAndScale() {
        // Given / When
        DType.Decimal sut = DType.decimal(12, 4);

        // Then
        assertThat(sut.precision()).isEqualTo((byte) 12);
        assertThat(sut.scale()).isEqualTo((byte) 4);
        assertThat(sut.nullable()).isFalse();
    }

    @Test
    void nullableShortcut_equivalentTo_withNullableTrue() {
        // Given
        DType direct = DType.F64.withNullable(true);

        // When
        DType result = DType.F64.asNullable();

        // Then
        assertThat(result).isEqualTo(direct);
    }
}
