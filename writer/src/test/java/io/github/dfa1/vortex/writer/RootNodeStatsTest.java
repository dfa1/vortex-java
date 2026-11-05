package io.github.dfa1.vortex.writer;

import io.github.dfa1.vortex.core.model.DTypes;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.proto.ProtoScalarValue;
import io.github.dfa1.vortex.writer.encode.NodeStats;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class RootNodeStatsTest {

    private static final ProtoScalarValue MIN = ProtoScalarValue.ofInt64Value(3L);
    private static final ProtoScalarValue MAX = ProtoScalarValue.ofInt64Value(90L);

    @ParameterizedTest
    @ValueSource(strings = {"vortex.primitive", "fastlanes.bitpacked"})
    void integerRoot_thatPassesStatsOn_keepsBoundsFlagCountAndSize(String root) {
        // Given / When
        NodeStats result = RootNodeStats.of(DTypes.I32, EncodingId.parse(root), MIN, MAX, 1_000, 0);

        // Then
        assertThat(result).isEqualTo(new NodeStats(MIN, MAX, false, null, null, 0L, 4_000L, null));
    }

    @Test
    void integerRoot_withNulls_hasNoConstantFlag_andCountsItsValidityBitmap() {
        // Given / When
        NodeStats result = RootNodeStats.of(DTypes.I64.asNullable(), EncodingId.VORTEX_PRIMITIVE, MIN, MAX, 1_000, 7);

        // Then — Rust does not run its constant check on an array with invalid rows
        assertThat(result.isConstant()).isNull();
        assertThat(result.nullCount()).isEqualTo(7);
        assertThat(result.uncompressedSize()).isEqualTo(1_000 * 8 + 125);
    }

    @Test
    void integerRoot_whoseBoundsAreEqual_isConstant() {
        // Given / When
        NodeStats result = RootNodeStats.of(DTypes.I32, EncodingId.VORTEX_PRIMITIVE, MIN, MIN, 10, 0);

        // Then
        assertThat(result.isConstant()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"fastlanes.for", "vortex.constant", "vortex.runend", "fastlanes.delta", "vortex.dict"})
    void integerRoot_thatDoesNotPassStatsOn_carriesNone(String root) {
        // Given / When
        NodeStats result = RootNodeStats.of(DTypes.I64, EncodingId.parse(root), MIN, MAX, 1_000, 0);

        // Then
        assertThat(result).isEqualTo(NodeStats.NONE);
    }

    @Test
    void floatRoot_keepsNoBounds_andAlpKeepsNothing() {
        // Given / When
        NodeStats primitive = RootNodeStats.of(DTypes.F64, EncodingId.VORTEX_PRIMITIVE, MIN, MAX, 100, 0);
        NodeStats alp = RootNodeStats.of(DTypes.F64, EncodingId.VORTEX_ALP, MIN, MAX, 100, 0);

        // Then — the compressor computes only the null count for a float
        assertThat(primitive).isEqualTo(new NodeStats(null, null, false, null, null, 0L, 800L, null));
        assertThat(alp).isEqualTo(NodeStats.NONE);
    }

    @Test
    void boolRoot_keepsTheFlagAndThePackedSize_butNoNullCount() {
        // Given / When
        NodeStats result = RootNodeStats.of(DTypes.BOOL, EncodingId.VORTEX_BOOL, null, null, 20_000, 0);

        // Then
        assertThat(result).isEqualTo(new NodeStats(null, null, false, null, null, null, 2_500L, null));
    }

    @Test
    void stringRoots_carryNone() {
        // Given / When
        NodeStats varbin = RootNodeStats.of(DTypes.UTF8, EncodingId.VORTEX_VARBIN, MIN, MAX, 100, 0);
        NodeStats fsst = RootNodeStats.of(DTypes.UTF8, EncodingId.VORTEX_FSST, MIN, MAX, 100, 0);

        // Then — Rust keeps no string bound in a flat segment's array stats
        assertThat(varbin).isEqualTo(NodeStats.NONE);
        assertThat(fsst).isEqualTo(NodeStats.NONE);
    }
}
