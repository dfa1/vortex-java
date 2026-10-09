package io.github.dfa1.vortex.core.model;

import io.github.dfa1.vortex.core.error.VortexException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class DTypeUnionTest {

    private static final ColumnName A = ColumnName.of("a");
    private static final ColumnName B = ColumnName.of("b");

    @Test
    void variantIndex_mapsNonConsecutiveTypeIds() {
        // Given — Rust allows sparse type ids such as [0, 5]
        var sut = new DType.Union(List.of(A, B), List.of(DType.I64, DType.UTF8), List.of(0, 5), false);

        // When
        List<Integer> result = List.of(sut.variantIndex(0), sut.variantIndex(5), sut.variantIndex(1));

        // Then
        assertThat(result).containsExactly(0, 1, -1);
    }

    @Test
    void maxVariants_isAccepted() {
        // Given — every u8 type id, Rust's upper bound
        int n = DType.Union.MAX_VARIANTS;
        List<ColumnName> names = IntStream.range(0, n).mapToObj(i -> ColumnName.of("v" + i)).toList();
        List<Integer> ids = IntStream.range(0, n).boxed().toList();

        // When
        var result = new DType.Union(names, Collections.nCopies(n, DType.I64), ids, true);

        // Then
        assertThat(result.variantIndex(255)).isEqualTo(255);
    }

    @Test
    void withNullable_keepsVariants() {
        // Given
        var sut = new DType.Union(List.of(A), List.of(DType.I64), List.of(3), false);

        // When
        DType result = sut.asNullable();

        // Then
        assertThat(result).isEqualTo(new DType.Union(List.of(A), List.of(DType.I64), List.of(3), true));
    }

    static Stream<Arguments> invalidShapes() {
        // Rust's UnionVariants::validate_shape, one rule per case
        return Stream.of(
                arguments("names/dtypes length", List.of(A, B), List.of(DType.I64), List.of(0, 1), "length mismatch"),
                arguments("names/type_ids length", List.of(A, B), List.of(DType.I64, DType.I64), List.of(0), "length mismatch"),
                arguments("no variants", List.of(), List.of(), List.of(), "1 to 256 variants"),
                arguments("duplicate type id", List.of(A, B), List.of(DType.I64, DType.I64), List.of(1, 1), "type_ids must be distinct"),
                arguments("duplicate name", List.of(A, A), List.of(DType.I64, DType.I64), List.of(0, 1), "names must be distinct"),
                arguments("type id above u8", List.of(A), List.of(DType.I64), List.of(256), "fit in u8"),
                arguments("negative type id", List.of(A), List.of(DType.I64), List.of(-1), "fit in u8"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidShapes")
    void invalidShape_throws(String label, List<ColumnName> names, List<DType> types, List<Integer> ids, String message) {
        // Given / When / Then
        assertThatThrownBy(() -> new DType.Union(names, types, ids, false))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining(message);
    }
}
