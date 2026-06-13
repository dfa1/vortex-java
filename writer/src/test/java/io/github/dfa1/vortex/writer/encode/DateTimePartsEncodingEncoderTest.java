package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.reader.array.GenericArray;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.decode.ArrayNode;
import io.github.dfa1.vortex.encoding.DTypes;
import io.github.dfa1.vortex.reader.decode.DecodeContext;

import io.github.dfa1.vortex.encoding.EncodingId;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.decode.TestRegistry;
import io.github.dfa1.vortex.encoding.TimeUnit;
import io.github.dfa1.vortex.proto.DateTimePartsMetadata;
import io.github.dfa1.vortex.reader.decode.DateTimePartsEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.PrimitiveEncodingDecoder;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DateTimePartsEncodingEncoderTest {

    private static final DateTimePartsEncodingEncoder ENCODER = new DateTimePartsEncodingEncoder();
    private static final DateTimePartsEncodingDecoder DECODER = new DateTimePartsEncodingDecoder();
    private static final ReadRegistry REGISTRY = TestRegistry.ofDecoders(DECODER, new PrimitiveEncodingDecoder());

    private static final DType EXT_TIMESTAMP_MS = timestampDType(TimeUnit.Milliseconds);
    private static final DType EXT_TIMESTAMP_NS = timestampDType(TimeUnit.Nanoseconds);

    private static DType timestampDType(TimeUnit unit) {
        ByteBuffer meta = ByteBuffer.allocate(3).order(ByteOrder.LITTLE_ENDIAN);
        meta.put((byte) unit.ordinal());
        meta.putShort((short) 0);
        meta.flip();
        return new DType.Extension("vortex.timestamp",
                new DType.Primitive(PType.I64, false), meta, false);
    }

    private static ArrayNode toArrayNode(EncodeNode node) {
        ArrayNode[] children = new ArrayNode[node.children().length];
        for (int i = 0; i < children.length; i++) {
            children[i] = toArrayNode(node.children()[i]);
        }
        return ArrayNode.of(node.encodingId(), node.metadata(), children, node.bufferIndices());
    }

    @Nested
    class Encode {

        @Test
        void accepts_extensionDtype_true() {
            assertThat(ENCODER.accepts(EXT_TIMESTAMP_MS)).isTrue();
        }

        @Test
        void accepts_primitiveDtype_false() {
            assertThat(ENCODER.accepts(DTypes.I64)).isFalse();
        }

        @Test
        void encode_producesThreeChildren_noBuffersAtRoot() {
            long[] timestamps = {0L, 86_400_000L};
            DateTimePartsData data = new DateTimePartsData(timestamps, false);

            EncodeResult result = ENCODER.encode(EXT_TIMESTAMP_MS, data, EncodeTestHelper.testCtx());

            assertThat(result.rootNode().encodingId()).isEqualTo(EncodingId.VORTEX_DATETIMEPARTS);
            assertThat(result.rootNode().bufferIndices()).isEmpty();
            assertThat(result.rootNode().children()).hasSize(3);
        }

        @Test
        void encode_missingMetadata_throws() {
            DType noMeta = new DType.Extension("vortex.timestamp",
                    new DType.Primitive(PType.I64, false), null, false);
            DateTimePartsData data = new DateTimePartsData(new long[]{0L}, false);

            assertThatThrownBy(() -> ENCODER.encode(noMeta, data, EncodeTestHelper.testCtx()))
                    .hasMessageContaining("extension metadata missing");
        }
    }

    @Nested
    class Decode {

        @Test
        void roundTrip_milliseconds_preservesDaysSecondsSubseconds() {
            long msPerDay = 86_400_000L;
            long ts = msPerDay + (3723L * 1000L) + 456L;
            long[] timestamps = {ts};
            DateTimePartsData data = new DateTimePartsData(timestamps, false);

            EncodeResult result = ENCODER.encode(EXT_TIMESTAMP_MS, data, EncodeTestHelper.testCtx());
            MemorySegment[] bufs = result.buffers().toArray(MemorySegment[]::new);
            DecodeContext ctx = new DecodeContext(
                    toArrayNode(result.rootNode()), EXT_TIMESTAMP_MS, 1, bufs, REGISTRY, Arena.global());
            GenericArray decoded = (GenericArray) DECODER.decode(ctx);

            assertThat(decoded.length()).isEqualTo(1);
            LongArray days = (LongArray) decoded.child(0);
            LongArray seconds = (LongArray) decoded.child(1);
            LongArray subseconds = (LongArray) decoded.child(2);
            assertThat(days.getLong(0)).isEqualTo(1L);
            assertThat(seconds.getLong(0)).isEqualTo(3723L);
            assertThat(subseconds.getLong(0)).isEqualTo(456L);
        }

        @Test
        void roundTrip_nanoseconds_preservesSubsecondPrecision() {
            long nsPerDay = 86_400_000_000_000L;
            long ts = nsPerDay + (3723L * 1_000_000_000L) + 456_789_123L;
            long[] timestamps = {ts};
            DateTimePartsData data = new DateTimePartsData(timestamps, false);

            EncodeResult result = ENCODER.encode(EXT_TIMESTAMP_NS, data, EncodeTestHelper.testCtx());
            MemorySegment[] bufs = result.buffers().toArray(MemorySegment[]::new);
            DecodeContext ctx = new DecodeContext(
                    toArrayNode(result.rootNode()), EXT_TIMESTAMP_NS, 1, bufs, REGISTRY, Arena.global());
            GenericArray decoded = (GenericArray) DECODER.decode(ctx);

            LongArray days = (LongArray) decoded.child(0);
            LongArray seconds = (LongArray) decoded.child(1);
            LongArray subseconds = (LongArray) decoded.child(2);
            assertThat(days.getLong(0)).isEqualTo(1L);
            assertThat(seconds.getLong(0)).isEqualTo(3723L);
            assertThat(subseconds.getLong(0)).isEqualTo(456_789_123L);
        }

        @Test
        void roundTrip_epoch_allZero() {
            long[] timestamps = {0L};
            DateTimePartsData data = new DateTimePartsData(timestamps, false);

            EncodeResult result = ENCODER.encode(EXT_TIMESTAMP_MS, data, EncodeTestHelper.testCtx());
            MemorySegment[] bufs = result.buffers().toArray(MemorySegment[]::new);
            DecodeContext ctx = new DecodeContext(
                    toArrayNode(result.rootNode()), EXT_TIMESTAMP_MS, 1, bufs, REGISTRY, Arena.global());
            GenericArray decoded = (GenericArray) DECODER.decode(ctx);

            LongArray days = (LongArray) decoded.child(0);
            LongArray seconds = (LongArray) decoded.child(1);
            LongArray subseconds = (LongArray) decoded.child(2);
            assertThat(days.getLong(0)).isEqualTo(0L);
            assertThat(seconds.getLong(0)).isEqualTo(0L);
            assertThat(subseconds.getLong(0)).isEqualTo(0L);
        }

        @Test
        void roundTrip_multipleTimestamps_allRowsPreserved() {
            long msPerDay = 86_400_000L;
            long[] timestamps = {0L, msPerDay, msPerDay + 1000L, msPerDay + 1001L};
            DateTimePartsData data = new DateTimePartsData(timestamps, false);

            EncodeResult result = ENCODER.encode(EXT_TIMESTAMP_MS, data, EncodeTestHelper.testCtx());
            MemorySegment[] bufs = result.buffers().toArray(MemorySegment[]::new);
            DecodeContext ctx = new DecodeContext(
                    toArrayNode(result.rootNode()), EXT_TIMESTAMP_MS, 4, bufs, REGISTRY, Arena.global());
            GenericArray decoded = (GenericArray) DECODER.decode(ctx);

            assertThat(decoded.length()).isEqualTo(4);
            LongArray days = (LongArray) decoded.child(0);
            assertThat(days.getLong(0)).isEqualTo(0L);
            assertThat(days.getLong(1)).isEqualTo(1L);
            assertThat(days.getLong(2)).isEqualTo(1L);
            assertThat(days.getLong(3)).isEqualTo(1L);
            LongArray subseconds = (LongArray) decoded.child(2);
            assertThat(subseconds.getLong(2)).isEqualTo(0L);
            assertThat(subseconds.getLong(3)).isEqualTo(1L);
        }

        @ParameterizedTest
        @EnumSource(value = TimeUnit.class, names = {"Nanoseconds", "Microseconds", "Milliseconds", "Seconds"})
        void roundTrip_allUnits_epochIsZero(TimeUnit unit) {
            DType dtype = timestampDType(unit);
            long[] timestamps = {0L};
            DateTimePartsData data = new DateTimePartsData(timestamps, false);

            EncodeResult result = ENCODER.encode(dtype, data, EncodeTestHelper.testCtx());
            MemorySegment[] bufs = result.buffers().toArray(MemorySegment[]::new);
            DecodeContext ctx = new DecodeContext(
                    toArrayNode(result.rootNode()), dtype, 1, bufs, REGISTRY, Arena.global());
            GenericArray decoded = (GenericArray) DECODER.decode(ctx);

            LongArray days = (LongArray) decoded.child(0);
            LongArray seconds = (LongArray) decoded.child(1);
            LongArray subseconds = (LongArray) decoded.child(2);
            assertThat(days.getLong(0)).isZero();
            assertThat(seconds.getLong(0)).isZero();
            assertThat(subseconds.getLong(0)).isZero();
        }

        @Test
        void encode_metadata_ptypes_areI64() throws Exception {
            long[] timestamps = {0L, 86_400_000L};
            DateTimePartsData data = new DateTimePartsData(timestamps, false);

            EncodeResult result = ENCODER.encode(EXT_TIMESTAMP_MS, data, EncodeTestHelper.testCtx());
            var metaSeg = java.lang.foreign.MemorySegment.ofBuffer(result.rootNode().metadata().duplicate());
            DateTimePartsMetadata meta = DateTimePartsMetadata.decode(metaSeg, 0, metaSeg.byteSize());

            assertThat(meta.days_ptype().value()).isEqualTo(7);
            assertThat(meta.seconds_ptype().value()).isEqualTo(7);
            assertThat(meta.subseconds_ptype().value()).isEqualTo(7);
        }
    }
}
