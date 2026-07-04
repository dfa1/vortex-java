package io.github.dfa1.vortex.core.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Canonical-constructor validation of [DType.Struct]: names and types must pair 1:1.
/// Duplicate names stay legal in memory — mirroring the Rust reference's `StructFields` —
/// because uniqueness is a file-boundary contract (writer guard + parser guard), not a
/// property of the in-memory type.
class DTypeStructValidationTest {

    private static final DType I64 = new DType.Primitive(PType.I64, false);

    @Test
    void construct_arityMismatch_throwsIllegalArgumentException() {
        // Given / When / Then — two names, one type: the record constructor rejects the desync
        assertThatThrownBy(() -> new DType.Struct(List.of("a", "b"), List.of(I64), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("size mismatch");
    }

    @Test
    void construct_nullNames_throwsNullPointerException() {
        // Given / When / Then
        assertThatThrownBy(() -> new DType.Struct(null, List.of(I64), false))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void construct_nullTypes_throwsNullPointerException() {
        // Given / When / Then
        assertThatThrownBy(() -> new DType.Struct(List.of("a"), null, false))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void construct_duplicateNames_isLegalInMemory() {
        // Given — duplicate names, mirroring Rust StructFields (uniqueness is enforced at the
        // file boundary by the writer and parser, not by the in-memory type)
        // When
        DType.Struct result = new DType.Struct(List.of("dup", "dup"), List.of(I64, I64), false);

        // Then
        assertThat(result.fieldNames()).containsExactly("dup", "dup");
    }
}
