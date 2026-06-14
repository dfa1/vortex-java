package io.github.dfa1.vortex.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// Static factories + {@code nullable()} shortcut (ADR 0009 part 1).
class DTypeFactoriesTest {

    @Test
    void primitiveFactories_areNonNullable() {
        // Given / When
        DType.Primitive sut = DType.i64();

        // Then
        assertThat(sut.ptype()).isEqualTo(PType.I64);
        assertThat(sut.nullable()).isFalse();
    }

    @Test
    void nullableShortcut_marksType_nullable() {
        // Given
        DType sut = DType.utf8();

        // When
        DType marked = sut.asNullable();

        // Then
        assertThat(marked).isInstanceOf(DType.Utf8.class);
        assertThat(marked.nullable()).isTrue();
        // Original instance must remain non-nullable
        assertThat(sut.nullable()).isFalse();
    }

    @Test
    void allPrimitiveFactories_returnExpectedPType() {
        // Given / When / Then
        assertThat(DType.i8().ptype()).isEqualTo(PType.I8);
        assertThat(DType.i16().ptype()).isEqualTo(PType.I16);
        assertThat(DType.i32().ptype()).isEqualTo(PType.I32);
        assertThat(DType.i64().ptype()).isEqualTo(PType.I64);
        assertThat(DType.u8().ptype()).isEqualTo(PType.U8);
        assertThat(DType.u16().ptype()).isEqualTo(PType.U16);
        assertThat(DType.u32().ptype()).isEqualTo(PType.U32);
        assertThat(DType.u64().ptype()).isEqualTo(PType.U64);
        assertThat(DType.f16().ptype()).isEqualTo(PType.F16);
        assertThat(DType.f32().ptype()).isEqualTo(PType.F32);
        assertThat(DType.f64().ptype()).isEqualTo(PType.F64);
    }

    @Test
    void bool_utf8_binary_null_variant_factories_areNonNullable() {
        // Given / When / Then
        assertThat(DType.bool_().nullable()).isFalse();
        assertThat(DType.utf8().nullable()).isFalse();
        assertThat(DType.binary().nullable()).isFalse();
        assertThat(DType.null_().nullable()).isFalse();
        assertThat(DType.variant().nullable()).isFalse();
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
        DType direct = DType.f64().withNullable(true);

        // When
        DType viaShortcut = DType.f64().asNullable();

        // Then
        assertThat(viaShortcut).isEqualTo(direct);
    }
}
