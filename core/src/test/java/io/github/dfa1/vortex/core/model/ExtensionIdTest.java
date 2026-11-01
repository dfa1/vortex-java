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
import java.time.ZoneId;
import java.util.List;
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
                    Arguments.of("date days I32", unit(ExtensionId.VORTEX_DATE, DTypes.I32, TimeUnit.Days)),
                    Arguments.of("date millis I64", unit(ExtensionId.VORTEX_DATE, DTypes.I64, TimeUnit.Milliseconds)),
                    Arguments.of("time seconds I32", unit(ExtensionId.VORTEX_TIME, DTypes.I32, TimeUnit.Seconds)),
                    Arguments.of("time millis I32", unit(ExtensionId.VORTEX_TIME, DTypes.I32, TimeUnit.Milliseconds)),
                    Arguments.of("time micros I64", unit(ExtensionId.VORTEX_TIME, DTypes.I64, TimeUnit.Microseconds)),
                    Arguments.of("time nanos I64", unit(ExtensionId.VORTEX_TIME, DTypes.I64, TimeUnit.Nanoseconds)),
                    Arguments.of("timestamp no tz", timestamp(DTypes.I64, TimeUnit.Milliseconds, null)),
                    Arguments.of("timestamp tz", timestamp(DTypes.I64, TimeUnit.Milliseconds, ZoneId.of("UTC"))),
                    Arguments.of("timestamp days", timestamp(DTypes.I64, TimeUnit.Days, null)),
                    Arguments.of("uuid no version", ext(ExtensionId.VORTEX_UUID, UUID_STORAGE)),
                    Arguments.of("uuid v4", ext(ExtensionId.VORTEX_UUID, UUID_STORAGE, 4)),
                    Arguments.of("uuid max 0x0f", ext(ExtensionId.VORTEX_UUID, UUID_STORAGE, 0x0f)),
                    Arguments.of("uuid max 0xff", ext(ExtensionId.VORTEX_UUID, UUID_STORAGE, 0xff)),
                    Arguments.of("date nullable storage", unit(ExtensionId.VORTEX_DATE, DTypes.I32.asNullable(), TimeUnit.Days)),
                    Arguments.of("json", ext(ExtensionId.VORTEX_JSON, DTypes.UTF8)),
                    Arguments.of("wkb", ext(ExtensionId.VORTEX_ST_WKB, DTypes.BINARY)),
                    Arguments.of("point xy", ext(ExtensionId.VORTEX_ST_POINT, coords("x", "y"))),
                    Arguments.of("point xyzm", ext(ExtensionId.VORTEX_ST_POINT, coords("x", "y", "z", "m"))),
                    Arguments.of("linestring", ext(ExtensionId.VORTEX_ST_LINESTRING, lists(coords("x", "y"), 1))),
                    Arguments.of("multipoint", ext(ExtensionId.VORTEX_ST_MULTIPOINT, lists(coords("x", "y", "z"), 1))),
                    Arguments.of("polygon", ext(ExtensionId.VORTEX_ST_POLYGON, lists(coords("x", "y"), 2))),
                    Arguments.of("multilinestring", ext(ExtensionId.VORTEX_ST_MULTILINESTRING, lists(coords("x", "y", "m"), 2))),
                    Arguments.of("multipolygon", ext(ExtensionId.VORTEX_ST_MULTIPOLYGON, lists(coords("x", "y"), 3))),
                    Arguments.of("box xy", ext(ExtensionId.VORTEX_ST_BOX, coords("xmin", "ymin", "xmax", "ymax"))));
        }

        static Stream<Arguments> invalid() {
            return Stream.of(
                    // the wire-format errors (empty, bad tag, truncated, not UTF-8) are the reader's
                    // ExtensionMetadataParser's: here the metadata is already typed
                    Arguments.of(ext(ExtensionId.VORTEX_DATE, DTypes.I32, 4), "must be TimeUnitMetadata"),
                    Arguments.of(unit(ExtensionId.VORTEX_DATE, DTypes.I64, TimeUnit.Days), "for Days must be I32"),
                    Arguments.of(unit(ExtensionId.VORTEX_DATE, DTypes.I64, TimeUnit.Nanoseconds), "does not support time unit Nanoseconds"),
                    Arguments.of(unit(ExtensionId.VORTEX_DATE, DTypes.UTF8, TimeUnit.Days), "must be I32"),
                    Arguments.of(unit(ExtensionId.VORTEX_TIME, DTypes.I64, TimeUnit.Milliseconds), "for Milliseconds must be I32"),
                    Arguments.of(unit(ExtensionId.VORTEX_TIME, DTypes.I32, TimeUnit.Days), "does not support time unit Days"),
                    Arguments.of(timestamp(DTypes.I32, TimeUnit.Milliseconds, null), "storage dtype must be I64"),
                    Arguments.of(unit(ExtensionId.VORTEX_TIMESTAMP, DTypes.I64, TimeUnit.Milliseconds), "must be TimestampMetadata"),
                    Arguments.of(unit(ExtensionId.VORTEX_UUID, UUID_STORAGE, TimeUnit.Days), "must be OpaqueMetadata"),
                    Arguments.of(ext(ExtensionId.VORTEX_UUID, UUID_STORAGE, 4, 0), "0 or 1 bytes"),
                    Arguments.of(ext(ExtensionId.VORTEX_UUID, UUID_STORAGE, 9), "unknown UUID version"),
                    Arguments.of(ext(ExtensionId.VORTEX_UUID, new DType.FixedSizeList(DTypes.U8, 8, false)), "FixedSizeList<non-null U8, 16>"),
                    Arguments.of(ext(ExtensionId.VORTEX_UUID, new DType.FixedSizeList(DTypes.U8.asNullable(), 16, false)), "FixedSizeList<non-null U8, 16>"),
                    Arguments.of(ext(ExtensionId.VORTEX_UUID, DTypes.I64), "FixedSizeList<non-null U8, 16>"),
                    Arguments.of(ext(ExtensionId.VORTEX_JSON, DTypes.BINARY), "storage dtype must be Utf8"),
                    Arguments.of(ext(ExtensionId.VORTEX_JSON, DTypes.UTF8, 1), "metadata must be empty"),
                    Arguments.of(ext(ExtensionId.VORTEX_ST_WKB, DTypes.UTF8), "storage dtype must be Binary"),
                    Arguments.of(ext(ExtensionId.VORTEX_ST_POINT, coords("y", "x")), "not a valid GeoArrow dimension"),
                    Arguments.of(ext(ExtensionId.VORTEX_ST_POINT, new DType.Struct(
                            List.of(ColumnName.of("x"), ColumnName.of("y")), List.of(DTypes.F64, DTypes.F32), false)),
                            "must be non-nullable F64"),
                    Arguments.of(ext(ExtensionId.VORTEX_ST_POINT, DTypes.F64), "must be a Struct"),
                    Arguments.of(ext(ExtensionId.VORTEX_ST_POLYGON, lists(coords("x", "y"), 1)), "must nest 2 List level(s)"),
                    Arguments.of(ext(ExtensionId.VORTEX_ST_MULTIPOLYGON, coords("x", "y")), "must nest 3 List level(s)"),
                    Arguments.of(ext(ExtensionId.VORTEX_ST_BOX, coords("x", "y")), "not a valid GeoArrow dimension"));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("valid")
        void validate_validDtype_passes(String name, DType.Extension dtype) {
            // Given a dtype Rust accepts
            ExtensionId.WellKnown sut = (ExtensionId.WellKnown) dtype.extensionId();

            // When / Then
            sut.validate(dtype);
        }

        @ParameterizedTest
        @MethodSource("invalid")
        void validate_invalidDtype_throws(DType.Extension dtype, String message) {
            // Given a dtype Rust refuses
            ExtensionId.WellKnown sut = (ExtensionId.WellKnown) dtype.extensionId();

            // When / Then
            assertThatThrownBy(() -> sut.validate(dtype))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining(message);
        }

        private static final DType UUID_STORAGE = new DType.FixedSizeList(DTypes.U8, 16, false);

        /// A GeoArrow coordinate (or box) struct of non-nullable F64 fields.
        private static DType coords(String... names) {
            return new DType.Struct(java.util.Arrays.stream(names).map(ColumnName::of).toList(),
                    java.util.Arrays.stream(names).map(n -> (DType) DTypes.F64).toList(), false);
        }

        private static DType lists(DType element, int depth) {
            DType out = element;
            for (int i = 0; i < depth; i++) {
                out = new DType.List(out, false);
            }
            return out;
        }

        private static DType.Extension ext(ExtensionId id, DType storage, int... meta) {
            byte[] bytes = new byte[meta.length];
            for (int i = 0; i < meta.length; i++) {
                bytes[i] = (byte) meta[i];
            }
            return new DType.Extension(id, storage, new OpaqueMetadata(MemorySegment.ofArray(bytes)),
                    storage.nullable());
        }

        private static DType.Extension unit(ExtensionId id, DType storage, TimeUnit unit) {
            return new DType.Extension(id, storage, new TimeUnitMetadata(unit), storage.nullable());
        }

        private static DType.Extension timestamp(DType storage, TimeUnit unit, ZoneId zone) {
            return new DType.Extension(ExtensionId.VORTEX_TIMESTAMP, storage, TimestampMetadata.of(unit, zone),
                    storage.nullable());
        }
    }
}
