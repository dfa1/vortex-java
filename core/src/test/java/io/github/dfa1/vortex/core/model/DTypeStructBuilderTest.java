package io.github.dfa1.vortex.core.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Struct builder (ADR 0009 part 2).
class DTypeStructBuilderTest {

    @Test
    void build_preservesFieldInsertionOrder() {
        // Given / When
        DType.Struct sut = DType.structBuilder()
                .field("timestamp", DType.I64)
                .field("symbol", DType.UTF8)
                .field("price", DType.F64)
                .build();

        // Then
        assertThat(sut.fieldNames()).containsExactly(
                ColumnName.of("timestamp"), ColumnName.of("symbol"), ColumnName.of("price"));
        assertThat(sut.fieldTypes()).containsExactly(
                DType.I64, DType.UTF8, DType.F64);
        assertThat(sut.nullable()).isFalse();
    }

    @Test
    void asNullable_marksTheStructItself() {
        // Given / When
        DType.Struct sut = DType.structBuilder()
                .field("v", DType.I64)
                .asNullable()
                .build();

        // Then
        assertThat(sut.nullable()).isTrue();
    }

    @Test
    void emptyBuilder_buildsEmptyStruct() {
        // Given / When
        DType.Struct sut = DType.structBuilder().build();

        // Then
        assertThat(sut.fieldNames()).isEmpty();
        assertThat(sut.fieldTypes()).isEmpty();
        assertThat(sut.nullable()).isFalse();
    }

    @Test
    void duplicateField_throws_atAddTime() {
        // Given
        DType.StructBuilder sut = DType.structBuilder().field("x", DType.I64);

        // When / Then
        assertThatThrownBy(() -> sut.field("x", DType.F64))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate field name: x");
    }

    @Test
    void buildResult_equalsRecordConstructed_struct() {
        // Given
        DType.Struct viaBuilder = DType.structBuilder()
                .field("a", DType.I32)
                .field("b", DType.UTF8)
                .build();

        // When
        DType.Struct result = new DType.Struct(
                List.of(ColumnName.of("a"), ColumnName.of("b")),
                List.of(DType.I32, DType.UTF8),
                false);

        // Then
        assertThat(viaBuilder).isEqualTo(result);
    }

    @Test
    void builder_isNotReusable_afterMutation_byField() {
        // Given / When — separate builder instances must produce independent structs
        DType.Struct resultX = DType.structBuilder().field("x", DType.I64).build();
        DType.Struct resultY = DType.structBuilder().field("y", DType.UTF8).build();

        // Then
        assertThat(resultX.fieldNames()).containsExactly(ColumnName.of("x"));
        assertThat(resultY.fieldNames()).containsExactly(ColumnName.of("y"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"", " ", "   ", "a\nb", "nul\u0000here"})
    void field_footgunName_throwsIllegalArgumentException(String name) {
        // Given the friendly path, which enforces the write-side name policy up front:
        // blank and control-character names are wire-legal footguns vortex-java refuses to write
        var builder = DType.structBuilder();
        // When / Then
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> builder.field(name, DType.I64))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
