package io.github.dfa1.vortex.reader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// Pins the [Layout] encoding-kind predicates (`isFlat`, `isChunked`, `isStruct`, `isZoned`,
/// `isDict`). [ScanIterator] dispatches layout-tree traversal on these, so a predicate that
/// silently returns a constant would route a whole layout family down the wrong decode path.
/// One layout per encoding id, asserting the matching predicate is `true` and every other is
/// `false` — which fixes each method's return to its `encodingId` rather than a constant.
class LayoutKindTest {

    private static Layout layout(String encodingId) {
        return new Layout(encodingId, 0L, null, List.of(), List.of());
    }

    static Stream<Arguments> kinds() {
        // (encodingId, isFlat, isChunked, isStruct, isZoned, isDict)
        return Stream.of(
                Arguments.of(Layout.FLAT, true, false, false, false, false),
                Arguments.of(Layout.CHUNKED, false, true, false, false, false),
                Arguments.of(Layout.STRUCT, false, false, true, false, false),
                Arguments.of(Layout.ZONED, false, false, false, true, false),
                Arguments.of(Layout.DICT, false, false, false, false, true));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("kinds")
    void predicates_matchOnlyOwnEncodingId(
            String encodingId, boolean flat, boolean chunked, boolean struct, boolean zoned, boolean dict) {
        // Given
        Layout sut = layout(encodingId);

        // When / Then — exactly one predicate is true, the rest false
        assertThat(sut.isFlat()).as("isFlat").isEqualTo(flat);
        assertThat(sut.isChunked()).as("isChunked").isEqualTo(chunked);
        assertThat(sut.isStruct()).as("isStruct").isEqualTo(struct);
        assertThat(sut.isZoned()).as("isZoned").isEqualTo(zoned);
        assertThat(sut.isDict()).as("isDict").isEqualTo(dict);
    }

    @Test
    void predicates_allFalse_forUnknownEncodingId() {
        // Given — an id matching no known layout kind
        Layout sut = layout("vortex.bogus");

        // When / Then — no predicate claims it
        assertThat(sut.isFlat()).isFalse();
        assertThat(sut.isChunked()).isFalse();
        assertThat(sut.isStruct()).isFalse();
        assertThat(sut.isZoned()).isFalse();
        assertThat(sut.isDict()).isFalse();
    }
}
