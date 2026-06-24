package io.github.dfa1.vortex.reader;

import io.github.dfa1.vortex.core.fbs.FbsBuilder;
import io.github.dfa1.vortex.core.fbs.FbsArraySpec;
import io.github.dfa1.vortex.core.fbs.FbsFooter;
import io.github.dfa1.vortex.core.fbs.FbsLayout;
import io.github.dfa1.vortex.core.fbs.FbsLayoutSpec;
import io.github.dfa1.vortex.core.fbs.FbsPostscript;
import io.github.dfa1.vortex.core.fbs.FbsPostscriptSegment;
import io.github.dfa1.vortex.core.fbs.FbsPrimitive;
import io.github.dfa1.vortex.core.fbs.FbsSegmentSpec;
import io.github.dfa1.vortex.core.fbs.FbsType;

import java.nio.ByteBuffer;

/// Shared FlatBuffer builders for hand-constructing (well-formed and malformed)
/// Vortex metadata blobs in the reader security tests. Each returns a sliced
/// little-endian [ByteBuffer] positioned at the finished root, ready to splice
/// into a file body. Centralised here so the bounds/depth/zip-bomb suites build
/// their fixtures the same way instead of each copy-pasting the kit.
final class MalformedFiles {

    private MalformedFiles() {
    }

    /// Builds a non-nullable I64 primitive DType blob.
    ///
    /// @return the finished DType FlatBuffer
    static ByteBuffer buildI64Dtype() {
        var fbb = new FbsBuilder(64);
        int prim = FbsPrimitive.createFbsPrimitive(fbb, io.github.dfa1.vortex.core.fbs.FbsPType.I64, false);
        int off = io.github.dfa1.vortex.core.fbs.FbsDType.createFbsDType(fbb, FbsType.FbsPrimitive, prim);
        io.github.dfa1.vortex.core.fbs.FbsDType.finishFbsDTypeBuffer(fbb, off);
        return slice(fbb);
    }

    /// Builds a FbsFooter with the given array/layout spec ids and inline segment specs.
    ///
    /// @param arraySpecs  encoding ids, one per array spec
    /// @param layoutSpecs layout ids, one per layout spec
    /// @param segOffsets  per-segment byte offsets
    /// @param segLengths  per-segment byte lengths
    /// @return the finished FbsFooter FlatBuffer
    static ByteBuffer buildFooter(
            String[] arraySpecs, String[] layoutSpecs,
            long[] segOffsets, long[] segLengths) {
        var fbb = new FbsBuilder(256);

        int[] asOffs = new int[arraySpecs.length];
        for (int i = 0; i < arraySpecs.length; i++) {
            asOffs[i] = FbsArraySpec.createFbsArraySpec(fbb, fbb.createString(arraySpecs[i]));
        }
        int asv = FbsFooter.createArraySpecsVector(fbb, asOffs);

        int[] lsOffs = new int[layoutSpecs.length];
        for (int i = 0; i < layoutSpecs.length; i++) {
            lsOffs[i] = FbsLayoutSpec.createFbsLayoutSpec(fbb, fbb.createString(layoutSpecs[i]));
        }
        int lsv = FbsFooter.createLayoutSpecsVector(fbb, lsOffs);

        // FbsSegmentSpec is an inline struct — write in reverse order.
        FbsFooter.startSegmentSpecsVector(fbb, segOffsets.length);
        for (int i = segOffsets.length - 1; i >= 0; i--) {
            FbsSegmentSpec.createFbsSegmentSpec(fbb, segOffsets[i], segLengths[i], 6, 0, 0);
        }
        int ssv = fbb.endVector();

        int footOff = FbsFooter.createFbsFooter(fbb, asv, lsv, ssv, 0, 0);
        fbb.finish(footOff);
        return slice(fbb);
    }

    /// Builds a flat FbsLayout referencing a single segment.
    ///
    /// @param layoutSpecIdx index into the footer's layout-spec vector
    /// @param rowCount      declared row count
    /// @param segIdx        index into the footer's segment-spec vector
    /// @return the finished FbsLayout FlatBuffer
    static ByteBuffer buildFlatLayout(int layoutSpecIdx, long rowCount, int segIdx) {
        var fbb = new FbsBuilder(128);
        int segV = FbsLayout.createSegmentsVector(fbb, new long[]{segIdx});
        int layoutOff = FbsLayout.createFbsLayout(fbb, layoutSpecIdx, rowCount, 0, 0, segV);
        FbsLayout.finishFbsLayoutBuffer(fbb, layoutOff);
        return slice(fbb);
    }

    /// Builds a FbsPostscript pointing at the footer, dtype, and layout blobs.
    ///
    /// @param footerOff footer byte offset
    /// @param footerLen footer byte length
    /// @param dtypeOff  dtype byte offset
    /// @param dtypeLen  dtype byte length
    /// @param layoutOff layout byte offset
    /// @param layoutLen layout byte length
    /// @return the finished FbsPostscript FlatBuffer
    static ByteBuffer buildPostscript(
            long footerOff, int footerLen,
            long dtypeOff, int dtypeLen,
            long layoutOff, int layoutLen) {
        var fbb = new FbsBuilder(128);
        int footSeg = FbsPostscriptSegment.createFbsPostscriptSegment(fbb, footerOff, footerLen, 0, 0, 0);
        int dtypeSeg = FbsPostscriptSegment.createFbsPostscriptSegment(fbb, dtypeOff, dtypeLen, 0, 0, 0);
        int layoutSeg = FbsPostscriptSegment.createFbsPostscriptSegment(fbb, layoutOff, layoutLen, 0, 0, 0);
        int psOff = FbsPostscript.createFbsPostscript(fbb, dtypeSeg, layoutSeg, 0, footSeg);
        FbsPostscript.finishFbsPostscriptBuffer(fbb, psOff);
        return slice(fbb);
    }

    /// Slices a finished builder's buffer to its remaining bytes.
    ///
    /// @param fbb a builder whose root has been finished
    /// @return a [ByteBuffer] view of the finished bytes
    static ByteBuffer slice(FbsBuilder fbb) {
        return fbb.dataSegment().asByteBuffer().order(java.nio.ByteOrder.LITTLE_ENDIAN);
    }
}
