package io.github.dfa1.vortex.reader;

import io.github.dfa1.vortex.reader.layout.Layout;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.fbs.FbsArraySpec;
import io.github.dfa1.vortex.core.fbs.FbsBuilder;
import io.github.dfa1.vortex.core.fbs.FbsFooter;
import io.github.dfa1.vortex.core.fbs.FbsLayout;
import io.github.dfa1.vortex.core.fbs.FbsLayoutSpec;
import io.github.dfa1.vortex.core.model.LayoutId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.foreign.MemorySegment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Drives `PostscriptParser.convertLayout` string → [LayoutId] resolution through the
/// package-private [PostscriptParser#parseBlobs] with crafted footer / layout FlatBuffers — no
/// file needed. Two concerns:
/// - the zoned wire alias: Rust renamed the zone-map layout id to `vortex.zoned` while keeping
///   `vortex.stats` as a legacy alias (which vortex-java writes). A file using either spec string
///   must parse to a zoned layout that traverses identically, so an interop file from a newer Rust
///   writer does not fail to open.
/// - a blank layout spec entry (untrusted input) must surface as [VortexException], not the
///   [IllegalArgumentException] that [LayoutId#parse] raises for a blank id.
class PostscriptParserLayoutIdTest {

    @ParameterizedTest
    @ValueSource(strings = {"vortex.stats", "vortex.zoned"})
    void convertLayout_zonedAlias_parsesToZonedLayoutWithPassthroughChild(String zonedSpec) {
        // Given — a zoned node (spec index 1) wrapping one flat child (spec index 0). The zoned
        // spec is exercised as both the legacy "vortex.stats" and the canonical "vortex.zoned".
        MemorySegment footer = footerWithLayoutSpecs("vortex.flat", zonedSpec);
        MemorySegment layout = zonedOverFlatLayout();

        // When
        Layout result = PostscriptParser.parseBlobs(footer, layout, null).layout();

        // Then — both aliases resolve to a zoned layout with the same shape: isZoned() is true and
        // the single flat child is preserved for pass-through decoding.
        assertThat(result.isZoned()).isTrue();
        assertThat(result.layoutId()).isEqualTo(LayoutId.parse(zonedSpec));
        assertThat(result.children()).hasSize(1);
        assertThat(result.children().getFirst().isFlat()).isTrue();
    }

    @Test
    void convertLayout_blankLayoutId_throwsVortexException() {
        // Given — a zero-length FlatBuffer string in the layout spec table decodes to "", which
        // LayoutId.parse rejects with IllegalArgumentException; untrusted input must surface as
        // VortexException instead.
        MemorySegment footer = footerWithLayoutSpecs("");
        MemorySegment layout = flatLayout();

        // When / Then
        assertThatThrownBy(() -> PostscriptParser.parseBlobs(footer, layout, null))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("blank layout id");
    }

    // ── FlatBuffer builders ─────────────────────────────────────────────────────

    private static MemorySegment footerWithLayoutSpecs(String... layoutSpecs) {
        var fbb = new FbsBuilder(256);
        int asv = FbsFooter.createArraySpecsVector(fbb, new int[]{
                FbsArraySpec.createFbsArraySpec(fbb, fbb.createString("vortex.primitive"))});
        int[] ls = new int[layoutSpecs.length];
        for (int i = 0; i < layoutSpecs.length; i++) {
            ls[i] = FbsLayoutSpec.createFbsLayoutSpec(fbb, fbb.createString(layoutSpecs[i]));
        }
        int lsv = FbsFooter.createLayoutSpecsVector(fbb, ls);
        FbsFooter.startSegmentSpecsVector(fbb, 0);
        int ssv = fbb.endVector();
        int footOff = FbsFooter.createFbsFooter(fbb, asv, lsv, ssv, 0, 0);
        fbb.finish(footOff);
        return fbb.dataSegment();
    }

    private static MemorySegment flatLayout() {
        var fbb = new FbsBuilder(128);
        int segV = FbsLayout.createSegmentsVector(fbb, new long[]{0});
        int off = FbsLayout.createFbsLayout(fbb, 0, 1L, 0, 0, segV);
        FbsLayout.finishFbsLayoutBuffer(fbb, off);
        return fbb.dataSegment();
    }

    private static MemorySegment zonedOverFlatLayout() {
        var fbb = new FbsBuilder(192);
        // Flat child first — FlatBuffer requires children be finished before parents.
        int childSegV = FbsLayout.createSegmentsVector(fbb, new long[]{0});
        int flatChild = FbsLayout.createFbsLayout(fbb, 0, 1L, 0, 0, childSegV);
        int childV = FbsLayout.createChildrenVector(fbb, new int[]{flatChild});
        // Zoned parent references layout spec index 1.
        int zoned = FbsLayout.createFbsLayout(fbb, 1, 1L, 0, childV, 0);
        FbsLayout.finishFbsLayoutBuffer(fbb, zoned);
        return fbb.dataSegment();
    }
}
