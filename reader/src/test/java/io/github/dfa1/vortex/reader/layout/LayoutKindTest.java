package io.github.dfa1.vortex.reader.layout;

import io.github.dfa1.vortex.core.model.LayoutId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/// Pins the [Layout] layout-kind predicates (`isFlat`, `isChunked`, `isStruct`, `isZoned`,
/// `isDict`). [ScanIterator] dispatches layout-tree traversal on these, so a predicate that
/// silently returned a constant would route a whole layout family down the wrong decode path.
/// For a layout of each kind, exactly its own predicate must be `true` and every other `false`,
/// which fixes each method's return to its `layoutId` rather than a constant.
class LayoutKindTest {

    /// Each layout kind paired with its typed id and the predicate that should recognize it. The
    /// legacy [LayoutId#STATS] alias is expected to satisfy `isZoned` alongside [LayoutId#ZONED].
    private enum Kind {
        FLAT(LayoutId.FLAT, Layout::isFlat),
        CHUNKED(LayoutId.CHUNKED, Layout::isChunked),
        STRUCT(LayoutId.STRUCT, Layout::isStruct),
        ZONED(LayoutId.ZONED, Layout::isZoned),
        STATS(LayoutId.STATS, Layout::isZoned),
        DICT(LayoutId.DICT, Layout::isDict);

        private final LayoutId layoutId;
        private final Predicate<Layout> predicate;

        Kind(LayoutId layoutId, Predicate<Layout> predicate) {
            this.layoutId = layoutId;
            this.predicate = predicate;
        }
    }

    private static Layout layout(LayoutId layoutId) {
        return new Layout(layoutId, 0L, null, List.of(), List.of());
    }

    private static boolean isZonedFamily(Kind kind) {
        return kind == Kind.ZONED || kind == Kind.STATS;
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void predicate_recognizesOnlyItsOwnLayoutId(Kind kind) {
        // Given — a layout carrying this kind's layout id
        Layout sut = layout(kind.layoutId);

        // When / Then — this kind's predicate is true; unrelated predicates are false. ZONED and
        // STATS both satisfy isZoned (legacy alias), so each is recognized by the other's predicate.
        for (Kind other : Kind.values()) {
            boolean expected = other == kind || (isZonedFamily(other) && isZonedFamily(kind));
            assertThat(other.predicate.test(sut))
                    .as("%s on %s", other, kind.layoutId)
                    .isEqualTo(expected);
        }
    }

    @Test
    void predicates_allFalse_forUnknownLayoutId() {
        // Given — a custom id matching no known layout kind
        Layout sut = layout(new LayoutId.Custom("vortex.bogus"));

        // When / Then — no kind claims it
        for (Kind kind : Kind.values()) {
            assertThat(kind.predicate.test(sut)).as("%s", kind).isFalse();
        }
    }
}
