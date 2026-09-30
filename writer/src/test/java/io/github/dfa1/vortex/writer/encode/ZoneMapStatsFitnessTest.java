package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.testing.DTypes;
import io.github.dfa1.vortex.writer.WriteRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// Fitness function for the zone-map contract: **an encoding that wins a column must report that
/// column's bounds.**
///
/// Five encodings shipped without doing so - `vortex.runend` (#385), `vortex.zigzag` (#386),
/// `vortex.fsst`, `vortex.ext` and `vortex.datetimeparts` - and each was found by eye, on one
/// dataset, by noticing a blank min/max in the inspector. A column that loses its bounds loses
/// zone-map pruning and `MIN`/`MAX` push-down, silently, and only for the data that happened to
/// select that encoding. Nothing failed. See issue #417.
///
/// This test makes the omission impossible to add quietly: every encoding registered on
/// [WriteRegistry] must appear in [#CLASSIFICATION] below, and every one classified [Verdict#BOUNDS]
/// must actually produce them.
class ZoneMapStatsFitnessTest {

    /// Whether an encoding owes the column its bounds.
    private enum Verdict {
        /// Must report min/max: the column it encodes has a meaningful ordering.
        BOUNDS,
        /// Need not: a container, a validity/offset child, or a column with no scalar ordering.
        /// Rust's `Stat::dtype` returns null for these too.
        EXEMPT,
        /// Owes bounds and does not report them yet. Recorded rather than classified EXEMPT,
        /// because calling a real gap "exempt" is exactly the quiet default this test exists to
        /// stop. Tracked in issue #417; the list may shrink, and
        /// [#knownGaps_haveNotGrown()] fails if it grows.
        KNOWN_GAP
    }

    /// Every encoding on the registry, classified. A new encoding fails
    /// [#everyRegisteredEncoding_isClassified()] until it is added here, which is the point: the
    /// author has to decide, rather than inherit `null, null` by default.
    private static final Map<EncodingId, Verdict> CLASSIFICATION = classification();

    private static Map<EncodingId, Verdict> classification() {
        Map<EncodingId, Verdict> m = new LinkedHashMap<>();
        // Orderable columns — these owe bounds.
        m.put(EncodingId.VORTEX_PRIMITIVE, Verdict.BOUNDS);
        m.put(EncodingId.VORTEX_CONSTANT, Verdict.BOUNDS);
        m.put(EncodingId.VORTEX_VARBIN, Verdict.BOUNDS);
        m.put(EncodingId.VORTEX_VARBINVIEW, Verdict.BOUNDS);
        m.put(EncodingId.VORTEX_FSST, Verdict.BOUNDS);
        m.put(EncodingId.VORTEX_DICT, Verdict.BOUNDS);
        m.put(EncodingId.VORTEX_MASKED, Verdict.BOUNDS);
        m.put(EncodingId.VORTEX_EXT, Verdict.BOUNDS);
        m.put(EncodingId.VORTEX_DATETIMEPARTS, Verdict.BOUNDS);
        m.put(EncodingId.VORTEX_RUNEND, Verdict.BOUNDS);
        m.put(EncodingId.VORTEX_ZIGZAG, Verdict.BOUNDS);
        m.put(EncodingId.VORTEX_ALP, Verdict.BOUNDS);
        m.put(EncodingId.VORTEX_ALPRD, Verdict.BOUNDS);
        m.put(EncodingId.VORTEX_PCO, Verdict.BOUNDS);
        m.put(EncodingId.VORTEX_ZSTD, Verdict.BOUNDS);
        m.put(EncodingId.VORTEX_SEQUENCE, Verdict.BOUNDS);
        m.put(EncodingId.FASTLANES_RLE, Verdict.BOUNDS);
        m.put(EncodingId.FASTLANES_FOR, Verdict.BOUNDS);
        m.put(EncodingId.FASTLANES_BITPACKED, Verdict.BOUNDS);
        m.put(EncodingId.FASTLANES_DELTA, Verdict.BOUNDS);
        m.put(EncodingId.VORTEX_BOOL, Verdict.BOUNDS);
        // Not orderable, or not a column of its own.
        m.put(EncodingId.VORTEX_STRUCT, Verdict.EXEMPT);
        m.put(EncodingId.VORTEX_CHUNKED, Verdict.EXEMPT);
        m.put(EncodingId.VORTEX_LIST, Verdict.EXEMPT);
        m.put(EncodingId.VORTEX_LISTVIEW, Verdict.EXEMPT);
        m.put(EncodingId.VORTEX_MAP, Verdict.EXEMPT);
        m.put(EncodingId.VORTEX_FIXED_SIZE_LIST, Verdict.EXEMPT);
        m.put(EncodingId.VORTEX_VARIANT, Verdict.EXEMPT);
        m.put(EncodingId.VORTEX_NULL, Verdict.EXEMPT);
        m.put(EncodingId.VORTEX_BYTEBOOL, Verdict.EXEMPT);
        // Orderable, but no scalar encoding exists for the bounds: ProtoScalarValue has no
        // decimal variant, so a decimal min/max cannot be serialized at all. Needs the scalar
        // representation settled against the Rust reference first — see issue #417.
        m.put(EncodingId.VORTEX_SPARSE, Verdict.BOUNDS);
        m.put(EncodingId.VORTEX_PATCHED, Verdict.BOUNDS);
        m.put(EncodingId.VORTEX_DECIMAL, Verdict.KNOWN_GAP);
        m.put(EncodingId.VORTEX_DECIMAL_BYTE_PARTS, Verdict.KNOWN_GAP);
        return Map.copyOf(m);
    }

    @Test
    void everyRegisteredEncoding_isClassified() {
        // Given
        Set<EncodingId> registered = WriteRegistry.loadAll().encoderMap().keySet();

        // When
        Set<EncodingId> unclassified = registered.stream()
                .filter(id -> !CLASSIFICATION.containsKey(id))
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));

        // Then — a new encoding lands here until its author decides whether it owes bounds
        assertThat(unclassified)
                .as("classify these in ZoneMapStatsFitnessTest#CLASSIFICATION (see issue #417)")
                .isEmpty();
    }

    @Test
    void classification_doesNotNameEncodingsTheRegistryNoLongerHas() {
        // Given
        Set<EncodingId> registered = WriteRegistry.loadAll().encoderMap().keySet();

        // When / Then — keeps the table honest as encodings are removed or renamed
        assertThat(CLASSIFICATION.keySet()).isSubsetOf(registered);
    }

    @Test
    void knownGaps_haveNotGrown() {
        // Given — the encodings that owe bounds and do not yet report them (issue #417)
        Set<EncodingId> expected = Set.of(
                EncodingId.VORTEX_DECIMAL,
                EncodingId.VORTEX_DECIMAL_BYTE_PARTS);

        // When
        Set<EncodingId> actual = CLASSIFICATION.entrySet().stream()
                .filter(e -> e.getValue() == Verdict.KNOWN_GAP)
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));

        // Then — fixing one means deleting it from both lists; adding one is not allowed
        assertThat(actual)
                .as("a new zone-map gap was introduced, or a fixed one was left listed (issue #417)")
                .isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("boundsOwingSamples")
    void encodingOwingBounds_reportsThem(EncodingId id, DType dtype, Object data) {
        // Given
        EncodingEncoder sut = WriteRegistry.loadAll().encoderMap().get(id);

        // When
        EncodeResult result = sut.encode(dtype, data, EncodeTestHelper.testCtx());

        // Then
        assertThat(result.hasStats())
                .as("%s encodes an orderable column but reported no min/max (see issue #417)", id.id())
                .isTrue();
    }

    /// One representative non-empty input per bounds-owing encoding that this test can drive
    /// directly. Encodings needing a shaped input their own tests already cover (dictionary
    /// cardinality, run structure, float exponent patterns) are asserted there instead.
    private static Stream<Arguments> boundsOwingSamples() {
        long[] ascending = {10L, 20L, 30L, 40L, 50L, 60L, 70L, 80L};
        String[] words = {"pear", "apple", "quince", "banana"};
        // Mostly the zero fill value sparse encodes around, with a couple of real values: the
        // bounds must cover the patches, not just the fill.
        long[] sparse = {0L, 0L, 0L, 7L, 0L, 0L, 99L, 0L};
        return Stream.of(
                Arguments.of(EncodingId.VORTEX_PRIMITIVE, DTypes.I64, ascending),
                Arguments.of(EncodingId.VORTEX_VARBIN, DTypes.UTF8, words),
                Arguments.of(EncodingId.VORTEX_VARBINVIEW, DTypes.UTF8, words),
                Arguments.of(EncodingId.VORTEX_FSST, DTypes.UTF8, words),
                Arguments.of(EncodingId.FASTLANES_RLE, DTypes.I64, ascending),
                Arguments.of(EncodingId.FASTLANES_FOR, DTypes.I64, ascending),
                Arguments.of(EncodingId.VORTEX_PCO, DTypes.I64, ascending),
                Arguments.of(EncodingId.VORTEX_ZIGZAG, DTypes.I64, ascending),
                Arguments.of(EncodingId.VORTEX_EXT,
                        new DType.Extension("vortex.timestamp", DType.I64, null, false), ascending),
                Arguments.of(EncodingId.VORTEX_SPARSE, DTypes.I64, sparse),
                Arguments.of(EncodingId.VORTEX_PATCHED, DTypes.I64, ascending));
    }
}
