package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.encoding.EncodingId;
import io.github.dfa1.vortex.encoding.TestSegments;
import io.github.dfa1.vortex.encoding.TimeUnit;
import io.github.dfa1.vortex.proto.ProtoDateTimePartsMetadata;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.array.LongArray;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DateTimePartsEncodingDecoderTest {

    private static final DateTimePartsEncodingDecoder SUT = new DateTimePartsEncodingDecoder();
    private static final ReadRegistry REGISTRY = TestRegistry.ofDecoders(SUT, new PrimitiveEncodingDecoder());

    private static final long SECONDS_PER_DAY = 86_400L;

    private static MemorySegment i64Meta() {
        return MemorySegment.ofArray(new ProtoDateTimePartsMetadata(
                io.github.dfa1.vortex.proto.ProtoPType.I64,
                io.github.dfa1.vortex.proto.ProtoPType.I64,
                io.github.dfa1.vortex.proto.ProtoPType.I64).encode());
    }

    private static DType timestampDType(TimeUnit unit, boolean nullable) {
        MemorySegment meta = MemorySegment.ofArray(new byte[]{(byte) unit.ordinal(), 0, 0});
        return new DType.Extension("vortex.timestamp",
                new DType.Primitive(PType.I64, nullable), meta, nullable);
    }

    /// Builds a context with three I64 part-children backed by the given segments.
    private static DecodeContext ctx(MemorySegment meta, DType dtype, long n,
            MemorySegment days, MemorySegment seconds, MemorySegment subseconds) {
        ArrayNode d = ArrayNode.of(EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[0], new int[]{0});
        ArrayNode s = ArrayNode.of(EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[0], new int[]{1});
        ArrayNode ss = ArrayNode.of(EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[0], new int[]{2});
        ArrayNode node = ArrayNode.of(EncodingId.VORTEX_DATETIMEPARTS, meta, new ArrayNode[]{d, s, ss}, new int[0]);
        return new DecodeContext(node, dtype, n, new MemorySegment[]{days, seconds, subseconds}, REGISTRY, Arena.ofAuto());
    }

    @Test
    void encodingId_isVortexDateTimeParts() {
        // Given / When / Then
        assertThat(SUT.encodingId()).isEqualTo(EncodingId.VORTEX_DATETIMEPARTS);
    }

    @Test
    void decode_missingMetadata_throws() {
        // Given a node with no metadata
        ArrayNode node = ArrayNode.of(EncodingId.VORTEX_DATETIMEPARTS, null, new ArrayNode[0], new int[0]);
        DecodeContext c = new DecodeContext(node, timestampDType(TimeUnit.Milliseconds, false), 1,
                new MemorySegment[0], REGISTRY, Arena.ofAuto());

        // When / Then
        assertThatThrownBy(() -> SUT.decode(c)).hasMessageContaining("missing metadata");
    }

    @Test
    void decode_milliseconds_reassemblesParts() {
        // Given 1 day + 1h2m3s + 456ms split across the three parts
        long ts = 86_400_000L + 3723L * 1000L + 456L;
        DecodeContext c = ctx(i64Meta(), timestampDType(TimeUnit.Milliseconds, false), 1,
                TestSegments.leLongs(1L), TestSegments.leLongs(3723L), TestSegments.leLongs(456L));

        // When
        LongArray result = (LongArray) SUT.decode(c);

        // Then
        assertThat(result.getLong(0)).isEqualTo(ts);
    }

    @Test
    void decode_daysUnit_usesUnitsPerSecondOne() {
        // Given a Days-unit extension: divisor() throws for Days, so the decoder must
        // special-case it to unitsPerSecond=1 (days only, sub-day parts zero)
        DecodeContext c = ctx(i64Meta(), timestampDType(TimeUnit.Days, false), 1,
                TestSegments.leLongs(2L), TestSegments.leLongs(0L), TestSegments.leLongs(0L));

        // When
        LongArray result = (LongArray) SUT.decode(c);

        // Then 2 days * 86400 s/day * 1 unit/s
        assertThat(result.getLong(0)).isEqualTo(2L * SECONDS_PER_DAY);
    }

    @Test
    void decode_nullableExtension_decodesNullableDaysChild() {
        // Given a nullable extension dtype — the days child is decoded as nullable
        DecodeContext c = ctx(i64Meta(), timestampDType(TimeUnit.Milliseconds, true), 1,
                TestSegments.leLongs(0L), TestSegments.leLongs(0L), TestSegments.leLongs(0L));

        // When
        LongArray result = (LongArray) SUT.decode(c);

        // Then
        assertThat(result.getLong(0)).isZero();
    }

    @Test
    void decode_extensionMissingTimeUnitMetadata_throws() {
        // Given an extension whose metadata byte is absent
        DType noUnit = new DType.Extension("vortex.timestamp",
                DType.I64, null, false);
        DecodeContext c = ctx(i64Meta(), noUnit, 1,
                TestSegments.leLongs(0L), TestSegments.leLongs(0L), TestSegments.leLongs(0L));

        // When / Then
        assertThatThrownBy(() -> SUT.decode(c)).hasMessageContaining("missing TimeUnit metadata");
    }

    @Test
    void decode_nonExtensionDtype_throws() {
        // Given a primitive (non-extension) logical type
        DecodeContext c = ctx(i64Meta(), DType.I64, 1,
                TestSegments.leLongs(0L), TestSegments.leLongs(0L), TestSegments.leLongs(0L));

        // When / Then
        assertThatThrownBy(() -> SUT.decode(c)).hasMessageContaining("expected Extension dtype");
    }
}
