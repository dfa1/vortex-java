package io.github.dfa1.vortex.reader;

import io.github.dfa1.vortex.core.fbs.FbsBuilder;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.fbs.FbsArraySpec;
import io.github.dfa1.vortex.core.fbs.FbsFooter;
import io.github.dfa1.vortex.core.fbs.FbsLayout;
import io.github.dfa1.vortex.core.fbs.FbsLayoutSpec;
import io.github.dfa1.vortex.core.fbs.FbsPostscript;
import io.github.dfa1.vortex.core.fbs.FbsPostscriptSegment;
import io.github.dfa1.vortex.core.fbs.FbsPrimitive;
import io.github.dfa1.vortex.core.fbs.FbsType;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Bounds coverage for `PostscriptParser.checkBlobBounds` — the guard that rejects a postscript
/// whose footer / layout / dtype blob pointer escapes the mapped file, run before any blob is
/// sliced or parsed. Drives [PostscriptParser#parse] directly with an in-memory
/// [MemorySegment] (no file I/O): a valid file segment of `[layout | dtype | footer]`, then a
/// postscript whose one blob pointer is moved out of range.
///
/// Two things must hold for every blob:
/// - an out-of-range pointer throws a `VortexException` naming *that blob* — not the generic
///   `IoBounds` slice message. Asserting the specific message is what proves the dedicated
///   `checkBlobBounds` call still runs (delete it and the failure shifts to the later slice,
///   with a different message).
/// - the footer blob is laid out last so it ends exactly at EOF: its `length == fileSize -
///   offset`, the largest legal range, which must still pass.
class PostscriptParserBlobBoundsTest {

    /// In-memory file: blobs concatenated as `[layout | dtype | footer]` with the footer last so
    /// it ends at EOF (exercises the exact-fit upper bound). Offsets/lengths are recorded for the
    /// postscript builder.
    private record Fixture(MemorySegment segment, long fileSize,
                           long footerOff, int footerLen,
                           long dtypeOff, int dtypeLen,
                           long layoutOff, int layoutLen) {
    }

    private static Fixture validFile() {
        MemorySegment layout = buildFlatLayout();
        MemorySegment dtype = buildI64Dtype();
        MemorySegment footer = buildFooter();
        int layoutLen = (int) layout.byteSize();
        int dtypeLen = (int) dtype.byteSize();
        int footerLen = (int) footer.byteSize();

        int layoutOff = 0;
        int dtypeOff = layoutOff + layoutLen;
        int footerOff = dtypeOff + dtypeLen;
        int fileSize = footerOff + footerLen;

        byte[] file = new byte[fileSize];
        copyInto(file, layoutOff, layout);
        copyInto(file, dtypeOff, dtype);
        copyInto(file, footerOff, footer);

        return new Fixture(MemorySegment.ofArray(file), fileSize,
                footerOff, footerLen, dtypeOff, dtypeLen, layoutOff, layoutLen);
    }

    @Test
    void parse_validInBoundsBlobs_succeeds() {
        // Given — every blob pointer fits; footer ends exactly at EOF (length == fileSize - offset)
        Fixture f = validFile();
        MemorySegment ps = buildPostscript(f.footerOff, f.footerLen, f.dtypeOff, f.dtypeLen,
                f.layoutOff, f.layoutLen);

        // When / Then — the largest legal footer range must not be rejected
        assertThatCode(() -> PostscriptParser.parse(ps, f.segment, f.fileSize))
                .doesNotThrowAnyException();
    }

    @Test
    void parse_footerBlobPastEof_throwsNamingFooter() {
        // Given — footer pointer one byte past EOF; everything else valid
        Fixture f = validFile();
        MemorySegment ps = buildPostscript(f.fileSize + 1, f.footerLen, f.dtypeOff, f.dtypeLen,
                f.layoutOff, f.layoutLen);

        // When / Then — rejected by the footer-specific check, not the later slice
        assertThatThrownBy(() -> PostscriptParser.parse(ps, f.segment, f.fileSize))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("footer blob out of bounds");
    }

    @Test
    void parse_layoutBlobLengthOverrunsEof_throwsNamingLayout() {
        // Given — layout length reaches one byte past EOF (offset valid, offset + length > fileSize)
        Fixture f = validFile();
        MemorySegment ps = buildPostscript(f.footerOff, f.footerLen, f.dtypeOff, f.dtypeLen,
                f.layoutOff, (int) (f.fileSize - f.layoutOff + 1));

        // When / Then
        assertThatThrownBy(() -> PostscriptParser.parse(ps, f.segment, f.fileSize))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("layout blob out of bounds");
    }

    @Test
    void parse_footerBlobLengthOverrunsEof_throwsNamingFooter() {
        // Given — footer length one byte past EOF. The footer sits at a non-zero offset (after
        // layout + dtype), so `length > fileSize - offset` and `length > fileSize + offset` give
        // different answers — this is what kills the `fileSize - offset` → `+` math mutant that a
        // zero-offset overrun (the layout case) cannot distinguish.
        Fixture f = validFile();
        MemorySegment ps = buildPostscript(f.footerOff, (int) (f.fileSize - f.footerOff + 1),
                f.dtypeOff, f.dtypeLen, f.layoutOff, f.layoutLen);

        // When / Then
        assertThatThrownBy(() -> PostscriptParser.parse(ps, f.segment, f.fileSize))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("footer blob out of bounds");
    }

    @Test
    void parse_dtypeBlobPastEof_throwsNamingDtype() {
        // Given — dtype pointer past EOF with non-zero length, so the dtype check runs
        Fixture f = validFile();
        MemorySegment ps = buildPostscript(f.footerOff, f.footerLen, f.fileSize + 1, f.dtypeLen,
                f.layoutOff, f.layoutLen);

        // When / Then
        assertThatThrownBy(() -> PostscriptParser.parse(ps, f.segment, f.fileSize))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("dtype blob out of bounds");
    }

    @Test
    void parse_dtypeBlobLengthZero_skipsDtypeCheckEvenWithBadOffset() {
        // Given — dtype length 0 with a wildly out-of-range offset. The `length > 0` gate must
        // skip both the bounds check and the slice, so a zero-length dtype never trips on its
        // offset and the file still parses (dtype absent). Kills the `length > 0` boundary/negate
        // mutants that would otherwise run the check on an empty dtype.
        Fixture f = validFile();
        MemorySegment ps = buildPostscript(f.footerOff, f.footerLen, f.fileSize + 999, 0,
                f.layoutOff, f.layoutLen);

        // When
        PostscriptParser.ParsedFile result = PostscriptParser.parse(ps, f.segment, f.fileSize);

        // Then — parsed, with no dtype
        assertThat(result.dtype()).isNull();
    }

    // ── FlatBuffer blob builders (minimal, just enough to parse) ────────────────

    private static MemorySegment buildFooter() {
        var fbb = new FbsBuilder(256);
        int asv = FbsFooter.createArraySpecsVector(fbb, new int[]{
                FbsArraySpec.createFbsArraySpec(fbb, fbb.createString("vortex.primitive"))});
        int lsv = FbsFooter.createLayoutSpecsVector(fbb, new int[]{
                FbsLayoutSpec.createFbsLayoutSpec(fbb, fbb.createString(io.github.dfa1.vortex.reader.Layout.FLAT))});
        // No segment_specs: validateSegmentSpecs has its own dedicated test; keep this fixture
        // focused on the blob-pointer bounds.
        FbsFooter.startSegmentSpecsVector(fbb, 0);
        int ssv = fbb.endVector();
        int footOff = FbsFooter.createFbsFooter(fbb, asv, lsv, ssv, 0, 0);
        fbb.finish(footOff);
        return slice(fbb);
    }

    private static MemorySegment buildI64Dtype() {
        var fbb = new FbsBuilder(64);
        int prim = FbsPrimitive.createFbsPrimitive(fbb, io.github.dfa1.vortex.core.fbs.FbsPType.I64, false);
        int off = io.github.dfa1.vortex.core.fbs.FbsDType.createFbsDType(fbb, FbsType.FbsPrimitive, prim);
        io.github.dfa1.vortex.core.fbs.FbsDType.finishFbsDTypeBuffer(fbb, off);
        return slice(fbb);
    }

    private static MemorySegment buildFlatLayout() {
        var fbb = new FbsBuilder(128);
        int segV = FbsLayout.createSegmentsVector(fbb, new long[]{0});
        int layoutOff = FbsLayout.createFbsLayout(fbb, 0, 1L, 0, 0, segV);
        FbsLayout.finishFbsLayoutBuffer(fbb, layoutOff);
        return slice(fbb);
    }

    private static MemorySegment buildPostscript(
            long footerOff, int footerLen, long dtypeOff, int dtypeLen, long layoutOff, int layoutLen) {
        var fbb = new FbsBuilder(128);
        int footSeg = FbsPostscriptSegment.createFbsPostscriptSegment(fbb, footerOff, footerLen, 0, 0, 0);
        int dtypeSeg = FbsPostscriptSegment.createFbsPostscriptSegment(fbb, dtypeOff, dtypeLen, 0, 0, 0);
        int layoutSeg = FbsPostscriptSegment.createFbsPostscriptSegment(fbb, layoutOff, layoutLen, 0, 0, 0);
        int psOff = FbsPostscript.createFbsPostscript(fbb, dtypeSeg, layoutSeg, 0, footSeg);
        FbsPostscript.finishFbsPostscriptBuffer(fbb, psOff);
        return slice(fbb);
    }

    private static MemorySegment slice(FbsBuilder fbb) {
        return fbb.dataSegment();
    }

    private static void copyInto(byte[] dst, int offset, MemorySegment src) {
        MemorySegment.copy(src, java.lang.foreign.ValueLayout.JAVA_BYTE, 0, dst, offset, (int) src.byteSize());
    }
}
