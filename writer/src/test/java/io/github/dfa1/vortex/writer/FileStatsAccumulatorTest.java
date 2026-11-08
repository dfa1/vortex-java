package io.github.dfa1.vortex.writer;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.DTypes;
import io.github.dfa1.vortex.core.proto.ProtoScalarValue;
import io.github.dfa1.vortex.writer.encode.NodeStats;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;

import static org.assertj.core.api.Assertions.assertThat;

/// The fold rules of [FileStatsAccumulator] that `FileStatisticsInteropIntegrationTest` cannot reach
/// with a small file: sums that overflow only past millions of rows, and columns without any batch.
class FileStatsAccumulatorTest {

    @Test
    void noBatch_carriesNoStat() {
        // Given — Rust drops every per-batch stat of a column it never saw a batch of: even the null
        // count and the sum, which a column with rows always has
        FileStatsAccumulator sut = new FileStatsAccumulator(DTypes.I64);
        sut.add(new long[0], 0);

        // When
        NodeStats result = sut.stats();

        // Then
        assertThat(result).isEqualTo(NodeStats.NONE);
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
    void unsignedSumOverflowAcrossBatches_dropsSum() {
        // Given — each batch fits u64, their total does not: the fold is checked too
        FileStatsAccumulator sut = new FileStatsAccumulator(DTypes.U64);
        sut.add(new long[]{-2L}, 1);
        sut.add(new long[]{2L}, 1);

        // When
        NodeStats result = sut.stats();

        // Then — min and max are unaffected
        assertThat(result.sum()).isNull();
        assertThat(result.max()).isEqualTo(ProtoScalarValue.ofUint64Value(-2L));
    }

    @Test
    void decimalSumPastMaxPrecision_dropsSum() {
        // Given — at Rust's maximum precision a sum cannot widen: two values near 10^76 overflow it
        BigDecimal big = new BigDecimal(BigInteger.TEN.pow(76).subtract(BigInteger.ONE));
        FileStatsAccumulator sut = new FileStatsAccumulator(DType.Decimal.of(76, 0, false));
        sut.add(new BigDecimal[]{big, big}, 2);

        // When
        NodeStats result = sut.stats();

        // Then
        assertThat(result.sum()).isNull();
        assertThat(result.min()).isNotNull();
    }

    @Test
    void nullDtype_countsEveryRowNull() {
        // Given
        FileStatsAccumulator sut = new FileStatsAccumulator(DTypes.NULL);
        sut.add(new Object(), 4);

        // When
        NodeStats result = sut.stats();

        // Then
        assertThat(result).isEqualTo(new NodeStats(null, null, null, null, null, 4L, null, null, null));
    }
}
