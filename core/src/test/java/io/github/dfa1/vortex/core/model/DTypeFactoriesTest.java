package io.github.dfa1.vortex.core.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// Static factories + `nullable()` shortcut (ADR 0009 part 1).
class DTypeFactoriesTest {

    @Test
    void primitiveFactories_areNonNullable() {
        // Given / When
        DType.Primitive sut = DTypes.I64;

        // Then
        assertThat(sut.ptype()).isEqualTo(PType.I64);
        assertThat(sut.nullable()).isFalse();
    }

    @Test
    void nullableShortcut_marksType_nullable() {
        // Given
        DType sut = DTypes.UTF8;

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
        assertThat(DTypes.I8.ptype()).isEqualTo(PType.I8);
        assertThat(DTypes.I16.ptype()).isEqualTo(PType.I16);
        assertThat(DTypes.I32.ptype()).isEqualTo(PType.I32);
        assertThat(DTypes.I64.ptype()).isEqualTo(PType.I64);
        assertThat(DTypes.U8.ptype()).isEqualTo(PType.U8);
        assertThat(DTypes.U16.ptype()).isEqualTo(PType.U16);
        assertThat(DTypes.U32.ptype()).isEqualTo(PType.U32);
        assertThat(DTypes.U64.ptype()).isEqualTo(PType.U64);
        assertThat(DTypes.F16.ptype()).isEqualTo(PType.F16);
        assertThat(DTypes.F32.ptype()).isEqualTo(PType.F32);
        assertThat(DTypes.F64.ptype()).isEqualTo(PType.F64);
    }

    @Test
    void bool_utf8_binary_null_variant_factories_areNonNullable() {
        // Given / When / Then
        assertThat(DTypes.BOOL.nullable()).isFalse();
        assertThat(DTypes.UTF8.nullable()).isFalse();
        assertThat(DTypes.BINARY.nullable()).isFalse();
        assertThat(DTypes.NULL.nullable()).isFalse();
        assertThat(DTypes.VARIANT.nullable()).isFalse();
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
        DType direct = DTypes.F64.withNullable(true);

        // When
        DType result = DTypes.F64.asNullable();

        // Then
        assertThat(result).isEqualTo(direct);
    }
}
