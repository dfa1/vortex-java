package io.github.dfa1.vortex.writer;

import io.github.dfa1.vortex.core.model.ArrayStats;
import io.github.dfa1.vortex.core.model.DTypes;
import io.github.dfa1.vortex.core.proto.ProtoListValue;
import io.github.dfa1.vortex.core.proto.ProtoScalarValue;
import io.github.dfa1.vortex.writer.encode.NodeStats;
import io.github.dfa1.vortex.writer.encode.NullableData;
import io.github.dfa1.vortex.writer.encode.UuidExtensionEncoder;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/// The accumulation rules of [FileStatsAccumulator], each on its own; `FileStatisticsInteropIntegrationTest`
/// checks them all together against vortex-jni.
class FileStatsAccumulatorTest {

    @Test
    void noBatch_zeroNullCountAndSize() {
        // Given — Rust's accumulators start at zero and keep it without a batch
        FileStatsAccumulator sut = new FileStatsAccumulator(DTypes.I64);
        sut.add(new long[0], 0);

        // When
        NodeStats result = sut.stats();

        // Then
        assertThat(result).isEqualTo(new NodeStats(null, null, null, null, null, 0L, 0L, null));
    }

    @Test
    void variant_carriesNoStat() {
        // Given — Rust's supports_file_stats excludes variant columns entirely
        FileStatsAccumulator sut = new FileStatsAccumulator(DTypes.VARIANT);
        sut.add(new Object(), 3);

        // When
        NodeStats result = sut.stats();

        // Then
        assertThat(result).isEqualTo(NodeStats.NONE);
    }

    @Test
    void utf8AllNullBatch_keepsTheOtherBatchesMax() {
        // Given — Rust's BoundedMax skips a batch without a valid value
        FileStatsAccumulator sut = new FileStatsAccumulator(DTypes.UTF8.asNullable());
        sut.add(new NullableData(new String[]{"b", "a"}, new boolean[]{true, true}), 2);
        sut.add(new NullableData(new String[]{null}, new boolean[]{false}), 1);

        // When
        NodeStats result = sut.stats();

        // Then
        assertThat(result.max()).isEqualTo(ProtoScalarValue.ofStringValue("b"));
        assertThat(result.nullCount()).isEqualTo(1L);
    }

    @Test
    void utf8_boundsAreInexact() {
        // Given — a short string is never cut, yet a bounded min/max only approximates Min/Max
        FileStatsAccumulator sut = new FileStatsAccumulator(DTypes.UTF8);
        sut.add(new String[]{"a"}, 1);

        // When
        boolean result = sut.boundsExact();

        // Then
        assertThat(result).isFalse();
    }

    @Test
    void i64_boundsAreExact() {
        // Given
        FileStatsAccumulator sut = new FileStatsAccumulator(DTypes.I64);
        sut.add(new long[]{1, 2}, 2);

        // When
        boolean result = sut.boundsExact();

        // Then
        assertThat(result).isTrue();
    }

    @Test
    void integers_haveNoSum() {
        // Given — Rust 0.91's default aggregates have no sum
        FileStatsAccumulator sut = new FileStatsAccumulator(DTypes.I64);
        sut.add(new long[]{1, 2}, 2);

        // When
        NodeStats result = sut.stats();

        // Then
        assertThat(result.sum()).isNull();
    }

    @Test
    void uncompressedSize_addsUpTheBatches() {
        // Given — 8 bytes a row; the first batch's one null adds a bitmap, the second is all valid
        FileStatsAccumulator sut = new FileStatsAccumulator(DTypes.I64.asNullable());
        sut.add(new NullableData(new long[]{1, 0, 3}, new boolean[]{true, false, true}), 3);
        sut.add(new NullableData(new long[]{4, 5}, new boolean[]{true, true}), 2);

        // When
        NodeStats result = sut.stats();

        // Then
        assertThat(result.uncompressedSize()).isEqualTo(3 * 8 + 1 + 2 * 8L);
    }

    @Test
    void nullDtype_countsEveryRowNull() {
        // Given
        FileStatsAccumulator sut = new FileStatsAccumulator(DTypes.NULL);
        sut.add(new Object(), 4);

        // When
        NodeStats result = sut.stats();

        // Then
        assertThat(result).isEqualTo(new NodeStats(null, null, null, null, null, 4L, 0L, null));
    }

    @Test
    void uuid_minAndMaxInUnsignedByteOrder() {
        // Given — 0x80… sorts above 0x7f… as unsigned bytes, though it is the smaller signed long,
        // which is how UUID.compareTo would order them
        UUID high = UUID.fromString("80000000-0000-0000-0000-000000000000");
        UUID low = UUID.fromString("7fffffff-ffff-ffff-ffff-ffffffffffff");
        FileStatsAccumulator sut = new FileStatsAccumulator(UuidExtensionEncoder.INSTANCE.dtype(false));
        sut.add(UuidExtensionEncoder.INSTANCE.encodeAll(UuidExtensionEncoder.INSTANCE.dtype(false), List.of(high, low)), 2);

        // When
        ArrayStats result = sut.arrayStats();

        // Then
        assertThat(result.min()).isEqualTo(low);
        assertThat(result.max()).isEqualTo(high);
        assertThat(result.minExact()).isTrue();
        assertThat(result.maxExact()).isTrue();
    }

    @Test
    void uuid_boundIsAListOfSixteenU8Scalars() {
        // Given
        UUID value = UUID.fromString("00010203-0405-0607-0809-0a0b0c0d0eff");
        FileStatsAccumulator sut = new FileStatsAccumulator(UuidExtensionEncoder.INSTANCE.dtype(false));
        sut.add(UuidExtensionEncoder.INSTANCE.encodeAll(UuidExtensionEncoder.INSTANCE.dtype(false), List.of(value)), 1);

        // When
        NodeStats result = sut.stats();

        // Then — Rust's FixedSizeList(U8, 16) scalar: one u8 per byte, big-endian as stored
        List<ProtoScalarValue> expected = new ArrayList<>();
        for (int b : new int[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 255}) {
            expected.add(ProtoScalarValue.ofUint64Value((long) b));
        }
        assertThat(result.min()).isEqualTo(ProtoScalarValue.ofListValue(new ProtoListValue(expected)));
        assertThat(result.max()).isEqualTo(result.min());
    }

    @Test
    void uuid_nullsCountAndKeepTheOtherBatchesBounds() {
        // Given — a nullable column: an all-null batch adds no bound
        UUID a = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID b = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID c = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
        var dtype = UuidExtensionEncoder.INSTANCE.dtype(true);
        FileStatsAccumulator sut = new FileStatsAccumulator(dtype);
        sut.add(UuidExtensionEncoder.INSTANCE.encodeAll(dtype, Arrays.asList(a, null)), 2);
        sut.add(UuidExtensionEncoder.INSTANCE.encodeAll(dtype, Arrays.asList(null, null)), 2);
        sut.add(UuidExtensionEncoder.INSTANCE.encodeAll(dtype, Arrays.asList(c, b)), 2);

        // When
        ArrayStats result = sut.arrayStats();

        // Then
        assertThat(result.min()).isEqualTo(b);
        assertThat(result.max()).isEqualTo(c);
        assertThat(result.nullCount()).isEqualTo(3L);
    }

    @Test
    void uuidAllNull_hasNoBound() {
        // Given
        var dtype = UuidExtensionEncoder.INSTANCE.dtype(true);
        FileStatsAccumulator sut = new FileStatsAccumulator(dtype);
        sut.add(UuidExtensionEncoder.INSTANCE.encodeAll(dtype, Arrays.asList(null, null, null)), 3);

        // When
        NodeStats result = sut.stats();

        // Then
        assertThat(result.min()).isNull();
        assertThat(result.max()).isNull();
        assertThat(result.nullCount()).isEqualTo(3L);
    }
}
