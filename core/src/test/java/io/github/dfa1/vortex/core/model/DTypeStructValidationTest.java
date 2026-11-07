package io.github.dfa1.vortex.core.model;

import io.github.dfa1.vortex.core.error.VortexException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Canonical-constructor validation of [DType.Struct]: names and types must pair 1:1 and names
/// must be unique. Stricter than the Rust reference's in-memory `StructFields`, whose file writer
/// refuses duplicates anyway; failures are [VortexException] because the same constructor runs on
/// dtypes parsed from untrusted files.
class DTypeStructValidationTest {

    private static final DType I64 = new DType.Primitive(PType.I64, false);

    @Test
    void construct_arityMismatch_throwsVortexException() {
        // Given two names, one type: the record constructor rejects the desync
        var names = List.of(ColumnName.of("a"), ColumnName.of("b"));
        var types = List.of(I64);
        // When / Then
        assertThatThrownBy(() -> new DType.Struct(names, types, false))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("size mismatch");
    }

    @Test
    void construct_nullNames_throwsNullPointerException() {
        // Given a null names list
        var types = List.of(I64);
        // When / Then
        assertThatThrownBy(() -> new DType.Struct(null, types, false))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void construct_nullTypes_throwsNullPointerException() {
        // Given a null types list
        var names = List.of(ColumnName.of("a"));
        // When / Then
        assertThatThrownBy(() -> new DType.Struct(names, null, false))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void construct_duplicateNames_throwsVortexException() {
        // Given — non-adjacent duplicates, so the check cannot be a neighbor comparison; the
        // name-keyed Chunk API would otherwise silently drop one of the two "dup" columns
        var names = List.of(ColumnName.of("dup"), ColumnName.of("other"), ColumnName.of("dup"));
        var types = List.of(I64, I64, I64);

        // When / Then
        assertThatThrownBy(() -> new DType.Struct(names, types, false))
                .isInstanceOf(VortexException.class)
                .hasMessage("duplicate field name: dup");
    }

    @Test
    void construct_blankAndDistinctNames_isAccepted() {
        // Given — blank names are wire-legal (the Rust writer produces them); only repeats fail
        var names = List.of(ColumnName.of(""), ColumnName.of(" "), ColumnName.of("a"));

        // When
        DType.Struct result = new DType.Struct(names, List.of(I64, I64, I64), false);

        // Then
        assertThat(result.fieldNames()).isEqualTo(names);
    }
}
