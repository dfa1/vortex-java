package io.github.dfa1.vortex.reader;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.LayoutId;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.testing.TestSegments;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.MaterializedBoolArray;
import io.github.dfa1.vortex.reader.array.VarBinOffsetArray;
import io.github.dfa1.vortex.reader.compute.Predicate;
import io.github.dfa1.vortex.reader.layout.Layout;
import io.github.dfa1.vortex.reader.layout.LayoutDecodeContext;
import io.github.dfa1.vortex.reader.layout.LayoutDecoder;
import io.github.dfa1.vortex.reader.layout.LayoutRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Named.named;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/// A scan skips a `vortex.dict` layout's chunk when no dictionary value satisfies the filter and a
/// null row cannot either; it must never skip a chunk a row of which matches.
class ScanIteratorDictPruningTest {

    private static final ColumnName S = ColumnName.of("s");
    private static final DType NULLABLE_UTF8 = new DType.Utf8(true);
    private static final LayoutId VALUES = LayoutId.parse("test.values");
    private static final long ROWS = 4;

    static Stream<Arguments> predicates() {
        Predicate missing = new Predicate.Eq("x");
        return Stream.of(
                Arguments.of(named("eq missing", missing), true),
                Arguments.of(named("eq present", new Predicate.Eq("a")), false),
                Arguments.of(named("lt below every value", new Predicate.Lt("a")), true),
                Arguments.of(named("neq a value", new Predicate.Neq("a")), false),
                Arguments.of(named("is not null", new Predicate.IsNotNull()), false),
                // a row with a null code satisfies IS NULL whatever the values hold
                Arguments.of(named("is null", new Predicate.IsNull()), false),
                Arguments.of(named("missing or null", new Predicate.Or(missing, new Predicate.IsNull())), false),
                Arguments.of(named("missing and null", new Predicate.And(missing, new Predicate.IsNull())), true),
                Arguments.of(named("missing or present", new Predicate.Or(missing, new Predicate.Eq("b"))), false));
    }

    @ParameterizedTest
    @MethodSource("predicates")
    void dictionaryValues_decideWhetherTheChunkIsSkipped(Predicate predicate, boolean skipped) {
        // Given — a dictionary holding the values a and b
        ScanIterator sut = scan(strings("a", "b"), 2, predicate);

        // When
        boolean result = sut.hasNext();

        // Then
        assertThat(result).isEqualTo(!skipped);
    }

    @Test
    void nullDictionaryValues_satisfyNoValuePredicate() {
        // Given — the values a and a null: rows coding the null value are null, so only a can match
        Array values = new MaskedArray(strings("a", "b"), new MaterializedBoolArray(DType.BOOL, 2,
                MemorySegment.ofArray(new byte[]{0b01})));

        // When
        boolean neqA = scan(values, 2, new Predicate.Neq("a")).hasNext();
        boolean isNotNull = scan(values, 2, new Predicate.IsNotNull()).hasNext();

        // Then
        assertThat(neqA).isFalse();
        assertThat(isNotNull).isTrue();
    }

    @Test
    void valuesDecodingLongerThanClaimed_areAllTested() {
        // Given — the values layout claims one value but decodes three; a code can reach "x"
        ScanIterator sut = scan(strings("a", "b", "x"), 1, new Predicate.Eq("x"));

        // When
        boolean result = sut.hasNext();

        // Then
        assertThat(result).isTrue();
    }

    @Test
    void moreValuesThanRows_isNotTested() {
        // Given — five values for four rows: testing them all could cost more than the scan
        ScanIterator sut = scan(strings("a", "b", "c", "d", "e"), 5, new Predicate.Eq("x"));

        // When
        boolean result = sut.hasNext();

        // Then
        assertThat(result).isTrue();
    }

    @Test
    void malformedValues_failAsVortexException() {
        // Given — value 1's offsets point past the 1-byte data buffer
        Array values = new VarBinOffsetArray(NULLABLE_UTF8, 2, MemorySegment.ofArray(new byte[]{'a'}),
                TestSegments.leInts(new int[]{0, 1, 99}), PType.I32);
        ScanIterator sut = scan(values, 2, new Predicate.Eq("x"));

        // When / Then
        assertThatThrownBy(sut::hasNext).isInstanceOf(VortexException.class);
    }

    /// A scan of one dict column whose values layout decodes to `values` and claims `claimed`
    /// entries, filtered by `predicate`.
    private static ScanIterator scan(Array values, long claimed, Predicate predicate) {
        Layout valuesLayout = new Layout(VALUES, claimed, null, List.of(), List.of());
        Layout codes = new Layout(LayoutId.FLAT, ROWS, null, List.of(), List.of());
        Layout dict = new Layout(LayoutId.DICT, ROWS, null, List.of(valuesLayout, codes), List.of());
        VortexHandle file = mock(VortexHandle.class);
        given(file.dtype()).willReturn(new DType.Struct(List.of(S), List.of(NULLABLE_UTF8), false));
        given(file.layout()).willReturn(new Layout(LayoutId.STRUCT, ROWS, null, List.of(dict), List.of()));
        given(file.layoutRegistry()).willReturn(LayoutRegistry.builder().registerDefaults()
                .register(new FixedValues(values)).build());
        given(file.registry()).willReturn(ReadRegistry.builder().registerDefaults().build());
        return new ScanIterator(file, ScanOptions.columns(S).withFilter(new ScanFilter.Column(S, predicate)));
    }

    private static VarBinOffsetArray strings(String... values) {
        byte[] bytes = String.join("", values).getBytes(StandardCharsets.UTF_8);
        int[] offsets = new int[values.length + 1];
        for (int i = 0; i < values.length; i++) {
            offsets[i + 1] = offsets[i] + values[i].getBytes(StandardCharsets.UTF_8).length;
        }
        return new VarBinOffsetArray(NULLABLE_UTF8, values.length, MemorySegment.ofArray(bytes),
                TestSegments.leInts(offsets), PType.I32);
    }

    /// Decodes the values layout to a fixed array, as a flat layout would decode its segment.
    private record FixedValues(Array values) implements LayoutDecoder {
        @Override
        public LayoutId layoutId() {
            return VALUES;
        }

        @Override
        public Array decode(LayoutDecodeContext ctx, Layout layout, DType dtype) {
            return values;
        }
    }
}
