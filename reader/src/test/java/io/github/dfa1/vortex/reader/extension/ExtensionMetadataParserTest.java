package io.github.dfa1.vortex.reader.extension;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.ExtensionId;
import io.github.dfa1.vortex.core.model.ExtensionMetadata;
import io.github.dfa1.vortex.core.model.OpaqueMetadata;
import io.github.dfa1.vortex.core.model.TimeUnit;
import io.github.dfa1.vortex.core.model.TimeUnitMetadata;
import io.github.dfa1.vortex.core.model.TimestampMetadata;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.foreign.MemorySegment;
import java.time.ZoneId;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Rust's `deserialize_metadata` for the temporal extensions (vortex-array/src/extension/datetime),
/// and every other id's bytes kept as they are.
class ExtensionMetadataParserTest {

    static Stream<Arguments> valid() {
        return Stream.of(
                Arguments.of(ExtensionId.VORTEX_DATE, bytes(4), new TimeUnitMetadata(TimeUnit.Days)),
                Arguments.of(ExtensionId.VORTEX_TIME, bytes(0), new TimeUnitMetadata(TimeUnit.Nanoseconds)),
                Arguments.of(ExtensionId.VORTEX_TIMESTAMP, bytes(2, 0, 0), new TimestampMetadata(TimeUnit.Milliseconds, null)),
                Arguments.of(ExtensionId.VORTEX_TIMESTAMP, bytes(1, 3, 0, 'U', 'T', 'C'),
                        new TimestampMetadata(TimeUnit.Microseconds, ZoneId.of("UTC"))),
                // trailing bytes past the declared timezone are ignored, as Rust slices to tz_len
                Arguments.of(ExtensionId.VORTEX_TIMESTAMP, bytes(1, 3, 0, 'U', 'T', 'C', 0),
                        new TimestampMetadata(TimeUnit.Microseconds, ZoneId.of("UTC"))),
                Arguments.of(ExtensionId.VORTEX_UUID, bytes(4), new OpaqueMetadata(bytes(4))),
                Arguments.of(ExtensionId.parse("acme.geopoint"), bytes(1, 2, 3), new OpaqueMetadata(bytes(1, 2, 3))));
    }

    static Stream<Arguments> invalid() {
        return Stream.of(
                Arguments.of(ExtensionId.VORTEX_DATE, bytes(), "metadata must not be empty"),
                Arguments.of(ExtensionId.VORTEX_DATE, bytes(9), "invalid time unit"),
                Arguments.of(ExtensionId.VORTEX_TIMESTAMP, bytes(2), "at least 3 bytes"),
                Arguments.of(ExtensionId.VORTEX_TIMESTAMP, bytes(2, 5, 0, 'U'), "truncated"),
                Arguments.of(ExtensionId.VORTEX_TIMESTAMP, bytes(2, 1, 0, 0xc3), "not valid UTF-8"),
                Arguments.of(ExtensionId.VORTEX_TIMESTAMP, bytes(2, 3, 0, 'X', '/', 'Y'), "not a valid zone id"),
                Arguments.of(ExtensionId.parse("acme.big"), MemorySegment.ofArray(new byte[(int) OpaqueMetadata.MAX_SIZE.bytes() + 1]),
                        "metadata too large"));
    }

    @ParameterizedTest
    @MethodSource("valid")
    void parse_validBytes(ExtensionId id, MemorySegment metadata, ExtensionMetadata expected) {
        // When
        ExtensionMetadata result = ExtensionMetadataParser.parse(id, metadata);

        // Then
        assertThat(result).isEqualTo(expected);
    }

    @ParameterizedTest
    @MethodSource("invalid")
    void parse_malformedBytes_throwsVortexException(ExtensionId id, MemorySegment metadata, String message) {
        // When / Then — the bytes come from an untrusted file
        assertThatThrownBy(() -> ExtensionMetadataParser.parse(id, metadata))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining(message);
    }

    private static MemorySegment bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return MemorySegment.ofArray(out);
    }
}
