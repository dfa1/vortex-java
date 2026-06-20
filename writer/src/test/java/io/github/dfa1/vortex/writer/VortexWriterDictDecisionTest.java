package io.github.dfa1.vortex.writer;

import io.github.dfa1.vortex.core.PType;
import org.junit.jupiter.api.Test;

import static io.github.dfa1.vortex.writer.VortexWriter.GLOBAL_DICT_MAX_CARDINALITY;
import static org.assertj.core.api.Assertions.assertThat;

/// Direct unit tests for the global-dictionary decision helpers in [VortexWriter]. These choices
/// (dict vs cascade fallback, and the code width) only affect *encoding*, not the values a reader
/// gets back — so a round-trip cannot pin their boundaries. Testing the pure predicates directly
/// fixes each cardinality / ratio edge.
class VortexWriterDictDecisionTest {

    // ── isDictCandidate (primitive) ──────────────────────────────────────────────

    @Test
    void isDictCandidate_excludesF16AndF32() {
        // Given low-cardinality float data that would otherwise qualify
        // When / Then — F16/F32 are excluded outright (ALP wins there)
        assertThat(VortexWriter.isDictCandidate(PType.F32, new float[]{1f, 1f, 2f, 2f, 2f})).isFalse();
        assertThat(VortexWriter.isDictCandidate(PType.F16, new short[]{1, 1, 2, 2, 2})).isFalse();
    }

    @Test
    void isDictCandidate_admitsLowCardinalityI64AndF64() {
        // Given — 2 distinct over 5 rows: 2*2 = 4 < 5, under the 50%-unique gate
        assertThat(VortexWriter.isDictCandidate(PType.I64, new long[]{1, 2, 1, 2, 1})).isTrue();
        assertThat(VortexWriter.isDictCandidate(PType.F64, new double[]{1, 2, 1, 2, 1})).isTrue();
    }

    @Test
    void isDictCandidate_emptyArray_isFalse() {
        assertThat(VortexWriter.isDictCandidate(PType.I64, new long[0])).isFalse();
    }

    @Test
    void isDictCandidate_singleDistinctValue_isFalse() {
        // One distinct value fits vortex.constant better than a dict
        assertThat(VortexWriter.isDictCandidate(PType.I64, new long[]{7, 7, 7, 7})).isFalse();
    }

    @Test
    void isDictCandidate_ratioGate_isExclusive() {
        // 2 distinct over 4 rows: 2*2 == 4, NOT < 4 → rejected (exactly 50% unique)
        assertThat(VortexWriter.isDictCandidate(PType.I64, new long[]{1, 2, 1, 2})).isFalse();
        // 2 distinct over 5 rows: 4 < 5 → admitted
        assertThat(VortexWriter.isDictCandidate(PType.I64, new long[]{1, 2, 1, 2, 1})).isTrue();
    }

    @Test
    void isDictCandidate_cardinalityAtAndOverMax() {
        // At MAX distinct values (well under 50% unique) → still a candidate
        assertThat(VortexWriter.isDictCandidate(PType.I64, distinctThenRepeat(GLOBAL_DICT_MAX_CARDINALITY))).isTrue();
        // One over MAX → rejected by the cardinality guard
        assertThat(VortexWriter.isDictCandidate(PType.I64, distinctThenRepeat(GLOBAL_DICT_MAX_CARDINALITY + 1))).isFalse();
    }

    // ── isUtf8DictCandidate ──────────────────────────────────────────────────────

    @Test
    void isUtf8DictCandidate_emptyArray_isFalse() {
        assertThat(VortexWriter.isUtf8DictCandidate(new String[0])).isFalse();
    }

    @Test
    void isUtf8DictCandidate_ratioGate_isExclusive() {
        // 2 distinct over 4: 4 == 4, not < → rejected
        assertThat(VortexWriter.isUtf8DictCandidate(new String[]{"a", "b", "a", "b"})).isFalse();
        // 2 distinct over 5: 4 < 5 → admitted
        assertThat(VortexWriter.isUtf8DictCandidate(new String[]{"a", "b", "a", "b", "a"})).isTrue();
    }

    @Test
    void isUtf8DictCandidate_overMaxCardinality_isFalse() {
        String[] data = new String[(GLOBAL_DICT_MAX_CARDINALITY + 1) * 4];
        for (int i = 0; i < data.length; i++) {
            data[i] = "s" + (i % (GLOBAL_DICT_MAX_CARDINALITY + 1));
        }
        assertThat(VortexWriter.isUtf8DictCandidate(data)).isFalse();
    }

    // ── codePTypeForSize ─────────────────────────────────────────────────────────

    @Test
    void codePTypeForSize_picksNarrowestUnsignedCarrier() {
        assertThat(VortexWriter.codePTypeForSize(1)).isEqualTo(PType.U8);
        assertThat(VortexWriter.codePTypeForSize(256)).isEqualTo(PType.U8);     // upper edge of U8
        assertThat(VortexWriter.codePTypeForSize(257)).isEqualTo(PType.U16);    // first U16
        assertThat(VortexWriter.codePTypeForSize(65_536)).isEqualTo(PType.U16); // upper edge of U16
        assertThat(VortexWriter.codePTypeForSize(65_537)).isEqualTo(PType.U32); // first U32
    }

    // ── primitiveArrayLen / readPrimitiveElement ─────────────────────────────────

    @Test
    void primitiveArrayLen_returnsActualLength() {
        assertThat(VortexWriter.primitiveArrayLen(new long[]{1, 2, 3}, PType.I64)).isEqualTo(3);
        assertThat(VortexWriter.primitiveArrayLen(new int[]{1, 2}, PType.I32)).isEqualTo(2);
    }

    @Test
    void readPrimitiveElement_returnsElementAtIndex() {
        assertThat(VortexWriter.readPrimitiveElement(new long[]{7, 8, 9}, PType.I64, 1)).isEqualTo(8L);
        assertThat(VortexWriter.readPrimitiveElement(new int[]{7, 8, 9}, PType.I32, 2)).isEqualTo(9);
    }

    /// Builds a long[] with exactly `distinct` distinct values, each repeated 4× (so the array is
    /// comfortably under the 50%-unique ratio gate and only the cardinality guard is in play).
    private static long[] distinctThenRepeat(int distinct) {
        long[] a = new long[distinct * 4];
        for (int i = 0; i < a.length; i++) {
            a[i] = i % distinct;
        }
        return a;
    }
}
