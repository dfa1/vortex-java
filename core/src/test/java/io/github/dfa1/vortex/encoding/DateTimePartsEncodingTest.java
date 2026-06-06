package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.core.ArrayStats;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.array.GenericArray;
import io.github.dfa1.vortex.core.array.LongArray;
import io.github.dfa1.vortex.proto.EncodingProtos;
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

class DateTimePartsEncodingTest {

    private static final DType EXT_TIMESTAMP_MS = timestampDType(TimeUnit.Milliseconds);
    private static final DType EXT_TIMESTAMP_NS = timestampDType(TimeUnit.Nanoseconds);

    private static DType timestampDType(TimeUnit unit) {
        // Rust hand-rolled: byte[0]=unit tag, bytes[1-2]=tz_len u16 LE (0 = no tz)
        ByteBuffer meta = ByteBuffer.allocate(3).order(ByteOrder.LITTLE_ENDIAN);
        meta.put((byte) unit.ordinal());
        meta.putShort((short) 0); // no timezone
        meta.flip();
        return new DType.Extension("vortex.timestamp",
                new DType.Primitive(PType.I64, false), meta, false);
    }

    private static ArrayNode toArrayNode(EncodeNode node) {
        ArrayNode[] children = new ArrayNode[node.children().length];
        for (int i = 0; i < children.length; i++) {
            children[i] = toArrayNode(node.children()[i]);
        }
        return ArrayNode.of(node.encodingId(), node.metadata(), children, node.bufferIndices(), ArrayStats.empty());
    }

    private static EncodingRegistry registry() {
        EncodingRegistry r = EncodingRegistry.empty();
        r.register(new DateTimePartsEncoding());
        r.register(new PrimitiveEncoding());
        return r;
    }

    @Nested
    class Encode {

        @Test
        void accepts_extensionDtype_true() {
            // Given
            DateTimePartsEncoding sut = new DateTimePartsEncoding();

            // When / Then
            assertThat(sut.accepts(EXT_TIMESTAMP_MS)).isTrue();
        }

        @Test
        void accepts_primitiveDtype_false() {
            // Given
            DateTimePartsEncoding sut = new DateTimePartsEncoding();

            // When / Then
            assertThat(sut.accepts(DTypes.I64)).isFalse();
        }

        @Test
        void encode_producesThreeChildren_noBuffersAtRoot() {
            // Given
            long[] timestamps = {0L, 86_400_000L};
            DateTimePartsData data = new DateTimePartsData(timestamps, false);
            DateTimePartsEncoding sut = new DateTimePartsEncoding();

            // When
            EncodeResult result = sut.encode(EXT_TIMESTAMP_MS, data, EncodeTestHelper.testCtx());

            // Then
            assertThat(result.rootNode().encodingId()).isEqualTo(EncodingId.VORTEX_DATETIMEPARTS);
            assertThat(result.rootNode().bufferIndices()).isEmpty();
            assertThat(result.rootNode().children()).hasSize(3);
        }

        @Test
        void encode_missingMetadata_throws() {
            // Given
            DType noMeta = new DType.Extension("vortex.timestamp",
                    new DType.Primitive(PType.I64, false), null, false);
            DateTimePartsEncoding sut = new DateTimePartsEncoding();
            DateTimePartsData data = new DateTimePartsData(new long[]{0L}, false);

            // When / Then
            assertThatThrownBy(() -> sut.encode(noMeta, data, EncodeTestHelper.testCtx()))
                    .hasMessageContaining("extension metadata missing");
        }
    }

    @Nested
    class Decode {

        @Test
        void roundTrip_milliseconds_preservesDaysSecondsSubseconds() {
            // Given
            // 1970-01-02 01:02:03.456 UTC in millis
            long msPerDay = 86_400_000L;
            long ts = msPerDay + (3723L * 1000L) + 456L;
            long[] timestamps = {ts};
            DateTimePartsData data = new DateTimePartsData(timestamps, false);
            DateTimePartsEncoding sut = new DateTimePartsEncoding();

            // When
            EncodeResult result = sut.encode(EXT_TIMESTAMP_MS, data, EncodeTestHelper.testCtx());
            MemorySegment[] bufs = result.buffers().toArray(MemorySegment[]::new);
            DecodeContext ctx = new DecodeContext(
                    toArrayNode(result.rootNode()), EXT_TIMESTAMP_MS, 1, bufs, registry(), Arena.global());
            GenericArray decoded = (GenericArray) sut.decode(ctx);

            // Then
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
            // Given
            // 1970-01-02 01:02:03.456789123 UTC in nanos
            long nsPerDay = 86_400_000_000_000L;
            long ts = nsPerDay + (3723L * 1_000_000_000L) + 456_789_123L;
            long[] timestamps = {ts};
            DateTimePartsData data = new DateTimePartsData(timestamps, false);
            DateTimePartsEncoding sut = new DateTimePartsEncoding();

            // When
            EncodeResult result = sut.encode(EXT_TIMESTAMP_NS, data, EncodeTestHelper.testCtx());
            MemorySegment[] bufs = result.buffers().toArray(MemorySegment[]::new);
            DecodeContext ctx = new DecodeContext(
                    toArrayNode(result.rootNode()), EXT_TIMESTAMP_NS, 1, bufs, registry(), Arena.global());
            GenericArray decoded = (GenericArray) sut.decode(ctx);

            // Then
            LongArray days = (LongArray) decoded.child(0);
            LongArray seconds = (LongArray) decoded.child(1);
            LongArray subseconds = (LongArray) decoded.child(2);
            assertThat(days.getLong(0)).isEqualTo(1L);
            assertThat(seconds.getLong(0)).isEqualTo(3723L);
            assertThat(subseconds.getLong(0)).isEqualTo(456_789_123L);
        }

        @Test
        void roundTrip_epoch_allZero() {
            // Given
            long[] timestamps = {0L};
            DateTimePartsData data = new DateTimePartsData(timestamps, false);
            DateTimePartsEncoding sut = new DateTimePartsEncoding();

            // When
            EncodeResult result = sut.encode(EXT_TIMESTAMP_MS, data, EncodeTestHelper.testCtx());
            MemorySegment[] bufs = result.buffers().toArray(MemorySegment[]::new);
            DecodeContext ctx = new DecodeContext(
                    toArrayNode(result.rootNode()), EXT_TIMESTAMP_MS, 1, bufs, registry(), Arena.global());
            GenericArray decoded = (GenericArray) sut.decode(ctx);

            // Then
            LongArray days = (LongArray) decoded.child(0);
            LongArray seconds = (LongArray) decoded.child(1);
            LongArray subseconds = (LongArray) decoded.child(2);
            assertThat(days.getLong(0)).isEqualTo(0L);
            assertThat(seconds.getLong(0)).isEqualTo(0L);
            assertThat(subseconds.getLong(0)).isEqualTo(0L);
        }

        @Test
        void roundTrip_multipleTimestamps_allRowsPreserved() {
            // Given
            long msPerDay = 86_400_000L;
            long[] timestamps = {0L, msPerDay, msPerDay + 1000L, msPerDay + 1001L};
            DateTimePartsData data = new DateTimePartsData(timestamps, false);
            DateTimePartsEncoding sut = new DateTimePartsEncoding();

            // When
            EncodeResult result = sut.encode(EXT_TIMESTAMP_MS, data, EncodeTestHelper.testCtx());
            MemorySegment[] bufs = result.buffers().toArray(MemorySegment[]::new);
            DecodeContext ctx = new DecodeContext(
                    toArrayNode(result.rootNode()), EXT_TIMESTAMP_MS, 4, bufs, registry(), Arena.global());
            GenericArray decoded = (GenericArray) sut.decode(ctx);

            // Then
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
            // Given
            DType dtype = timestampDType(unit);
            long[] timestamps = {0L};
            DateTimePartsData data = new DateTimePartsData(timestamps, false);
            DateTimePartsEncoding sut = new DateTimePartsEncoding();

            // When
            EncodeResult result = sut.encode(dtype, data, EncodeTestHelper.testCtx());
            MemorySegment[] bufs = result.buffers().toArray(MemorySegment[]::new);
            DecodeContext ctx = new DecodeContext(
                    toArrayNode(result.rootNode()), dtype, 1, bufs, registry(), Arena.global());
            GenericArray decoded = (GenericArray) sut.decode(ctx);

            // Then
            LongArray days = (LongArray) decoded.child(0);
            LongArray seconds = (LongArray) decoded.child(1);
            LongArray subseconds = (LongArray) decoded.child(2);
            assertThat(days.getLong(0)).isZero();
            assertThat(seconds.getLong(0)).isZero();
            assertThat(subseconds.getLong(0)).isZero();
        }

        @Test
        void encode_metadata_ptypes_areI64() throws Exception {
            // Given — DateTimeParts always encodes days/seconds/subseconds as I64 (ordinal=7)
            // if any tag drifts, the corresponding ptype reads as 0 (U8) which is proto3 default
            long[] timestamps = {0L, 86_400_000L};
            DateTimePartsData data = new DateTimePartsData(timestamps, false);
            DateTimePartsEncoding sut = new DateTimePartsEncoding();

            // When
            EncodeResult result = sut.encode(EXT_TIMESTAMP_MS, data, EncodeTestHelper.testCtx());
            EncodingProtos.DateTimePartsMetadata meta =
                    EncodingProtos.DateTimePartsMetadata.parseFrom(result.rootNode().metadata().duplicate());

            // Then
            assertThat(meta.getDaysPtypeValue()).isEqualTo(7);       // I64
            assertThat(meta.getSecondsPtypeValue()).isEqualTo(7);    // I64
            assertThat(meta.getSubsecondsPtypeValue()).isEqualTo(7); // I64
        }
    }
}
