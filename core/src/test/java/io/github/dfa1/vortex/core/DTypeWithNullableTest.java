package io.github.dfa1.vortex.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// [DType#withNullable(boolean)] across every sealed variant.
class DTypeWithNullableTest {

    /// One non-nullable instance of each [DType] variant, with non-default payload
    /// fields so field-preservation is actually exercised (not just the flag).
    static Stream<Arguments> nonNullableSamples() {
        DType i32 = DType.I32;
        return Stream.of(
                Arguments.of("Null", DType.NULL),
                Arguments.of("Bool", DType.BOOL),
                Arguments.of("Primitive", DType.I64),
                Arguments.of("Decimal", new DType.Decimal((byte) 10, (byte) 2, false)),
                Arguments.of("Utf8", DType.UTF8),
                Arguments.of("Binary", DType.BINARY),
                Arguments.of("Struct", new DType.Struct(List.of("a", "b"), List.of(i32, DType.UTF8), false)),
                Arguments.of("List", new DType.List(i32, false)),
                Arguments.of("FixedSizeList", new DType.FixedSizeList(i32, 4, false)),
                Arguments.of("Extension", new DType.Extension("ip.address", i32, null, false)),
                Arguments.of("Variant", DType.VARIANT)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("nonNullableSamples")
    void withNullableTrue_flipsFlag_keepsTypeAndFields(String name, DType sut) {
        // When
        DType result = sut.withNullable(true);

        // Then — same concrete variant, now nullable, source untouched (records are immutable)
        assertThat(result).isInstanceOf(sut.getClass());
        assertThat(result.nullable()).isTrue();
        assertThat(sut.nullable()).isFalse();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("nonNullableSamples")
    void withNullable_roundTrip_preservesEveryField(String name, DType sut) {
        // When — flip to nullable and back
        DType result = sut.withNullable(true).withNullable(false);

        // Then — record equality proves all non-nullability fields survived unchanged
        // (ptype, precision/scale, field names/types, element type, size, extension id/storage/meta)
        assertThat(result).isEqualTo(sut);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("nonNullableSamples")
    void withNullableFalse_onAlreadyNonNullable_isIdempotent(String name, DType sut) {
        // When
        DType result = sut.withNullable(false);

        // Then
        assertThat(result).isEqualTo(sut);
    }

    @Test
    void asNullable_isSugarForWithNullableTrue() {
        // Given
        DType sut = DType.I64;

        // When
        DType result = sut.asNullable();

        // Then
        assertThat(result).isEqualTo(sut.withNullable(true));
        assertThat(result.nullable()).isTrue();
    }

    @Test
    void withNullable_preservesCompoundPayload_struct() {
        // Given — a struct whose field names/types must ride through the flip verbatim
        DType i32 = DType.I32;
        DType.Struct sut = new DType.Struct(List.of("id", "name"), List.of(i32, new DType.Utf8(true)), false);

        // When
        DType.Struct result = (DType.Struct) sut.withNullable(true);

        // Then
        assertThat(result.nullable()).isTrue();
        assertThat(result.fieldNames()).containsExactly("id", "name");
        assertThat(result.fieldTypes()).containsExactly(i32, new DType.Utf8(true));
    }

    @Test
    void withNullable_preservesCompoundPayload_extensionAndFixedSizeList() {
        // Given
        DType storage = DType.I32;
        DType.Extension ext = new DType.Extension("ip.address", storage, null, false);
        DType.FixedSizeList fsl = new DType.FixedSizeList(storage, 16, false);

        // When
        DType.Extension extResult = (DType.Extension) ext.withNullable(true);
        DType.FixedSizeList fslResult = (DType.FixedSizeList) fsl.withNullable(true);

        // Then
        assertThat(extResult.extensionId()).isEqualTo("ip.address");
        assertThat(extResult.storageDType()).isEqualTo(storage);
        assertThat(extResult.metadata()).isNull();
        assertThat(fslResult.fixedSize()).isEqualTo(16);
        assertThat(fslResult.elementType()).isEqualTo(storage);
    }
}
