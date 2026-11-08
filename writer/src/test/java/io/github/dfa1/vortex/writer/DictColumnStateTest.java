package io.github.dfa1.vortex.writer;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.proto.ProtoScalarValue;
import io.github.dfa1.vortex.writer.encode.NullableData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/// Direct unit tests for the global-dictionary decision helpers in [DictColumnState]. These choices
/// (dict vs cascade fallback, and the code width) only affect *encoding*, not the values a reader
/// gets back — so a round-trip cannot pin their boundaries. Testing the pure predicates directly
/// fixes each cardinality / ratio edge.
class DictColumnStateTest {

    // ── every primitive ptype ──────────────────────────────────────────────────────

    @ParameterizedTest(name = "{0}")
    @EnumSource(PType.class)
    void ingestThenReconstruct_everyPtype_roundTripsInItsCarrier(PType ptype) {
        // Given — a nullable chunk of 3 distinct values and a null in the ptype's own carrier. Rust's
        // dict layout admits every primitive (#651: a nullable I16 was never probed), so each carrier
        // must survive ingest, the dictionary entries, and a demotion's reconstruction
        Object values = switch (ptype) {
            case I8, U8 -> new byte[]{7, 8, 0, 7, 9};
            case I16, U16, F16 -> new short[]{7, 8, 0, 7, 9};
            case I32, U32 -> new int[]{7, 8, 0, 7, 9};
            case I64, U64 -> new long[]{7, 8, 0, 7, 9};
            case F32 -> new float[]{7, 8, 0, 7, 9};
            case F64 -> new double[]{7, 8, 0, 7, 9};
        };
        boolean[] validity = {true, true, false, true, true};
        var sut = new DictColumnState(new DType.Primitive(ptype, true), true);

        // When
        int ingested = sut.ingestDictChunk(new NullableData(values, validity));
        Object result = sut.reconstructChunk(sut.buildInverseMap(), 0);

        // Then — every row ingested, three values plus the null entry, the chunk rebuilt exactly
        assertThat(ingested).isEqualTo(5);
        assertThat(java.lang.reflect.Array.getLength(sut.uniques())).isEqualTo(4);
        assertThat(result).isInstanceOf(NullableData.class);
        assertThat(((NullableData) result).values()).isEqualTo(values);
        assertThat(((NullableData) result).validity()).isEqualTo(validity);
    }

    // ── isVarBinDictCandidate ──────────────────────────────────────────────────────

    static Stream<Arguments> utf8DictCandidateCases() {
        return Stream.of(
                arguments("empty", new String[0], false),
                arguments("ratio exactly 50%", new String[]{"a", "b", "a", "b"}, false),
                arguments("ratio under 50%", new String[]{"a", "b", "a", "b", "a"}, true),
                // no cardinality cap as for numbers: Rust skips a sample only above half distinct, and a
                // column that outgrows one dictionary starts another
                arguments("Utf8 admitted above numeric cap", repeated(2_049), true),
                arguments("cardinality of a full dictionary", repeated(70_000), true));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("utf8DictCandidateCases")
    void isVarBinDictCandidate(String name, String[] data, boolean expected) {
        // Given — a string column with the case's data

        // When
        boolean result = DictColumnState.isVarBinDictCandidate(data);

        // Then
        assertThat(result).isEqualTo(expected);
    }

    // ── isVarBinDictCandidate (nullable) ───────────────────────────────────────────

    static Stream<Arguments> nullableUtf8DictCandidateCases() {
        // Nullable Utf8 keeps real null array elements at invalid positions (ChunkImpl.adaptUtf8),
        // so a null string is skipped whether flagged by the validity array or by being null itself.
        return Stream.of(
                // 2 distinct valid strings over 5 total rows (2*2 < 5) → candidate; the null is skipped.
                arguments("nulls excluded from cardinality",
                        new String[]{"a", "b", null, "a", "b"},
                        new boolean[]{true, true, false, true, true}, true),
                arguments("all-null not a candidate",
                        new String[]{null, null, null}, new boolean[]{false, false, false}, false),
                arguments("null-heavy but low cardinality still passes",
                        new String[]{"a", null, null, "b", null, "a", null, "b"},
                        new boolean[]{true, false, false, true, false, true, false, true}, true),
                arguments("null validity delegates to all-valid path",
                        new String[]{"a", "b", "a", "b", "a"}, null, true));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("nullableUtf8DictCandidateCases")
    void isVarBinDictCandidate_nullable(String name, String[] data, boolean[] validity, boolean expected) {
        // Given — a string column with the case's values and validity

        // When
        boolean result = DictColumnState.isVarBinDictCandidate(data, validity);

        // Then
        assertThat(result).isEqualTo(expected);
    }

    @Test
    void isVarBinDictCandidate_binaryComparesContentNotIdentity() {
        // Given — 5 rows of 2 distinct byte strings, each row its own byte[] instance. Keyed by
        // array identity (byte[] has no content equals) every row would look distinct and the
        // column would never qualify.
        byte[][] data = new byte[5][];
        for (int i = 0; i < data.length; i++) {
            data[i] = new byte[]{(byte) (i % 2), 42};
        }

        // When
        boolean result = DictColumnState.isVarBinDictCandidate(data);

        // Then
        assertThat(result).isTrue();
    }

    @Test
    void ingestDictChunk_binary_dedupsByContentAndReconstructs() {
        // Given — a nullable Binary column whose equal values arrive as distinct byte[] instances
        var sut = new DictColumnState(new DType.Binary(true), true);
        byte[][] values = {{1, 2}, {3}, null, {1, 2}, {3}};
        boolean[] validity = {true, true, false, true, true};

        // When
        int admitted = sut.ingestVarBinChunk(new NullableData(values, validity));
        Object result = sut.reconstructChunk(sut.buildInverseMap(), 0);

        // Then — two values plus the null entry, and demotion rebuilds the original rows (null kept null)
        assertThat(admitted).isEqualTo(5);
        assertThat(sut.cardinality()).isEqualTo(3);
        assertThat((byte[][]) sut.uniques()).isDeepEqualTo(new byte[][]{{1, 2}, {3}, null});
        assertThat(result).isInstanceOfSatisfying(NullableData.class, nd -> {
            assertThat((byte[][]) nd.values()).isDeepEqualTo(values);
            assertThat(nd.validity()).containsExactly(validity);
        });
    }

    // ── code assignment ──────────────────────────────────────────────────────────

    @Test
    void ingestDictChunk_assignsCodesFirstSeen_nullIncluded() {
        // Given — Rust's dict builder codes values in first-seen order and gives null the next code
        // at its first appearance. The dominant 0.0 arrives after a rare value: a frequency ranking
        // (the old remap) would make it code 0 and push null to the end, so the codes reaching the
        // cascade differed from Rust's.
        var sut = new DictColumnState(new DType.Primitive(PType.F64, true), true);
        double[] values = {1.75, 0.0, 0.0, 0.0, 0.0, -1.75};
        boolean[] validity = {true, true, false, true, false, true};

        // When
        int admitted = sut.ingestDictChunk(new NullableData(values, validity));
        short[] result = sut.chunkCodes(0);

        // Then
        assertThat(admitted).isEqualTo(values.length);
        assertThat(result).containsExactly((short) 0, (short) 1, (short) 2, (short) 1, (short) 2, (short) 3);
        assertThat(sut.nullCode()).isEqualTo(2);
        assertThat((double[]) sut.uniques()).containsExactly(1.75, 0.0, 0.0, -1.75);
    }

    @Test
    void ingestDictChunk_stopsAtTheNullEntryThatNoLongerFits() {
        // Given — a dictionary filled to u16::MAX entries; the next chunk's first row is a null
        var sut = new DictColumnState(new DType.Primitive(PType.I64, true), true);
        long[] full = new long[DictColumnState.MAX_ENTRIES];
        for (int i = 0; i < full.length; i++) {
            full[i] = i;
        }
        sut.ingestDictChunk(full);
        long[] next = {0, -1};
        boolean[] validity = {false, true};

        // When
        int result = sut.ingestDictChunk(new NullableData(next, validity));

        // Then — Rust's encode_null: no room for a null entry, so the dictionary is closed
        assertThat(result).isZero();
        assertThat(sut.nullCode()).isEqualTo(-1);
        assertThat(sut.cardinality()).isEqualTo(full.length);
    }

    @Test
    void ingestDictChunk_stopsAtTheValueThatNoLongerFitsAndKeepsThePrefixStats() {
        // Given — a dictionary filled to u16::MAX entries
        var sut = new DictColumnState(new DType.Primitive(PType.I64, false), true);
        long[] full = new long[DictColumnState.MAX_ENTRIES];
        for (int i = 0; i < full.length; i++) {
            full[i] = i;
        }
        sut.ingestDictChunk(full);

        // When — known values still fit; the first new one ends the prefix
        int result = sut.ingestDictChunk(new long[]{5, 6, -1, 7});

        // Then — and the prefix's own bounds are recorded, not the whole chunk's
        assertThat(result).isEqualTo(2);
        assertThat(sut.chunkRowCounts()).containsExactly((long) full.length, 2L);
        assertThat(sut.chunkStatsMin().getLast()).isEqualTo(ProtoScalarValue.ofInt64Value(5L));
        assertThat(sut.chunkStatsMax().getLast()).isEqualTo(ProtoScalarValue.ofInt64Value(6L));
    }

    @ParameterizedTest
    @EnumSource(value = PType.class, names = {"I8", "U8"})
    void ingestDictChunk_aNullableEightBitDictionaryHoldsEveryValueAndANull(PType ptype) {
        // Given — every byte value, then a null: 257 entries, which U8 codes could not name, so
        // the codes are U16 (Rust 0.91 counts the null entry when it sizes them)
        var sut = new DictColumnState(new DType.Primitive(ptype, true), true);
        byte[] all = new byte[257];
        boolean[] validity = new boolean[257];
        for (int i = 0; i < 256; i++) {
            all[i] = (byte) i;
            validity[i] = true;
        }

        // When
        int result = sut.ingestDictChunk(new NullableData(all, validity));

        // Then
        assertThat(sut.codesPType()).isEqualTo(PType.U16);
        assertThat(result).isEqualTo(257);
        assertThat(sut.cardinality()).isEqualTo(257);
    }

    @ParameterizedTest
    @EnumSource(value = PType.class, names = {"I8", "U8"})
    void codesPType_isU8OverNonNullableEightBitValues(PType ptype) {
        // Given
        var sut = new DictColumnState(new DType.Primitive(ptype, false), true);

        // When
        PType result = sut.codesPType();

        // Then
        assertThat(result).isEqualTo(PType.U8);
    }

    @ParameterizedTest
    @EnumSource(value = PType.class, names = {"I16", "U16", "I32", "F64"})
    void codesPType_isU16AboveEightBits(PType ptype) {
        // Given
        var sut = new DictColumnState(new DType.Primitive(ptype, false), true);

        // When
        PType result = sut.codesPType();

        // Then
        assertThat(result).isEqualTo(PType.U16);
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
        int result = DictColumnState.primitiveArrayLen(data, ptype);

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
        Object result = DictColumnState.readPrimitiveElement(data, ptype, index);

        // Then
        assertThat(result).isEqualTo(expected);
    }

    // ── ingestDictChunk (string min/max per chunk) ───────────────────────────────

    /// Min/max are taken once per distinct code per chunk, not per row. This pins that each chunk
    /// still reports its own extremes: chunk 3 reuses codes chunk 1 already saw (a stale "seen"
    /// mark would drop them), chunk 2 holds none of them, and the emoji sorts above U+FF21 only in
    /// UTF-8 order (String#compareTo says the opposite).
    @Test
    void ingestDictChunkReportsPerChunkStringMinMax() {
        // Given
        DictColumnState sut = new DictColumnState(new DType.Utf8(true), true);
        String emoji = new String(Character.toChars(0x1F600));

        // When
        sut.ingestVarBinChunk(new String[]{"b", "a", "b", "Ａ", emoji, "a"});
        sut.ingestVarBinChunk(new String[]{"c", null, "c", "d"});
        sut.ingestVarBinChunk(new String[]{"b", "a", "b"});

        // Then
        assertThat(decodeStrings(sut.chunkStatsMin())).containsExactly("a", "c", "a");
        assertThat(decodeStrings(sut.chunkStatsMax())).containsExactly(emoji, "d", "b");
    }

    private static List<String> decodeStrings(List<ProtoScalarValue> scalars) {
        return scalars.stream().map(ProtoScalarValue::string_value).toList();
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

    // A column of `distinct` values each repeated three times: under half distinct, whatever its size.
    private static String[] repeated(int distinct) {
        String[] out = new String[distinct * 3];
        for (int i = 0; i < out.length; i++) {
            out[i] = "value-" + (i / 3);
        }
        return out;
    }

    // ── ingestVarBinChunk (Rust's dict constraints) ──────────────────────────────

    @Test
    void ingestVarBinChunk_stopsAtTheEntryThatNoLongerFits() {
        // Given — a dictionary filled to u16::MAX entries
        DictColumnState sut = new DictColumnState(new DType.Utf8(false), true);
        String[] full = new String[DictColumnState.MAX_ENTRIES];
        for (int i = 0; i < full.length; i++) {
            full[i] = "v" + i;
        }
        sut.ingestVarBinChunk(full);

        // When — known values still fit, the first new one ends the prefix
        int consumed = sut.ingestVarBinChunk(new String[]{"v0", "v1", "new", "v2"});

        // Then
        assertThat(consumed).isEqualTo(2);
        assertThat(sut.cardinality()).isEqualTo(DictColumnState.MAX_ENTRIES);
        assertThat(sut.chunkRowCounts()).containsExactly((long) full.length, 2L);
    }

    @Test
    void ingestVarBinChunk_countsViewsAndOutOfLineBytesAgainstTheByteLimit() {
        // Given — 20-byte values cost a 16-byte view plus themselves: 36 bytes, so 29127 fit in 1 MiB
        DictColumnState sut = new DictColumnState(new DType.Utf8(false), true);
        String[] values = new String[40_000];
        for (int i = 0; i < values.length; i++) {
            values[i] = String.format("%020d", i);
        }

        // When
        int consumed = sut.ingestVarBinChunk(values);

        // Then
        assertThat(consumed).isEqualTo((int) (DictColumnState.MAX_DICT_BYTES / 36));
    }

    @Test
    void ingestVarBinChunk_admitsTheFirstEntryWhateverItsSize() {
        // Given — one value past the byte limit on its own
        DictColumnState sut = new DictColumnState(new DType.Utf8(false), true);
        String huge = "x".repeat((int) DictColumnState.MAX_DICT_BYTES + 1);

        // When
        int consumed = sut.ingestVarBinChunk(new String[]{huge, "other"});

        // Then — the caller makes progress, and the next value starts a new dictionary
        assertThat(consumed).isEqualTo(1);
    }

    @Test
    void ingestVarBinChunk_aFullDictionaryTakesNothingNew() {
        // Given
        DictColumnState sut = new DictColumnState(new DType.Utf8(false), true);
        sut.ingestVarBinChunk(new String[]{"x".repeat((int) DictColumnState.MAX_DICT_BYTES + 1)});

        // When
        int consumed = sut.ingestVarBinChunk(new String[]{"new"});

        // Then
        assertThat(consumed).isZero();
        assertThat(sut.chunkCount()).isEqualTo(1);
    }
}
