package io.github.dfa1.vortex.core.model;

import io.github.dfa1.vortex.core.error.VortexException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DTypeMapTest {

    @Nested
    class Construction {

        @Test
        void nonNullableKey_isAccepted() {
            // Given / When
            DType.Map result = new DType.Map(DType.UTF8, DType.I64, false, false);

            // Then
            assertThat(result.keyType()).isEqualTo(DType.UTF8);
            assertThat(result.valueType()).isEqualTo(DType.I64);
            assertThat(result.keysSorted()).isFalse();
            assertThat(result.nullable()).isFalse();
        }

        @Test
        void nullKey_throwsNullPointerException() {
            // Given a missing key type — unreachable from the file parser (guarded at the FbsMap
            // parse site) but the record is public API, so it must not NPE from inside the
            // nullability check with no context
            // When / Then
            assertThatThrownBy(() -> new DType.Map(null, DType.I64, false, false))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("keyType");
        }

        @Test
        void nullableValue_isAccepted() {
            // Given a nullable value type — legal, unlike a nullable key
            // When
            DType.Map result = new DType.Map(DType.UTF8, DType.I64.asNullable(), false, false);

            // Then
            assertThat(result.valueType().nullable()).isTrue();
        }

        @Test
        void nullableKey_throwsVortexException() {
            // Given a nullable key type — rejected because this constructor also runs while
            // parsing an untrusted file's DType blob, where a raw exception would breach the
            // security contract
            DType nullableKey = DType.UTF8.asNullable();

            // When / Then
            assertThatThrownBy(() -> new DType.Map(nullableKey, DType.I64, false, false))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("map key dtype must be non-nullable");
        }

        @Test
        void nestedMapValue_isAccepted() {
            // Given a map whose values are themselves maps
            DType.Map inner = new DType.Map(DType.UTF8, DType.I32, false, false);

            // When
            DType.Map result = new DType.Map(DType.I64, inner, false, true);

            // Then
            assertThat(result.valueType()).isEqualTo(inner);
        }

        @Test
        void equality_isByValue() {
            // Given two independently built maps with identical components
            DType.Map sut = new DType.Map(DType.UTF8, DType.I64, true, false);

            // When
            DType.Map result = new DType.Map(DType.UTF8, DType.I64, true, false);

            // Then
            assertThat(result).isEqualTo(sut).hasSameHashCodeAs(sut);
        }

        @Test
        void equality_keysSortedIsPartOfIdentity() {
            // Given a producer assertion that differs between two otherwise identical maps
            DType.Map sut = new DType.Map(DType.UTF8, DType.I64, true, false);

            // When
            DType.Map result = new DType.Map(DType.UTF8, DType.I64, false, false);

            // Then
            assertThat(result).isNotEqualTo(sut);
        }
    }

    @Nested
    class Nullability {

        @ParameterizedTest
        @ValueSource(booleans = {true, false})
        void withNullable_flipsOnlyOuterNullability(boolean nullable) {
            // Given
            DType.Map sut = new DType.Map(DType.UTF8, DType.I64.asNullable(), true, !nullable);

            // When
            DType result = sut.withNullable(nullable);

            // Then
            assertThat(result).isEqualTo(new DType.Map(DType.UTF8, DType.I64.asNullable(), true, nullable));
        }

        @Test
        void asNullable_returnsNullableCopy() {
            // Given
            DType.Map sut = new DType.Map(DType.UTF8, DType.I64, false, false);

            // When
            DType result = sut.asNullable();

            // Then
            assertThat(result.nullable()).isTrue();
        }

        @Test
        void isUnsigned_isFalse() {
            // Given a map of unsigned keys and values — the map itself is never an unsigned integer
            DType.Map sut = new DType.Map(DType.U64, DType.U32, false, false);

            // When
            boolean result = sut.isUnsigned();

            // Then
            assertThat(result).isFalse();
        }
    }

    @Nested
    class EntriesDtype {

        @Test
        void entriesDtype_isNonNullableKeyValueStruct() {
            // Given
            DType.Map sut = new DType.Map(DType.UTF8, DType.I64.asNullable(), false, false);

            // When
            DType.Struct result = sut.entriesDtype();

            // Then
            assertThat(result.nullable()).isFalse();
            assertThat(result.fieldNames()).containsExactly(ColumnName.of("key"), ColumnName.of("value"));
            assertThat(result.fieldTypes()).containsExactly(DType.UTF8, DType.I64.asNullable());
        }

        @Test
        void entriesDtype_ignoresOuterNullability() {
            // Given a nullable map — the entries struct itself stays non-nullable, because a
            // null map row is a null *entries list* row, not a null entry struct
            DType.Map sut = new DType.Map(DType.UTF8, DType.I64, false, true);

            // When
            DType.Struct result = sut.entriesDtype();

            // Then
            assertThat(result).isEqualTo(new DType.Struct(
                    List.of(ColumnName.of("key"), ColumnName.of("value")),
                    List.of(DType.UTF8, DType.I64),
                    false));
        }
    }
}
