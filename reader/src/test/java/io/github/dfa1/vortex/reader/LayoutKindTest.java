package io.github.dfa1.vortex.reader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/// Pins the [Layout] encoding-kind predicates (`isFlat`, `isChunked`, `isStruct`, `isZoned`,
/// `isDict`). [ScanIterator] dispatches layout-tree traversal on these, so a predicate that
/// silently returned a constant would route a whole layout family down the wrong decode path.
/// For a layout of each kind, exactly its own predicate must be `true` and every other `false`,
/// which fixes each method's return to its `encodingId` rather than a constant.
class LayoutKindTest {

    /// Each layout kind paired with its encoding id and the predicate that should recognize it.
    private enum Kind {
        FLAT(Layout.FLAT, Layout::isFlat),
        CHUNKED(Layout.CHUNKED, Layout::isChunked),
        STRUCT(Layout.STRUCT, Layout::isStruct),
        ZONED(Layout.ZONED, Layout::isZoned),
        DICT(Layout.DICT, Layout::isDict);

        private final String encodingId;
        private final Predicate<Layout> predicate;

        Kind(String encodingId, Predicate<Layout> predicate) {
            this.encodingId = encodingId;
            this.predicate = predicate;
        }
    }

    private static Layout layout(String encodingId) {
        return new Layout(encodingId, 0L, null, List.of(), List.of());
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void predicate_recognizesOnlyItsOwnEncodingId(Kind kind) {
        // Given — a layout carrying this kind's encoding id
        Layout sut = layout(kind.encodingId);

        // When / Then — only this kind's predicate is true; every other kind's is false
        for (Kind other : Kind.values()) {
            assertThat(other.predicate.test(sut))
                    .as("%s on %s", other, kind.encodingId)
                    .isEqualTo(other == kind);
        }
    }

    @Test
    void predicates_allFalse_forUnknownEncodingId() {
        // Given — an id matching no known layout kind
        Layout sut = layout("vortex.bogus");

        // When / Then — no kind claims it
        for (Kind kind : Kind.values()) {
            assertThat(kind.predicate.test(sut)).as("%s", kind).isFalse();
        }
    }
}
