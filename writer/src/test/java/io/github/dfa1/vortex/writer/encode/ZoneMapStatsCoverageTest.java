package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.TimeUnit;
import io.github.dfa1.vortex.core.testing.DTypes;
import io.github.dfa1.vortex.writer.WriteRegistry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.foreign.MemorySegment;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// Fitness function for #382/#384/#385/#386 and the sibling bugs found in the same audit
/// (`Pco`/`Rle`/`Sparse`/`Patched`/`Zstd`/`Fsst`/`VarBinView`/`Ext`/`DateTimeParts`/`Sequence`):
/// every registered [EncodingEncoder] that can encode a `Primitive`/`Extension`/`Utf8` value must
/// report zone-map `MIN`/`MAX` stats for representative non-empty input, or `RowFilter` pruning
/// silently no-ops for any column that encoder wins.
///
/// Two checks:
/// - [#everyStatsEligibleDefaultEncoder_hasACoverageCase] — registry-driven: every default encoder
///   whose [EncodingEncoder#accepts] matches one of a few representative dtypes must appear in
///   [#coverageCases]. Add an encoder capable of `Primitive`/`Extension`/`Utf8` without adding a
///   case here and this fails — the whole point, so the next such bug can't land silently.
/// - [#encode_reportsMinMaxStats] — the actual per-encoder assertion, run over every case.
///
/// Deliberately excluded (no zone-map `MIN`/`MAX` concept at all, per
/// `ZoneMapStatCodec#zoneMinMaxDtype`): `Decimal`/`DecimalByteParts` (dtype `Decimal`, not in the
/// codec's supported set), `Bool`/`ByteBool` (dtype `Bool`), every structural/collection encoder
/// (`Chunked`, `FixedSizeList`, `List`, `ListView`, `Map`, `Null`, `Struct`, `Variant`) — none
/// accept a `Primitive`/`Extension`/`Utf8` dtype, so they never surface via the registry probe
/// below. `MaskedEncodingEncoder` is excluded too: its `accepts()` is unconditionally `false` (it
/// is special-dispatched for nullable columns, never registry-selected), so it cannot appear via
/// this probe either — its own stats correctness (#381) is covered by its dedicated test class.
class ZoneMapStatsCoverageTest {

    private static final DType TIMESTAMP_MS = new DType.Extension(
            "vortex.timestamp", DType.I64, MemorySegment.ofArray(new byte[]{(byte) TimeUnit.Milliseconds.ordinal(), 0, 0}), false);

    /// Representative dtypes to probe every default-registered encoder's [EncodingEncoder#accepts]
    /// with. Deliberately not exhaustive over every `PType` -- just enough to surface every
    /// stats-eligible encoder class at least once (an integer, a float, a string, an extension).
    private static final DType[] PROBE_DTYPES = {DTypes.I64, DTypes.F64, DTypes.UTF8, TIMESTAMP_MS};

    @Test
    void everyStatsEligibleDefaultEncoder_hasACoverageCase() {
        // Given -- every encoder the default registry would actually select
        WriteRegistry registry = WriteRegistry.builder().registerDefaults().build();

        // When -- narrowed to those that can encode at least one representative comparable dtype
        Set<Class<?>> statsEligible = new HashSet<>();
        for (EncodingEncoder encoder : registry.encoderMap().values()) {
            for (DType probe : PROBE_DTYPES) {
                if (encoder.accepts(probe)) {
                    statsEligible.add(encoder.getClass());
                    break;
                }
            }
        }
        Set<Class<?>> covered = coverageCases().map(a -> a.get()[1].getClass()).collect(java.util.stream.Collectors.toSet());

        // Then -- every stats-eligible encoder has a coverage case (new encoder + no case here = failure)
        assertThat(covered).containsAll(statsEligible);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("coverageCases")
    void encode_reportsMinMaxStats(String label, EncodingEncoder encoder, DType dtype, Object data) {
        // Given / When
        EncodeResult result = encoder.encode(dtype, data, EncodeTestHelper.testCtx());

        // Then
        assertThat(result.hasStats()).as(label).isTrue();
    }

    static Stream<Arguments> coverageCases() {
        return Stream.of(
                Arguments.of("Alp/f64", new AlpEncodingEncoder(), DTypes.F64, new double[]{1.1, 2.2, 3.3, 4.4}),
                Arguments.of("AlpRd/f64", new AlpRdEncodingEncoder(), DTypes.F64, new double[]{0.5, -3.25, 10.0, 2.0}),
                Arguments.of("Bitpacked/i64", new BitpackedEncodingEncoder(), DTypes.I64, new long[]{1L, 2L, 3L, 4L, 5L}),
                Arguments.of("Constant/i64", new ConstantEncodingEncoder(), DTypes.I64, new long[]{7L, 7L, 7L}),
                Arguments.of("DateTimeParts/timestamp", new DateTimePartsEncodingEncoder(), TIMESTAMP_MS,
                        new DateTimePartsData(new long[]{1_700_000_000_000L, 1_700_000_100_000L, 1_699_999_900_000L}, false)),
                Arguments.of("Delta/i64", new DeltaEncodingEncoder(), DTypes.I64, new long[]{10L, 20L, 15L, 30L}),
                Arguments.of("Dict/i32", new DictEncodingEncoder(), DTypes.I32, new int[]{1, 1, 2, 2, 3}),
                Arguments.of("Ext/timestamp", new ExtEncodingEncoder(), TIMESTAMP_MS, new long[]{100L, 200L, 300L}),
                Arguments.of("FrameOfReference/i64", new FrameOfReferenceEncodingEncoder(), DTypes.I64, new long[]{1000L, 1001L, 1002L, 1003L}),
                Arguments.of("Fsst/utf8", new FsstEncodingEncoder(), DTypes.UTF8, new String[]{"hello", "world", "hello"}),
                Arguments.of("Patched/i32", new PatchedEncodingEncoder(), DTypes.I32, new int[]{1, 2, 3, 4, 1_000_000}),
                Arguments.of("Pco/i64", new PcoEncodingEncoder(), DTypes.I64, longRange(0, 4096)),
                Arguments.of("Primitive/i32", new PrimitiveEncodingEncoder(), DTypes.I32, new int[]{1, 2, 3}),
                Arguments.of("Rle/i32", new RleEncodingEncoder(), DTypes.I32, new int[]{1, 1, 2, 2, 3, 3}),
                Arguments.of("RunEnd/i64", new RunEndEncodingEncoder(), DTypes.I64, new long[]{1L, 1L, 2L, 2L, 3L}),
                Arguments.of("Sequence/i64", new SequenceEncodingEncoder(), DTypes.I64, new long[]{10L, 20L, 30L, 40L}),
                Arguments.of("Sparse/i32", new SparseEncodingEncoder(), DTypes.I32, new int[]{0, 0, 5, 0, 0}),
                Arguments.of("VarBin/utf8", new VarBinEncodingEncoder(), DTypes.UTF8, new String[]{"apple", "banana"}),
                Arguments.of("VarBinView/utf8", new VarBinViewEncodingEncoder(), DTypes.UTF8, new String[]{"apple", "banana"}),
                Arguments.of("ZigZag/i32", new ZigZagEncodingEncoder(), DTypes.I32, new int[]{-1, 1, -2, 2}),
                Arguments.of("Zstd/i64", new ZstdEncodingEncoder(), DTypes.I64, new long[]{1L, 2L, 3L, 4L, 5L})
        );
    }

    private static long[] longRange(long start, int n) {
        long[] a = new long[n];
        for (int i = 0; i < n; i++) {
            a[i] = start + i;
        }
        return a;
    }
}
