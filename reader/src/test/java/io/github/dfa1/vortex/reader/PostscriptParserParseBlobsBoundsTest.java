package io.github.dfa1.vortex.reader;

import java.lang.foreign.MemorySegment;

import io.github.dfa1.vortex.fbs.FbsBuilder;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.fbs.FbsArraySpec;
import io.github.dfa1.vortex.fbs.FbsDecimal;
import io.github.dfa1.vortex.fbs.FbsFooter;
import io.github.dfa1.vortex.fbs.FbsLayout;
import io.github.dfa1.vortex.fbs.FbsLayoutSpec;
import io.github.dfa1.vortex.fbs.FbsType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;


import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Boundary coverage for the validation guards inside `PostscriptParser.convertLayout` (layout
/// depth, encoding index) and `convertDType` (decimal precision / scale). Drives the
/// package-private [PostscriptParser#parseBlobs] directly with crafted footer / layout / dtype
/// FlatBuffers — no file or segment needed — so each guard is hit at its exact edge: the largest
/// legal value must parse, the first illegal value must throw a [VortexException].
class PostscriptParserParseBlobsBoundsTest {

    // ── FbsLayout encoding-index bound: encIdx < 0 || encIdx >= layoutSpecs.size() ──

    @Test
    void parseBlobs_layoutEncodingIndex_atLastSpec_parses() {
        // Given — one layout spec, layout references index 0 (== size - 1, the last valid index)
        MemorySegment footer = footerWithLayoutSpecs("vortex.flat");
        MemorySegment layout = flatLayout(0);

        // When / Then — the top valid index must be accepted (kills `>=` relaxed to `>`)
        assertThatCode(() -> PostscriptParser.parseBlobs(footer, layout, null))
                .doesNotThrowAnyException();
    }

    @Test
    void parseBlobs_layoutEncodingIndex_equalToSize_throws() {
        // Given — one layout spec (size 1), layout references index 1 (first out-of-range index)
        MemorySegment footer = footerWithLayoutSpecs("vortex.flat");
        MemorySegment layout = flatLayout(1);

        // When / Then
        assertThatThrownBy(() -> PostscriptParser.parseBlobs(footer, layout, null))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("encoding index");
    }

    // ── FbsLayout depth bound: depth > MAX_LAYOUT_DEPTH (64) ────────────────────────

    @Test
    void parseBlobs_layoutDepth_atLimit_parses() {
        // Given — a single-child chain whose deepest node sits at exactly MAX_LAYOUT_DEPTH.
        // convertLayout is called with depth == 64 there, and `64 > 64` is false. Kills the
        // `depth >` relaxed to `depth >=` mutant, which would reject the legal max-depth tree.
        MemorySegment footer = footerWithLayoutSpecs("vortex.flat");
        MemorySegment layout = nestedLayout(PostscriptParser.MAX_LAYOUT_DEPTH);

        // When / Then
        assertThatCode(() -> PostscriptParser.parseBlobs(footer, layout, null))
                .doesNotThrowAnyException();
    }

    @Test
    void parseBlobs_layoutDepth_oneOverLimit_throws() {
        // Given — one level deeper: the deepest node reaches depth 65, tripping `65 > 64`
        MemorySegment footer = footerWithLayoutSpecs("vortex.flat");
        MemorySegment layout = nestedLayout(PostscriptParser.MAX_LAYOUT_DEPTH + 1);

        // When / Then
        assertThatThrownBy(() -> PostscriptParser.parseBlobs(footer, layout, null))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("depth");
    }

    // ── FbsDecimal precision bound: precision < 1 || precision > 38 ─────────────────

    @ParameterizedTest
    @ValueSource(ints = {1, 38})
    void parseBlobs_decimalPrecision_atEdges_parses(int precision) {
        // Given — precision at the inclusive edges 1 and 38 (scale 0 keeps the scale guard happy).
        // Kills `precision < 1` -> `<= 1` (would reject precision 1) and `precision > 38` ->
        // `>= 38` (would reject precision 38).
        MemorySegment dtype = decimalDtype(precision, (byte) 0);

        // When
        DType result = parseDtype(dtype);

        // Then
        assertThat(result).isInstanceOf(DType.Decimal.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 39})
    void parseBlobs_decimalPrecision_outOfRange_throws(int precision) {
        // Given — precision just outside [1, 38]
        MemorySegment dtype = decimalDtype(precision, (byte) 0);

        // When / Then
        assertThatThrownBy(() -> parseDtype(dtype))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("precision");
    }

    // ── FbsDecimal scale bound: scale < 0 || scale > precision ──────────────────────

    @Test
    void parseBlobs_decimalScale_atEdges_parses() {
        // Given — scale 0 (lower edge) and scale == precision (upper edge) must both pass.
        // Kills `scale < 0` -> `<= 0` (would reject scale 0) and `scale > precision` ->
        // `>= precision` (would reject scale == precision).

        // When / Then
        assertThatCode(() -> parseDtype(decimalDtype(10, (byte) 0))).doesNotThrowAnyException();
        assertThatCode(() -> parseDtype(decimalDtype(10, (byte) 10))).doesNotThrowAnyException();
    }

    @Test
    void parseBlobs_decimalScale_abovePrecision_throws() {
        // Given — scale one past precision
        MemorySegment dtype = decimalDtype(10, (byte) 11);

        // When / Then
        assertThatThrownBy(() -> parseDtype(dtype))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("scale");
    }

    // ── FbsLayout metadata-size bound: metadata.remaining() > MAX_LAYOUT_METADATA_BYTES ──

    @Test
    void parseBlobs_layoutMetadata_atLimit_parses() {
        // Given — metadata of exactly MAX_LAYOUT_METADATA_BYTES (the largest allowed). `> MAX` is
        // false at the limit, so it must parse; kills `>` relaxed to `>=`, which would reject it.
        MemorySegment footer = footerWithLayoutSpecs("vortex.flat");
        MemorySegment layout = flatLayoutWithMetadata(PostscriptParser.MAX_LAYOUT_METADATA_BYTES);

        // When / Then
        assertThatCode(() -> PostscriptParser.parseBlobs(footer, layout, null))
                .doesNotThrowAnyException();
    }

    @Test
    void parseBlobs_layoutMetadata_oneOverLimit_throws() {
        // Given — one byte past the cap
        MemorySegment footer = footerWithLayoutSpecs("vortex.flat");
        MemorySegment layout = flatLayoutWithMetadata(PostscriptParser.MAX_LAYOUT_METADATA_BYTES + 1);

        // When / Then
        assertThatThrownBy(() -> PostscriptParser.parseBlobs(footer, layout, null))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("metadata size");
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    /// Parses a dtype blob through the full parseBlobs path, paired with a minimal valid
    /// footer + flat layout so convertDType is reached. Returns the decoded [DType].
    private static DType parseDtype(MemorySegment dtype) {
        MemorySegment footer = footerWithLayoutSpecs("vortex.flat");
        MemorySegment layout = flatLayout(0);
        return PostscriptParser.parseBlobs(footer, layout, dtype).dtype();
    }

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
        return slice(fbb);
    }

    private static MemorySegment flatLayout(int encodingIdx) {
        var fbb = new FbsBuilder(128);
        int segV = FbsLayout.createSegmentsVector(fbb, new long[]{0});
        int off = FbsLayout.createFbsLayout(fbb, encodingIdx, 1L, 0, 0, segV);
        FbsLayout.finishFbsLayoutBuffer(fbb, off);
        return slice(fbb);
    }

    private static MemorySegment flatLayoutWithMetadata(int metadataBytes) {
        var fbb = new FbsBuilder(metadataBytes + 128);
        int meta = FbsLayout.createMetadataVector(fbb, new byte[metadataBytes]);
        int segV = FbsLayout.createSegmentsVector(fbb, new long[]{0});
        int off = FbsLayout.createFbsLayout(fbb, 0, 1L, meta, 0, segV);
        FbsLayout.finishFbsLayoutBuffer(fbb, off);
        return slice(fbb);
    }

    private static MemorySegment nestedLayout(int depth) {
        var fbb = new FbsBuilder(depth * 32 + 64);
        int segV = FbsLayout.createSegmentsVector(fbb, new long[]{0});
        int current = FbsLayout.createFbsLayout(fbb, 0, 1L, 0, 0, segV);
        // Wrap `depth` times: the innermost leaf ends up at recursion depth == `depth`.
        for (int i = 0; i < depth; i++) {
            int childV = FbsLayout.createChildrenVector(fbb, new int[]{current});
            current = FbsLayout.createFbsLayout(fbb, 0, 1L, 0, childV, 0);
        }
        FbsLayout.finishFbsLayoutBuffer(fbb, current);
        return slice(fbb);
    }

    private static MemorySegment decimalDtype(int precision, byte scale) {
        var fbb = new FbsBuilder(64);
        int dec = FbsDecimal.createFbsDecimal(fbb, precision, scale, false);
        int off = io.github.dfa1.vortex.fbs.FbsDType.createFbsDType(fbb, FbsType.FbsDecimal, dec);
        io.github.dfa1.vortex.fbs.FbsDType.finishFbsDTypeBuffer(fbb, off);
        return slice(fbb);
    }

    private static MemorySegment slice(FbsBuilder fbb) {
        return fbb.dataSegment();
    }
}
