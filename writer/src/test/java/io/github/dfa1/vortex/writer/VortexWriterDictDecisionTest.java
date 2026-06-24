package io.github.dfa1.vortex.writer;

import io.github.dfa1.vortex.core.model.PType;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static io.github.dfa1.vortex.writer.VortexWriter.GLOBAL_DICT_MAX_CARDINALITY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/// Direct unit tests for the global-dictionary decision helpers in [VortexWriter]. These choices
/// (dict vs cascade fallback, and the code width) only affect *encoding*, not the values a reader
/// gets back — so a round-trip cannot pin their boundaries. Testing the pure predicates directly
/// fixes each cardinality / ratio edge.
class VortexWriterDictDecisionTest {

    // ── isDictCandidate (primitive) ──────────────────────────────────────────────

    static Stream<Arguments> dictCandidateCases() {
        return Stream.of(
                // exclusions: a U8/U16 code is no smaller than the value, and F16/F32 prefer ALP
                arguments("F32 excluded", PType.F32, new float[]{1f, 1f, 2f, 2f, 2f}, false),
                arguments("F16 excluded", PType.F16, new short[]{1, 1, 2, 2, 2}, false),
                arguments("I8 excluded", PType.I8, new byte[]{1, 2, 1, 2, 1}, false),
                arguments("U8 excluded", PType.U8, new byte[]{1, 2, 1, 2, 1}, false),
                arguments("I16 excluded", PType.I16, new short[]{1, 2, 1, 2, 1}, false),
                arguments("U16 excluded", PType.U16, new short[]{1, 2, 1, 2, 1}, false),
                // admitted carriers, 2 distinct over 5 rows (4 < 5, under the gate)
                arguments("I64 low cardinality", PType.I64, new long[]{1, 2, 1, 2, 1}, true),
                arguments("F64 low cardinality", PType.F64, new double[]{1, 2, 1, 2, 1}, true),
                arguments("empty", PType.I64, new long[0], false),
                arguments("single distinct value", PType.I64, new long[]{7, 7, 7, 7}, false),
                arguments("ratio exactly 50%", PType.I64, new long[]{1, 2, 1, 2}, false),
                arguments("ratio under 50%", PType.I64, new long[]{1, 2, 1, 2, 1}, true),
                arguments("cardinality at MAX", PType.I64, distinctThenRepeat(GLOBAL_DICT_MAX_CARDINALITY), true),
                arguments("cardinality over MAX", PType.I64, distinctThenRepeat(GLOBAL_DICT_MAX_CARDINALITY + 1), false));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dictCandidateCases")
    void isDictCandidate(String name, PType ptype, Object data, boolean expected) {
        // Given — a column of `ptype` with the case's data

        // When
        boolean result = VortexWriter.isDictCandidate(ptype, data);

        // Then
        assertThat(result).isEqualTo(expected);
    }

    // ── isUtf8DictCandidate ──────────────────────────────────────────────────────

    static Stream<Arguments> utf8DictCandidateCases() {
        return Stream.of(
                arguments("empty", new String[0], false),
                arguments("ratio exactly 50%", new String[]{"a", "b", "a", "b"}, false),
                arguments("ratio under 50%", new String[]{"a", "b", "a", "b", "a"}, true),
                arguments("cardinality at MAX", distinctStrings(GLOBAL_DICT_MAX_CARDINALITY), true),
                arguments("cardinality over MAX", distinctStrings(GLOBAL_DICT_MAX_CARDINALITY + 1), false));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("utf8DictCandidateCases")
    void isUtf8DictCandidate(String name, String[] data, boolean expected) {
        // Given — a string column with the case's data

        // When
        boolean result = VortexWriter.isUtf8DictCandidate(data);

        // Then
        assertThat(result).isEqualTo(expected);
    }

    // ── codePTypeForSize ─────────────────────────────────────────────────────────

    static Stream<Arguments> codePTypeCases() {
        return Stream.of(
                arguments(1, PType.U8),
                arguments(256, PType.U8),      // upper edge of U8
                arguments(257, PType.U16),     // first U16
                arguments(65_536, PType.U16),  // upper edge of U16
                arguments(65_537, PType.U32)); // first U32
    }

    @ParameterizedTest
    @MethodSource("codePTypeCases")
    void codePTypeForSize_picksNarrowestUnsignedCarrier(int dictSize, PType expected) {
        // Given — a dictionary of `dictSize` distinct values

        // When
        PType result = VortexWriter.codePTypeForSize(dictSize);

        // Then
        assertThat(result).isEqualTo(expected);
    }

    // ── primitiveArrayLen / readPrimitiveElement ─────────────────────────────────

    static Stream<Arguments> primitiveArrayLenCases() {
        // Only dict-admitted ptypes reach primitiveArrayLen: I32/U32 (int[]), I64/U64 (long[]), F64.
        return Stream.of(
                arguments(new long[]{1, 2, 3}, PType.I64, 3),
                arguments(new int[]{1, 2}, PType.I32, 2),
                arguments(new double[]{1.0, 2.0, 3.0, 4.0}, PType.F64, 4));
    }

    @ParameterizedTest
    @MethodSource("primitiveArrayLenCases")
    void primitiveArrayLen_returnsActualLength(Object data, PType ptype, int expected) {
        // Given — a typed primitive array of `ptype`

        // When
        int result = VortexWriter.primitiveArrayLen(data, ptype);

        // Then
        assertThat(result).isEqualTo(expected);
    }

    static Stream<Arguments> readPrimitiveElementCases() {
        // Only dict-admitted ptypes reach readPrimitiveElement: I32/U32, I64/U64, F64.
        return Stream.of(
                arguments(new long[]{7, 8, 9}, PType.I64, 1, 8L),
                arguments(new int[]{7, 8, 9}, PType.I32, 2, 9),
                arguments(new double[]{7.0, 8.0, 9.0}, PType.F64, 0, 7.0));
    }

    @ParameterizedTest
    @MethodSource("readPrimitiveElementCases")
    void readPrimitiveElement_returnsElementAtIndex(Object data, PType ptype, int index, Object expected) {
        // Given — a typed primitive array of `ptype`

        // When
        Object result = VortexWriter.readPrimitiveElement(data, ptype, index);

        // Then
        assertThat(result).isEqualTo(expected);
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

    /// Builds a String[] with exactly `distinct` distinct values, each repeated 4×.
    private static String[] distinctStrings(int distinct) {
        String[] a = new String[distinct * 4];
        for (int i = 0; i < a.length; i++) {
            a[i] = "s" + (i % distinct);
        }
        return a;
    }
}
