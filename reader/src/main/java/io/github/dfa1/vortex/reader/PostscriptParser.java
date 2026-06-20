package io.github.dfa1.vortex.reader;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.IoBounds;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.fbs.Binary;
import io.github.dfa1.vortex.fbs.Bool;
import io.github.dfa1.vortex.fbs.Decimal;
import io.github.dfa1.vortex.fbs.Extension;
import io.github.dfa1.vortex.fbs.FixedSizeList;
import io.github.dfa1.vortex.fbs.Postscript;
import io.github.dfa1.vortex.fbs.Primitive;
import io.github.dfa1.vortex.fbs.Struct_;
import io.github.dfa1.vortex.fbs.Type;
import io.github.dfa1.vortex.fbs.Utf8;
import io.github.dfa1.vortex.fbs.Variant;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

final class PostscriptParser {

    /// Hard cap on layout-tree recursion depth. Real-world layouts are typically four levels
    /// (Struct → Zoned → Chunked → Flat); 64 is well past any expected schema and prevents
    /// adversarial inputs — deeply nested trees or self-referential FlatBuffer cycles — from
    /// blowing the JVM stack during [#convertLayout(io.github.dfa1.vortex.fbs.Layout, List, int)].
    static final int MAX_LAYOUT_DEPTH = 64;

    /// Hard cap on per-layout metadata size. The FlatBuffer runtime returns an unbounded slice
    /// from `metadataAsByteBuffer()`; a crafted file can claim a multi-gigabyte metadata
    /// blob and force later allocators into pathological behaviour. 4 MiB is well above any
    /// real encoding's metadata footprint (the largest is FSST's symbol table at ~32 KiB).
    static final int MAX_LAYOUT_METADATA_BYTES = 4 * 1024 * 1024;

    private PostscriptParser() {
    }

    static ParsedFile parse(ByteBuffer postscriptBuf, MemorySegment fileSegment, long fileSize) {
        var ps = Postscript.getRootAsPostscript(postscriptBuf);

        var footerSeg = ps.footer();
        if (footerSeg == null) {
            throw new VortexException("postscript missing footer segment");
        }
        var layoutSeg = ps.layout();
        if (layoutSeg == null) {
            throw new VortexException("postscript missing layout segment");
        }
        var dtypeSeg = ps.dtype();

        checkBlobBounds("footer", footerSeg.offset(), footerSeg.length(), fileSize);
        checkBlobBounds("layout", layoutSeg.offset(), layoutSeg.length(), fileSize);
        if (dtypeSeg != null && dtypeSeg.length() > 0) {
            checkBlobBounds("dtype", dtypeSeg.offset(), dtypeSeg.length(), fileSize);
        }

        ByteBuffer footerBuf = slice(fileSegment, footerSeg.offset(), footerSeg.length());
        ByteBuffer layoutBuf = slice(fileSegment, layoutSeg.offset(), layoutSeg.length());
        ByteBuffer dtypeBuf = (dtypeSeg != null && dtypeSeg.length() > 0)
                                      ? slice(fileSegment, dtypeSeg.offset(), dtypeSeg.length())
                                      : null;

        ParsedFile parsed = parseBlobs(footerBuf, layoutBuf, dtypeBuf);
        validateSegmentSpecs(parsed.footer().segmentSpecs(), fileSize);
        return parsed;
    }

    /// Rejects [SegmentSpec] entries whose declared range is not entirely contained in the
    /// memory-mapped file. Without this check, every scan-time `fileSegment.asSlice(offset,
    /// length)` on these specs would throw [IndexOutOfBoundsException], breaking the
    /// "malformed input → [VortexException]" contract.
    static void validateSegmentSpecs(List<SegmentSpec> specs, long fileSize) {
        for (int i = 0; i < specs.size(); i++) {
            SegmentSpec s = specs.get(i);
            long offset = s.offset();
            long length = s.length();
            // Overflow-safe containment in [0, fileSize], same shape as IoBounds.checkRange. An
            // `offset > fileSize` clause would be redundant: with length >= 0 already guaranteed,
            // offset > fileSize forces length > fileSize - offset, so the final clause covers it.
            if (offset < 0 || length < 0 || length > fileSize - offset) {
                throw new VortexException(
                        "footer segmentSpecs[" + i + "] out of bounds: offset=" + offset
                                + " length=" + length + " fileSize=" + fileSize);
            }
        }
    }

    private static void checkBlobBounds(String name, long offset, long length, long fileSize) {
        // Same overflow-safe range form as IoBounds.checkRange (no redundant `offset > fileSize`
        // clause: length >= 0 makes it implied by the final comparison). Keeps the blob-named
        // message that checkRange's generic text would lose.
        if (offset < 0 || length < 0 || length > fileSize - offset) {
            throw new VortexException(
                    "postscript " + name + " blob out of bounds: offset=" + offset
                            + " length=" + length + " fileSize=" + fileSize);
        }
    }

    static ParsedFile parseBlobs(ByteBuffer footerBuf, ByteBuffer layoutBuf, ByteBuffer dtypeBuf) {
        try {
            var fbsFooter = io.github.dfa1.vortex.fbs.Footer.getRootAsFooter(footerBuf);
            var fbsLayout = io.github.dfa1.vortex.fbs.Layout.getRootAsLayout(layoutBuf);

            Footer footer = convertFooter(fbsFooter);
            Layout layout = convertLayout(fbsLayout, footer.layoutSpecs(), 0);

            DType dtype = null;
            if (dtypeBuf != null && dtypeBuf.hasRemaining()) {
                dtype = convertDType(io.github.dfa1.vortex.fbs.DType.getRootAsDType(dtypeBuf));
            }

            return new ParsedFile(footer, dtype, layout);
        } catch (VortexException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new VortexException("malformed footer/layout/dtype blob", e);
        }
    }

    private static ByteBuffer slice(MemorySegment seg, long offset, long length) {
        return IoBounds.slice(seg, offset, length).asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
    }

    static Footer convertFooter(io.github.dfa1.vortex.fbs.Footer f) {
        var arraySpecs = new ArrayList<String>(f.arraySpecsLength());
        for (int i = 0; i < f.arraySpecsLength(); i++) {
            arraySpecs.add(f.arraySpecs(i).id());
        }

        var layoutSpecs = new ArrayList<String>(f.layoutSpecsLength());
        for (int i = 0; i < f.layoutSpecsLength(); i++) {
            layoutSpecs.add(f.layoutSpecs(i).id());
        }

        var segmentSpecs = new ArrayList<SegmentSpec>(f.segmentSpecsLength());
        for (int i = 0; i < f.segmentSpecsLength(); i++) {
            var s = f.segmentSpecs(i);
            segmentSpecs.add(new SegmentSpec(
                    s.offset(), s.length(),
                    (byte) s.alignmentExponent(),
                    CompressionScheme.of(s._Compression())));
        }

        var compressionSpecs = new ArrayList<CompressionScheme>(f.compressionSpecsLength());
        for (int i = 0; i < f.compressionSpecsLength(); i++) {
            compressionSpecs.add(CompressionScheme.of(f.compressionSpecs(i).scheme()));
        }

        return new Footer(
                List.copyOf(arraySpecs), List.copyOf(layoutSpecs),
                List.copyOf(segmentSpecs), List.copyOf(compressionSpecs));
    }

    private static Layout convertLayout(io.github.dfa1.vortex.fbs.Layout l, List<String> layoutSpecs, int depth) {
        if (depth > MAX_LAYOUT_DEPTH) {
            throw new VortexException(
                    "layout tree depth exceeds limit (" + MAX_LAYOUT_DEPTH + ")");
        }
        int encIdx = l.encoding();
        if (encIdx < 0 || encIdx >= layoutSpecs.size()) {
            throw new VortexException(
                    "layout encoding index " + encIdx
                            + " out of bounds (layoutSpecs.size=" + layoutSpecs.size() + ")");
        }
        String encodingId = layoutSpecs.get(encIdx);

        ByteBuffer metadata = l.metadataAsByteBuffer();
        if (metadata != null && metadata.remaining() > MAX_LAYOUT_METADATA_BYTES) {
            throw new VortexException(
                    "layout metadata size " + metadata.remaining()
                            + " exceeds limit (" + MAX_LAYOUT_METADATA_BYTES + ")");
        }

        var children = new ArrayList<Layout>(l.childrenLength());
        for (int i = 0; i < l.childrenLength(); i++) {
            children.add(convertLayout(l.children(i), layoutSpecs, depth + 1));
        }

        var segments = new ArrayList<Integer>(l.segmentsLength());
        for (int i = 0; i < l.segmentsLength(); i++) {
            segments.add((int) l.segments(i));
        }

        return new Layout(encodingId, l.rowCount(), metadata, List.copyOf(children), List.copyOf(segments));
    }

    private static DType convertDType(io.github.dfa1.vortex.fbs.DType fbs) {
        byte typeType = fbs.typeType();
        return switch (typeType) {
            case Type.Null -> new DType.Null(true);
            case Type.Bool -> new DType.Bool(((Bool) fbs.type(new Bool())).nullable());
            case Type.Primitive -> {
                var p = (Primitive) fbs.type(new Primitive());
                yield new DType.Primitive(convertPType(p.ptype()), p.nullable());
            }
            case Type.Decimal -> {
                var d = (Decimal) fbs.type(new Decimal());
                int precision = d.precision();
                int scale = d.scale();
                // IEEE 754-2008 decimal128 covers precision up to 38 digits; scale must be in
                // [0, precision]. Reject crafted values up front rather than letting a downstream
                // BigDecimal/byte-width calculation fail with an unrelated exception.
                if (precision < 1 || precision > 38) {
                    throw new VortexException(
                            "decimal precision " + precision + " out of range (expected 1..38)");
                }
                if (scale < 0 || scale > precision) {
                    throw new VortexException(
                            "decimal scale " + scale + " out of range (expected 0.." + precision + ")");
                }
                yield new DType.Decimal((byte) precision, (byte) scale, d.nullable());
            }
            case Type.Utf8 -> new DType.Utf8(((Utf8) fbs.type(new Utf8())).nullable());
            case Type.Binary -> new DType.Binary(((Binary) fbs.type(new Binary())).nullable());
            case Type.Struct_ -> {
                var s = (Struct_) fbs.type(new Struct_());
                var names = new ArrayList<String>(s.namesLength());
                var types = new ArrayList<DType>(s.dtypesLength());
                for (int i = 0; i < s.namesLength(); i++) {
                    names.add(s.names(i));
                }
                for (int i = 0; i < s.dtypesLength(); i++) {
                    types.add(convertDType(s.dtypes(new io.github.dfa1.vortex.fbs.DType(), i)));
                }
                yield new DType.Struct(List.copyOf(names), List.copyOf(types), s.nullable());
            }
            case Type.List -> {
                var l = (io.github.dfa1.vortex.fbs.List) fbs.type(new io.github.dfa1.vortex.fbs.List());
                yield new DType.List(
                        convertDType(l.elementType(new io.github.dfa1.vortex.fbs.DType())),
                        l.nullable());
            }
            case Type.FixedSizeList -> {
                var fsl = (FixedSizeList) fbs.type(new FixedSizeList());
                yield new DType.FixedSizeList(
                        convertDType(fsl.elementType(new io.github.dfa1.vortex.fbs.DType())),
                        (int) fsl.size(), fsl.nullable());
            }
            case Type.Extension -> {
                var e = (Extension) fbs.type(new Extension());
                DType storage = convertDType(e.storageDtype(new io.github.dfa1.vortex.fbs.DType()));
                yield new DType.Extension(
                        e.id(),
                        storage,
                        e.metadataAsByteBuffer(),
                        storage.nullable());
            }
            case Type.Variant -> new DType.Variant(((Variant) fbs.type(new Variant())).nullable());
            default -> throw new VortexException("unsupported DType typeType=" + typeType);
        };
    }

    private static PType convertPType(int fbsPType) {
        return switch (fbsPType) {
            case io.github.dfa1.vortex.fbs.PType.U8 -> PType.U8;
            case io.github.dfa1.vortex.fbs.PType.U16 -> PType.U16;
            case io.github.dfa1.vortex.fbs.PType.U32 -> PType.U32;
            case io.github.dfa1.vortex.fbs.PType.U64 -> PType.U64;
            case io.github.dfa1.vortex.fbs.PType.I8 -> PType.I8;
            case io.github.dfa1.vortex.fbs.PType.I16 -> PType.I16;
            case io.github.dfa1.vortex.fbs.PType.I32 -> PType.I32;
            case io.github.dfa1.vortex.fbs.PType.I64 -> PType.I64;
            case io.github.dfa1.vortex.fbs.PType.F16 -> PType.F16;
            case io.github.dfa1.vortex.fbs.PType.F32 -> PType.F32;
            case io.github.dfa1.vortex.fbs.PType.F64 -> PType.F64;
            default -> throw new VortexException("unrecognized PType=" + fbsPType);
        };
    }

    record ParsedFile(Footer footer, DType dtype, Layout layout) {
    }
}
