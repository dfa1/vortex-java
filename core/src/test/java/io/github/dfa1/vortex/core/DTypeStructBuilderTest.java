package io.github.dfa1.vortex.core;

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
                .field("timestamp", DType.i64())
                .field("symbol", DType.utf8())
                .field("price", DType.f64())
                .build();

        // Then
        assertThat(sut.fieldNames()).containsExactly("timestamp", "symbol", "price");
        assertThat(sut.fieldTypes()).containsExactly(
                DType.i64(), DType.utf8(), DType.f64());
        assertThat(sut.nullable()).isFalse();
    }

    @Test
    void asNullable_marksTheStructItself() {
        // Given / When
        DType.Struct sut = DType.structBuilder()
                .field("v", DType.i64())
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
        DType.StructBuilder sut = DType.structBuilder().field("x", DType.i64());

        // When / Then
        assertThatThrownBy(() -> sut.field("x", DType.f64()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate field name: x");
    }

    @Test
    void buildResult_equalsRecordConstructed_struct() {
        // Given
        DType.Struct viaBuilder = DType.structBuilder()
                .field("a", DType.i32())
                .field("b", DType.utf8())
                .build();

        // When
        DType.Struct viaRecord = new DType.Struct(
                List.of("a", "b"),
                List.of(new DType.Primitive(PType.I32, false), new DType.Utf8(false)),
                false);

        // Then
        assertThat(viaBuilder).isEqualTo(viaRecord);
    }

    @Test
    void builder_isNotReusable_afterMutation_byField() {
        // Given — separate builder instances must produce independent structs
        DType.Struct a = DType.structBuilder().field("x", DType.i64()).build();
        DType.Struct b = DType.structBuilder().field("y", DType.utf8()).build();

        // Then
        assertThat(a.fieldNames()).containsExactly("x");
        assertThat(b.fieldNames()).containsExactly("y");
    }
}
