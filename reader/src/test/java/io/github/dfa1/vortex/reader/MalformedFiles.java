package io.github.dfa1.vortex.reader;

import com.google.flatbuffers.FlatBufferBuilder;
import io.github.dfa1.vortex.fbs.ArraySpec;
import io.github.dfa1.vortex.fbs.Footer;
import io.github.dfa1.vortex.fbs.Layout;
import io.github.dfa1.vortex.fbs.LayoutSpec;
import io.github.dfa1.vortex.fbs.Postscript;
import io.github.dfa1.vortex.fbs.PostscriptSegment;
import io.github.dfa1.vortex.fbs.Primitive;
import io.github.dfa1.vortex.fbs.SegmentSpec;
import io.github.dfa1.vortex.fbs.Type;

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
        var fbb = new FlatBufferBuilder(64);
        int prim = Primitive.createPrimitive(fbb, io.github.dfa1.vortex.fbs.PType.I64, false);
        int off = io.github.dfa1.vortex.fbs.DType.createDType(fbb, Type.Primitive, prim);
        io.github.dfa1.vortex.fbs.DType.finishDTypeBuffer(fbb, off);
        return slice(fbb);
    }

    /// Builds a Footer with the given array/layout spec ids and inline segment specs.
    ///
    /// @param arraySpecs  encoding ids, one per array spec
    /// @param layoutSpecs layout ids, one per layout spec
    /// @param segOffsets  per-segment byte offsets
    /// @param segLengths  per-segment byte lengths
    /// @return the finished Footer FlatBuffer
    static ByteBuffer buildFooter(
            String[] arraySpecs, String[] layoutSpecs,
            long[] segOffsets, long[] segLengths) {
        var fbb = new FlatBufferBuilder(256);

        int[] asOffs = new int[arraySpecs.length];
        for (int i = 0; i < arraySpecs.length; i++) {
            asOffs[i] = ArraySpec.createArraySpec(fbb, fbb.createString(arraySpecs[i]));
        }
        int asv = Footer.createArraySpecsVector(fbb, asOffs);

        int[] lsOffs = new int[layoutSpecs.length];
        for (int i = 0; i < layoutSpecs.length; i++) {
            lsOffs[i] = LayoutSpec.createLayoutSpec(fbb, fbb.createString(layoutSpecs[i]));
        }
        int lsv = Footer.createLayoutSpecsVector(fbb, lsOffs);

        // SegmentSpec is an inline struct — write in reverse order.
        Footer.startSegmentSpecsVector(fbb, segOffsets.length);
        for (int i = segOffsets.length - 1; i >= 0; i--) {
            SegmentSpec.createSegmentSpec(fbb, segOffsets[i], segLengths[i], 6, 0, 0);
        }
        int ssv = fbb.endVector();

        int footOff = Footer.createFooter(fbb, asv, lsv, ssv, 0, 0);
        fbb.finish(footOff);
        return slice(fbb);
    }

    /// Builds a flat Layout referencing a single segment.
    ///
    /// @param layoutSpecIdx index into the footer's layout-spec vector
    /// @param rowCount      declared row count
    /// @param segIdx        index into the footer's segment-spec vector
    /// @return the finished Layout FlatBuffer
    static ByteBuffer buildFlatLayout(int layoutSpecIdx, long rowCount, int segIdx) {
        var fbb = new FlatBufferBuilder(128);
        int segV = Layout.createSegmentsVector(fbb, new long[]{segIdx});
        int layoutOff = Layout.createLayout(fbb, layoutSpecIdx, rowCount, 0, 0, segV);
        Layout.finishLayoutBuffer(fbb, layoutOff);
        return slice(fbb);
    }

    /// Builds a Postscript pointing at the footer, dtype, and layout blobs.
    ///
    /// @param footerOff footer byte offset
    /// @param footerLen footer byte length
    /// @param dtypeOff  dtype byte offset
    /// @param dtypeLen  dtype byte length
    /// @param layoutOff layout byte offset
    /// @param layoutLen layout byte length
    /// @return the finished Postscript FlatBuffer
    static ByteBuffer buildPostscript(
            long footerOff, int footerLen,
            long dtypeOff, int dtypeLen,
            long layoutOff, int layoutLen) {
        var fbb = new FlatBufferBuilder(128);
        int footSeg = PostscriptSegment.createPostscriptSegment(fbb, footerOff, footerLen, 0, 0, 0);
        int dtypeSeg = PostscriptSegment.createPostscriptSegment(fbb, dtypeOff, dtypeLen, 0, 0, 0);
        int layoutSeg = PostscriptSegment.createPostscriptSegment(fbb, layoutOff, layoutLen, 0, 0, 0);
        int psOff = Postscript.createPostscript(fbb, dtypeSeg, layoutSeg, 0, footSeg);
        Postscript.finishPostscriptBuffer(fbb, psOff);
        return slice(fbb);
    }

    /// Slices a finished builder's buffer to its remaining bytes.
    ///
    /// @param fbb a builder whose root has been finished
    /// @return a [ByteBuffer] view of the finished bytes
    static ByteBuffer slice(FlatBufferBuilder fbb) {
        ByteBuffer data = fbb.dataBuffer();
        return data.slice(data.position(), data.remaining());
    }
}
