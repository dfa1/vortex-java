package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.ExtensionId;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.decode.ArrayNode;
import io.github.dfa1.vortex.core.model.DTypes;
import io.github.dfa1.vortex.reader.decode.DecodeContext;

import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.decode.TestRegistry;
import io.github.dfa1.vortex.core.model.TimeUnit;
import io.github.dfa1.vortex.core.proto.ProtoDateTimePartsMetadata;
import io.github.dfa1.vortex.core.proto.ProtoScalarValue;
import io.github.dfa1.vortex.core.model.TimestampMetadata;
import io.github.dfa1.vortex.reader.decode.DateTimePartsEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.PrimitiveEncodingDecoder;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DateTimePartsEncodingEncoderTest {

    private static final DateTimePartsEncodingEncoder ENCODER = new DateTimePartsEncodingEncoder();
    private static final DateTimePartsEncodingDecoder DECODER = new DateTimePartsEncodingDecoder();
    private static final ReadRegistry REGISTRY = TestRegistry.ofDecoders(DECODER, new PrimitiveEncodingDecoder());

    private static final DType EXT_TIMESTAMP_MS = timestampDType(TimeUnit.Milliseconds);
    private static final DType EXT_TIMESTAMP_NS = timestampDType(TimeUnit.Nanoseconds);

    private static DType timestampDType(TimeUnit unit) {
        return new DType.Extension(ExtensionId.VORTEX_TIMESTAMP,
                DTypes.I64, new TimestampMetadata(unit, null), false);
    }

    private static ArrayNode toArrayNode(EncodeNode node) {
        ArrayNode[] children = new ArrayNode[node.children().length];
        for (int i = 0; i < children.length; i++) {
            children[i] = toArrayNode(node.children()[i]);
        }
        return new ArrayNode(node.encodingId(), node.metadata(), children, node.bufferIndices());
    }

    @Nested
    class Encode {

        @Test
        void accepts_extensionDtype_true() {
            // Given
            // When
            boolean result = ENCODER.accepts(EXT_TIMESTAMP_MS);

            // Then
            assertThat(result).isTrue();
        }

        @Test
        void accepts_primitiveDtype_false() {
            // Given
            // When
            boolean result = ENCODER.accepts(DTypes.I64);

            // Then
            assertThat(result).isFalse();
        }

        @Test
        void encode_producesThreeChildren_noBuffersAtRoot() {
            // Given
            long[] timestamps = {0L, 86_400_000L};
            DateTimePartsData data = new DateTimePartsData(timestamps, false);

            // When
            EncodeResult result = ENCODER.encode(EXT_TIMESTAMP_MS, data, EncodeTestHelper.testCtx());

            // Then
            assertThat(result.rootNode().encodingId()).isEqualTo(EncodingId.VORTEX_DATETIMEPARTS);
            assertThat(result.rootNode().bufferIndices()).isEmpty();
            assertThat(result.rootNode().children()).hasSize(3);
        }

        @Test
        void encode_missingMetadata_throws() {
            // Given
            DType noMeta = new DType.Extension(ExtensionId.VORTEX_TIMESTAMP,
                    DTypes.I64, io.github.dfa1.vortex.core.model.OpaqueMetadata.EMPTY, false);
            DateTimePartsData data = new DateTimePartsData(new long[]{0L}, false);

            // When / Then
            assertThatThrownBy(() -> ENCODER.encode(noMeta, data, EncodeTestHelper.testCtx()))
                    .hasMessageContaining("extension metadata is not timestamp metadata");
        }
    }

    /// A nullable timestamp's validity goes on the days child, as Rust's `split_temporal` keeps it.
    @Nested
    class Nullable {

        private static final DType NULLABLE_MS = new DType.Extension(ExtensionId.VORTEX_TIMESTAMP,
                DTypes.I64.asNullable(), new TimestampMetadata(TimeUnit.Milliseconds, null),
                true);

        @Test
        void encodeCascade_withNulls_putsValidityOnDaysChildOnly() {
            // Given
            boolean[] validity = {true, false, true};
            NullableData data = new NullableData(new long[]{86_400_000L, 0L, 2 * 86_400_000L}, validity);

            // When
            CascadeStep result = ENCODER.encodeCascade(NULLABLE_MS, data, EncodeTestHelper.testCtx());

            // Then
            assertThat(result.applicable()).isTrue();
            assertThat(result.openChildren().get(0).childDtype()).isEqualTo(DTypes.U8.asNullable());
            assertThat(result.openChildren().get(0).childData()).isInstanceOfSatisfying(NullableData.class, days -> {
                assertThat((byte[]) days.values()).containsExactly(1, 0, 2);
                assertThat(days.validity()).containsExactly(validity);
            });
            assertThat(result.openChildren().get(1).childDtype().nullable()).isFalse();
            assertThat(result.openChildren().get(2).childDtype().nullable()).isFalse();
        }

        @Test
        void encodeCascade_withNulls_takesBoundsOverValidRowsOnly() {
            // Given — the invalid slot's 0 placeholder is below every valid value
            NullableData data = new NullableData(new long[]{5_000L, 0L, 9_000L}, new boolean[]{true, false, true});

            // When
            CascadeStep result = ENCODER.encodeCascade(NULLABLE_MS, data, EncodeTestHelper.testCtx());

            // Then
            assertThat(result.statsMin()).isEqualTo(ProtoScalarValue.ofInt64Value(5_000L));
            assertThat(result.statsMax()).isEqualTo(ProtoScalarValue.ofInt64Value(9_000L));
        }

        @Test
        void encodeCascade_allValid_writesNoValidity() {
            // Given — Rust's AllValid carries no bitmap
            NullableData data = new NullableData(new long[]{1L, 2L}, new boolean[]{true, true});

            // When
            CascadeStep result = ENCODER.encodeCascade(NULLABLE_MS, data, EncodeTestHelper.testCtx());

            // Then
            assertThat(result.openChildren().getFirst().childDtype()).isEqualTo(DTypes.U8.asNullable());
            assertThat(result.openChildren().getFirst().childData()).isInstanceOf(byte[].class);
        }

        @Test
        void encodeCascade_allNull_isNotApplicable() {
            // Given — vortex.ext writes an all-null chunk as the null constant
            NullableData data = new NullableData(new long[]{0L, 0L}, new boolean[]{false, false});

            // When
            CascadeStep result = ENCODER.encodeCascade(NULLABLE_MS, data, EncodeTestHelper.testCtx());

            // Then
            assertThat(result.applicable()).isFalse();
        }

        @Test
        void encode_withNulls_keepsValidityUnderDaysChild() {
            // Given
            NullableData data = new NullableData(new long[]{86_400_000L, 0L}, new boolean[]{true, false});

            // When
            EncodeResult result = ENCODER.encode(NULLABLE_MS, data, EncodeTestHelper.testCtx());

            // Then — a bool validity under days, none under seconds or subseconds
            EncodeNode[] children = result.rootNode().children();
            assertThat(result.rootNode().encodingId()).isEqualTo(EncodingId.VORTEX_DATETIMEPARTS);
            assertThat(children).hasSize(3);
            assertThat(contains(children[0], EncodingId.VORTEX_BOOL)).isTrue();
            assertThat(contains(children[1], EncodingId.VORTEX_BOOL)).isFalse();
            assertThat(contains(children[2], EncodingId.VORTEX_BOOL)).isFalse();
        }

        private static boolean contains(EncodeNode node, EncodingId id) {
            if (node.encodingId() == id) {
                return true;
            }
            for (EncodeNode child : node.children()) {
                if (contains(child, id)) {
                    return true;
                }
            }
            return false;
        }
    }

    @Nested
    class Decode {

        @Test
        void roundTrip_milliseconds_preservesDaysSecondsSubseconds() {
            // Given
            long msPerDay = 86_400_000L;
            long ts = msPerDay + (3723L * 1000L) + 456L;
            long[] timestamps = {ts};
            DateTimePartsData data = new DateTimePartsData(timestamps, false);
            EncodeResult encoded = ENCODER.encode(EXT_TIMESTAMP_MS, data, EncodeTestHelper.testCtx());
            MemorySegment[] bufs = encoded.buffers().toArray(MemorySegment[]::new);
            DecodeContext ctx = new DecodeContext(
                    toArrayNode(encoded.rootNode()), EXT_TIMESTAMP_MS, 1, bufs, REGISTRY, Arena.global());

            // When
            LongArray result = (LongArray) DECODER.decode(ctx);

            // Then
            assertThat(result.length()).isEqualTo(1);
            assertThat(result.getLong(0)).isEqualTo(ts);
        }

        @Test
        void roundTrip_nanoseconds_preservesSubsecondPrecision() {
            // Given
            long nsPerDay = 86_400_000_000_000L;
            long ts = nsPerDay + (3723L * 1_000_000_000L) + 456_789_123L;
            long[] timestamps = {ts};
            DateTimePartsData data = new DateTimePartsData(timestamps, false);
            EncodeResult encoded = ENCODER.encode(EXT_TIMESTAMP_NS, data, EncodeTestHelper.testCtx());
            MemorySegment[] bufs = encoded.buffers().toArray(MemorySegment[]::new);
            DecodeContext ctx = new DecodeContext(
                    toArrayNode(encoded.rootNode()), EXT_TIMESTAMP_NS, 1, bufs, REGISTRY, Arena.global());

            // When
            LongArray result = (LongArray) DECODER.decode(ctx);

            // Then
            assertThat(result.getLong(0)).isEqualTo(ts);
        }

        @Test
        void roundTrip_epoch_allZero() {
            // Given
            long[] timestamps = {0L};
            DateTimePartsData data = new DateTimePartsData(timestamps, false);
            EncodeResult encoded = ENCODER.encode(EXT_TIMESTAMP_MS, data, EncodeTestHelper.testCtx());
            MemorySegment[] bufs = encoded.buffers().toArray(MemorySegment[]::new);
            DecodeContext ctx = new DecodeContext(
                    toArrayNode(encoded.rootNode()), EXT_TIMESTAMP_MS, 1, bufs, REGISTRY, Arena.global());

            // When
            LongArray result = (LongArray) DECODER.decode(ctx);

            // Then
            assertThat(result.getLong(0)).isZero();
        }

        @Test
        void roundTrip_multipleTimestamps_allRowsPreserved() {
            // Given
            long msPerDay = 86_400_000L;
            long[] timestamps = {0L, msPerDay, msPerDay + 1000L, msPerDay + 1001L};
            DateTimePartsData data = new DateTimePartsData(timestamps, false);
            EncodeResult encoded = ENCODER.encode(EXT_TIMESTAMP_MS, data, EncodeTestHelper.testCtx());
            MemorySegment[] bufs = encoded.buffers().toArray(MemorySegment[]::new);
            DecodeContext ctx = new DecodeContext(
                    toArrayNode(encoded.rootNode()), EXT_TIMESTAMP_MS, 4, bufs, REGISTRY, Arena.global());

            // When
            LongArray result = (LongArray) DECODER.decode(ctx);

            // Then
            assertThat(result.length()).isEqualTo(4);
            for (int i = 0; i < timestamps.length; i++) {
                assertThat(result.getLong(i)).as("row %d", i).isEqualTo(timestamps[i]);
            }
        }

        @ParameterizedTest
        @EnumSource(value = TimeUnit.class, names = {"Nanoseconds", "Microseconds", "Milliseconds", "Seconds"})
        void roundTrip_allUnits_epochIsZero(TimeUnit unit) {
            // Given
            DType dtype = timestampDType(unit);
            long[] timestamps = {0L};
            DateTimePartsData data = new DateTimePartsData(timestamps, false);
            EncodeResult encoded = ENCODER.encode(dtype, data, EncodeTestHelper.testCtx());
            MemorySegment[] bufs = encoded.buffers().toArray(MemorySegment[]::new);
            DecodeContext ctx = new DecodeContext(
                    toArrayNode(encoded.rootNode()), dtype, 1, bufs, REGISTRY, Arena.global());

            // When
            LongArray result = (LongArray) DECODER.decode(ctx);

            // Then
            assertThat(result.getLong(0)).isZero();
        }

        @Test
        void encode_metadata_namesEachPartsNarrowedPtype() throws Exception {
            // Given — Rust narrows each part before compressing it: days 19723 fits U16 and seconds
            // 86399 needs U32. One millisecond before the epoch truncates to day 0, second 0,
            // subsecond -1, as Rust splits it, so only subseconds go signed; flooring would have
            // made it day -1 and the days signed instead
            long[] timestamps = {19_723L * 86_400_000L + 86_399_000L + 999L, -1L};
            DateTimePartsData data = new DateTimePartsData(timestamps, false);

            // When
            EncodeResult result = ENCODER.encode(EXT_TIMESTAMP_MS, data, EncodeTestHelper.testCtx());

            // Then
            var metaSeg = result.rootNode().metadata();
            ProtoDateTimePartsMetadata meta = ProtoDateTimePartsMetadata.decode(metaSeg, 0, metaSeg.byteSize());

            assertThat(PType.fromOrdinal(meta.days_ptype().value())).isEqualTo(PType.U16);
            assertThat(PType.fromOrdinal(meta.seconds_ptype().value())).isEqualTo(PType.U32);
            assertThat(PType.fromOrdinal(meta.subseconds_ptype().value())).isEqualTo(PType.I16);
        }

        @Test
        void encode_preEpoch_roundTripsTheNegativeParts() {
            // Given — pre-epoch values whose truncated parts are all negative, including one that
            // is not a whole second
            long[] timestamps = {-1L, -1_000L, -86_400_001L, -1_704_067_200_123L};
            DateTimePartsData data = new DateTimePartsData(timestamps, false);
            EncodeResult encoded = ENCODER.encode(EXT_TIMESTAMP_MS, data, EncodeTestHelper.testCtx());
            MemorySegment[] bufs = encoded.buffers().toArray(MemorySegment[]::new);
            DecodeContext ctx = new DecodeContext(toArrayNode(encoded.rootNode()), EXT_TIMESTAMP_MS,
                    timestamps.length, bufs, REGISTRY, Arena.global());

            // When
            LongArray result = (LongArray) DECODER.decode(ctx);

            // Then
            for (int i = 0; i < timestamps.length; i++) {
                assertThat(result.getLong(i)).isEqualTo(timestamps[i]);
            }
        }
    }
}
