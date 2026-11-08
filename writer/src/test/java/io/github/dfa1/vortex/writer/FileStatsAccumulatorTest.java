package io.github.dfa1.vortex.writer;

import io.github.dfa1.vortex.core.model.DTypes;
import io.github.dfa1.vortex.core.proto.ProtoScalarValue;
import io.github.dfa1.vortex.writer.encode.NodeStats;
import io.github.dfa1.vortex.writer.encode.NullableData;
import org.junit.jupiter.api.Test;

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
}
