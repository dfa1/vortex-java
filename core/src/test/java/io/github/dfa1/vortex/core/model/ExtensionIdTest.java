package io.github.dfa1.vortex.core.model;

import io.github.dfa1.vortex.core.error.VortexException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.foreign.MemorySegment;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExtensionIdTest {

    @Nested
    class Parse {

        @ParameterizedTest
        @EnumSource(ExtensionId.WellKnown.class)
        void parse_knownId_returnsMatchingConstant(ExtensionId.WellKnown id) {
            // Given the wire string of a well-known constant
            // When
            ExtensionId result = ExtensionId.parse(id.id());
            // Then the same constant comes back
            assertThat(result).isSameAs(id);
        }

        @Test
        void parse_unknownId_returnsCustomWrappingRawId() {
            // Given a wire string no build knows about
            String raw = "acme.geopoint";
            // When — parse is total, so a miss is a typed Custom rather than an empty Optional
            ExtensionId result = ExtensionId.parse(raw);
            // Then
            assertThat(result).isEqualTo(new ExtensionId.Custom(raw));
            assertThat(result.id()).isEqualTo(raw);
        }

        @ParameterizedTest
        @ValueSource(strings = {"", " ", "   "})
        void parse_blankId_throwsIllegalArgumentException(String blank) {
            // Given / When / Then — blank is not a valid id; parse must not silently wrap it,
            // so untrusted-input callers are forced to guard it into their own domain error
            assertThatThrownBy(() -> ExtensionId.parse(blank))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class CustomInvariants {

        @Test
        void construct_nullId_throwsNullPointerException() {
            // Given / When / Then — a Custom must always carry a wire string
            assertThatThrownBy(() -> new ExtensionId.Custom(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void construct_blankId_throwsIllegalArgumentException() {
            // Given / When / Then — blank ids have no wire representation
            assertThatThrownBy(() -> new ExtensionId.Custom("   "))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void construct_wellKnownId_throwsIllegalArgumentException() {
            // Given a wire string that already names a well-known constant
            // When / Then — Custom refuses to shadow it and points at the constant to use instead
            assertThatThrownBy(() -> new ExtensionId.Custom("vortex.date"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("VORTEX_DATE");
        }

        @ParameterizedTest
        @ValueSource(strings = {"a\nb", "tab\there"})
        void custom_controlCharId_throwsIllegalArgumentException(String id) {
            // Given / When / Then — a control char would write a file PostscriptParser crashes on,
            // so Custom rejects it just as ColumnName does
            assertThatThrownBy(() -> new ExtensionId.Custom(id))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class Properties {

        @ParameterizedTest
        @EnumSource(ExtensionId.WellKnown.class)
        void id_isNonBlankString(ExtensionId.WellKnown id) {
            // Given / When / Then
            assertThat(id.id()).isNotBlank();
        }

        @ParameterizedTest
        @EnumSource(ExtensionId.WellKnown.class)
        void toString_equalsId(ExtensionId.WellKnown id) {
            // Given / When / Then
            assertThat(id).hasToString(id.id());
        }
    }

    /// Rust's `deserialize_metadata` + `validate_dtype` rules for the four spec extensions
    /// (vortex-array/src/extension/datetime/*.rs and encodings/uuid/src/vtable.rs at 0.88.0).
    @Nested
    class Validate {

        static Stream<Arguments> valid() {
            return Stream.of(
                    Arguments.of("date days I32", ext(ExtensionId.VORTEX_DATE, DType.I32, 4)),
                    Arguments.of("date millis I64", ext(ExtensionId.VORTEX_DATE, DType.I64, 2)),
                    Arguments.of("time seconds I32", ext(ExtensionId.VORTEX_TIME, DType.I32, 3)),
                    Arguments.of("time millis I32", ext(ExtensionId.VORTEX_TIME, DType.I32, 2)),
                    Arguments.of("time micros I64", ext(ExtensionId.VORTEX_TIME, DType.I64, 1)),
                    Arguments.of("time nanos I64", ext(ExtensionId.VORTEX_TIME, DType.I64, 0)),
                    Arguments.of("timestamp no tz", ext(ExtensionId.VORTEX_TIMESTAMP, DType.I64, 2, 0, 0)),
                    Arguments.of("timestamp tz", ext(ExtensionId.VORTEX_TIMESTAMP, DType.I64, 2, 3, 0, 'U', 'T', 'C')),
                    Arguments.of("timestamp days", ext(ExtensionId.VORTEX_TIMESTAMP, DType.I64, 4, 0, 0)),
                    Arguments.of("uuid no version", ext(ExtensionId.VORTEX_UUID, UUID_STORAGE)),
                    Arguments.of("uuid v4", ext(ExtensionId.VORTEX_UUID, UUID_STORAGE, 4)),
                    Arguments.of("uuid max 0x0f", ext(ExtensionId.VORTEX_UUID, UUID_STORAGE, 0x0f)),
                    Arguments.of("uuid max 0xff", ext(ExtensionId.VORTEX_UUID, UUID_STORAGE, 0xff)),
                    Arguments.of("date nullable storage", ext(ExtensionId.VORTEX_DATE, DType.I32.asNullable(), 4)));
        }

        static Stream<Arguments> invalid() {
            return Stream.of(
                    Arguments.of(ext(ExtensionId.VORTEX_DATE, DType.I32), "metadata must not be empty"),
                    Arguments.of(ext(ExtensionId.VORTEX_DATE, DType.I32, 9), "invalid time unit"),
                    Arguments.of(ext(ExtensionId.VORTEX_DATE, DType.I64, 4), "for Days must be I32"),
                    Arguments.of(ext(ExtensionId.VORTEX_DATE, DType.I64, 0), "does not support time unit Nanoseconds"),
                    Arguments.of(ext(ExtensionId.VORTEX_DATE, DType.UTF8, 4), "must be I32"),
                    Arguments.of(ext(ExtensionId.VORTEX_TIME, DType.I64, 2), "for Milliseconds must be I32"),
                    Arguments.of(ext(ExtensionId.VORTEX_TIME, DType.I32, 4), "does not support time unit Days"),
                    Arguments.of(ext(ExtensionId.VORTEX_TIMESTAMP, DType.I32, 2, 0, 0), "storage dtype must be I64"),
                    Arguments.of(ext(ExtensionId.VORTEX_TIMESTAMP, DType.I64, 2), "at least 3 bytes"),
                    Arguments.of(ext(ExtensionId.VORTEX_TIMESTAMP, DType.I64, 2, 5, 0, 'U'), "truncated"),
                    Arguments.of(ext(ExtensionId.VORTEX_TIMESTAMP, DType.I64, 2, 1, 0, 0xc3), "not valid UTF-8"),
                    Arguments.of(ext(ExtensionId.VORTEX_UUID, UUID_STORAGE, 4, 0), "0 or 1 bytes"),
                    Arguments.of(ext(ExtensionId.VORTEX_UUID, UUID_STORAGE, 9), "unknown UUID version"),
                    Arguments.of(ext(ExtensionId.VORTEX_UUID, new DType.FixedSizeList(DType.U8, 8, false)), "FixedSizeList<non-null U8, 16>"),
                    Arguments.of(ext(ExtensionId.VORTEX_UUID, new DType.FixedSizeList(DType.U8.asNullable(), 16, false)), "FixedSizeList<non-null U8, 16>"),
                    Arguments.of(ext(ExtensionId.VORTEX_UUID, DType.I64), "FixedSizeList<non-null U8, 16>"));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("valid")
        void validate_validDtype_passes(String name, DType.Extension dtype) {
            // Given a dtype Rust accepts
            ExtensionId.WellKnown sut = (ExtensionId.WellKnown) ExtensionId.parse(dtype.extensionId());

            // When / Then
            sut.validate(dtype);
        }

        @ParameterizedTest
        @MethodSource("invalid")
        void validate_invalidDtype_throws(DType.Extension dtype, String message) {
            // Given a dtype Rust refuses
            ExtensionId.WellKnown sut = (ExtensionId.WellKnown) ExtensionId.parse(dtype.extensionId());

            // When / Then
            assertThatThrownBy(() -> sut.validate(dtype))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining(message);
        }

        @Test
        void validate_nullMetadata_isTreatedAsEmpty() {
            // Given — Java callers may pass null; it means "no metadata", which uuid allows
            DType.Extension dtype = new DType.Extension("vortex.uuid", UUID_STORAGE, null, false);

            // When / Then
            ExtensionId.VORTEX_UUID.validate(dtype);
        }

        private static final DType UUID_STORAGE = new DType.FixedSizeList(DType.U8, 16, false);

        private static DType.Extension ext(ExtensionId id, DType storage, int... meta) {
            byte[] bytes = new byte[meta.length];
            for (int i = 0; i < meta.length; i++) {
                bytes[i] = (byte) meta[i];
            }
            return new DType.Extension(id.id(), storage, MemorySegment.ofArray(bytes), storage.nullable());
        }
    }
}
