package io.github.dfa1.vortex.reader;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.io.IoBounds;
import io.github.dfa1.vortex.core.model.LayoutId;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.fbs.FbsBinary;
import io.github.dfa1.vortex.core.fbs.FbsBool;
import io.github.dfa1.vortex.core.fbs.FbsDecimal;
import io.github.dfa1.vortex.core.fbs.FbsExtension;
import io.github.dfa1.vortex.core.fbs.FbsFixedSizeList;
import io.github.dfa1.vortex.core.fbs.FbsPostscript;
import io.github.dfa1.vortex.core.fbs.FbsPrimitive;
import io.github.dfa1.vortex.core.fbs.FbsStruct_;
import io.github.dfa1.vortex.core.fbs.FbsType;
import io.github.dfa1.vortex.core.fbs.FbsUtf8;
import io.github.dfa1.vortex.core.fbs.FbsVariant;
import io.github.dfa1.vortex.reader.layout.Layout;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

final class PostscriptParser {

    /// Hard cap on layout-tree recursion depth. Real-world layouts are typically four levels
    /// (Struct → Zoned → Chunked → Flat); 64 is well past any expected schema and prevents
    /// adversarial inputs — deeply nested trees or self-referential FlatBuffer cycles — from
    /// blowing the JVM stack during [#convertLayout(io.github.dfa1.vortex.core.fbs.FbsLayout, List, int)].
    static final int MAX_LAYOUT_DEPTH = 64;

    /// Hard cap on per-layout metadata size. The FlatBuffer runtime returns an unbounded slice
    /// from `metadataAsSegment()`; a crafted file can claim a multi-gigabyte metadata
    /// blob and force later allocators into pathological behavior. 4 MiB is well above any
    /// real encoding's metadata footprint (the largest is FSST's symbol table at ~32 KiB).
    static final int MAX_LAYOUT_METADATA_BYTES = 4 * 1024 * 1024;

    /// Hard cap on DType-tree recursion depth. A `DType` nests through Struct fields, List/
    /// FixedSizeList element types, and Extension storage types; like the layout tree, a crafted
    /// or self-referential FlatBuffer can drive [#convertDType(io.github.dfa1.vortex.core.fbs.FbsDType, int)]
    /// into unbounded recursion and a [StackOverflowError] — which, being an `Error`, would escape
    /// the [VortexException] sanitization and leak the reader's memory-mapped Arena. 64 is well past
    /// any real schema's nesting.
    static final int MAX_DTYPE_DEPTH = 64;

    private PostscriptParser() {
    }

    static ParsedFile parse(MemorySegment postscriptSeg, MemorySegment fileSegment, long fileSize) {
        var ps = FbsPostscript.getRootAsFbsPostscript(postscriptSeg);

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

        MemorySegment footerBuf = slice(fileSegment, footerSeg.offset(), footerSeg.length());
        MemorySegment layoutBuf = slice(fileSegment, layoutSeg.offset(), layoutSeg.length());
        MemorySegment dtypeBuf = (dtypeSeg != null && dtypeSeg.length() > 0)
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
        // Overflow-safe containment in [0, fileSize], keeping the blob-named message that
        // IoBounds.checkRange's generic text would lose. Two clauses checkRange carries are
        // omitted because they are unreachable here: every caller passes a u32-masked
        // PostscriptSegment.length() (always >= 0, so no `length < 0` check), which in turn makes
        // an `offset > fileSize` check redundant — it is already implied by the final comparison.
        if (offset < 0 || length > fileSize - offset) {
            throw new VortexException(
                    "postscript " + name + " blob out of bounds: offset=" + offset
                            + " length=" + length + " fileSize=" + fileSize);
        }
    }

    static ParsedFile parseBlobs(MemorySegment footerBuf, MemorySegment layoutBuf, MemorySegment dtypeBuf) {
        try {
            var fbsFooter = io.github.dfa1.vortex.core.fbs.FbsFooter.getRootAsFbsFooter(footerBuf);
            var fbsLayout = io.github.dfa1.vortex.core.fbs.FbsLayout.getRootAsFbsLayout(layoutBuf);

            Footer footer = convertFooter(fbsFooter);
            Layout layout = convertLayout(fbsLayout, footer.layoutSpecs(), 0);

            DType dtype = null;
            if (dtypeBuf != null && dtypeBuf.byteSize() > 0) {
                dtype = convertDType(io.github.dfa1.vortex.core.fbs.FbsDType.getRootAsFbsDType(dtypeBuf), 0);
            }

            return new ParsedFile(footer, dtype, layout);
        } catch (VortexException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new VortexException("malformed footer/layout/dtype blob", e);
        }
    }

    private static MemorySegment slice(MemorySegment seg, long offset, long length) {
        return IoBounds.slice(seg, offset, length);
    }

    static Footer convertFooter(io.github.dfa1.vortex.core.fbs.FbsFooter f) {
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
                    CompressionScheme.of(s.compression())));
        }

        var compressionSpecs = new ArrayList<CompressionScheme>(f.compressionSpecsLength());
        for (int i = 0; i < f.compressionSpecsLength(); i++) {
            compressionSpecs.add(CompressionScheme.of(f.compressionSpecs(i).scheme()));
        }

        return new Footer(
                List.copyOf(arraySpecs), List.copyOf(layoutSpecs),
                List.copyOf(segmentSpecs), List.copyOf(compressionSpecs));
    }

    private static Layout convertLayout(io.github.dfa1.vortex.core.fbs.FbsLayout l, List<String> layoutSpecs, int depth) {
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
        String rawLayoutId = layoutSpecs.get(encIdx);
        if (rawLayoutId.isBlank()) {
            // LayoutId.parse rejects blank ids with IllegalArgumentException; the file is
            // untrusted input, so a blank spec entry must surface as VortexException instead.
            throw new VortexException("blank layout id at layout spec index " + encIdx);
        }
        LayoutId layoutId = LayoutId.parse(rawLayoutId);

        MemorySegment metadata = l.metadataAsSegment();
        if (metadata != null && metadata.byteSize() > MAX_LAYOUT_METADATA_BYTES) {
            throw new VortexException(
                    "layout metadata size " + metadata.byteSize()
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

        return new Layout(layoutId, l.rowCount(), metadata, List.copyOf(children), List.copyOf(segments));
    }

    private static DType convertDType(io.github.dfa1.vortex.core.fbs.FbsDType fbs, int depth) {
        if (depth > MAX_DTYPE_DEPTH) {
            throw new VortexException(
                    "DType tree depth exceeds limit (" + MAX_DTYPE_DEPTH + ")");
        }
        int typeType = fbs.typeType();
        return switch (typeType) {
            case FbsType.FbsNull -> new DType.Null(true);
            case FbsType.FbsBool -> new DType.Bool(fbs.type(new FbsBool()).nullable());
            case FbsType.FbsPrimitive -> {
                var p = fbs.type(new FbsPrimitive());
                yield new DType.Primitive(convertPType(p.ptype()), p.nullable());
            }
            case FbsType.FbsDecimal -> {
                var d = fbs.type(new FbsDecimal());
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
            case FbsType.FbsUtf8 -> new DType.Utf8(fbs.type(new FbsUtf8()).nullable());
            case FbsType.FbsBinary -> new DType.Binary(fbs.type(new FbsBinary()).nullable());
            case FbsType.FbsStruct_ -> {
                var s = fbs.type(new FbsStruct_());
                if (s.namesLength() != s.dtypesLength()) {
                    throw new VortexException("struct names/dtypes length mismatch: "
                            + s.namesLength() + " names, " + s.dtypesLength() + " dtypes");
                }
                var names = new ArrayList<String>(s.namesLength());
                var types = new ArrayList<DType>(s.dtypesLength());
                var seen = new HashSet<String>();
                for (int i = 0; i < s.namesLength(); i++) {
                    String name = s.names(i);
                    if (!seen.add(name)) {
                        // Wire contract, enforced by the reference writer ("StructLayout must
                        // have unique field names"): a duplicate here is a crafted or corrupt
                        // file, and the name-keyed Chunk API would silently drop a column.
                        throw new VortexException("duplicate field name in file schema: " + name);
                    }
                    // Same strict name policy as the write side (ColumnName is the single
                    // source of truth): blank and control-character names are wire-legal but
                    // almost certainly a bug in the producing pipeline — reject with a message
                    // that says so rather than propagate unusable names into name-keyed APIs
                    // and SQL identifiers.
                    final int fieldIndex = i;
                    io.github.dfa1.vortex.core.model.ColumnName.violation(name).ifPresent(reason -> {
                        throw new VortexException("invalid field name in file schema (field index "
                                + fieldIndex + "): " + reason
                                + " — likely a bug in the pipeline that produced this file");
                    });
                    names.add(name);
                }
                for (int i = 0; i < s.dtypesLength(); i++) {
                    types.add(convertDType(s.dtypes(i), depth + 1));
                }
                yield new DType.Struct(List.copyOf(names), List.copyOf(types), s.nullable());
            }
            case FbsType.FbsList -> {
                var l = fbs.type(new io.github.dfa1.vortex.core.fbs.FbsList());
                yield new DType.List(convertDType(l.elementType(), depth + 1), l.nullable());
            }
            case FbsType.FbsFixedSizeList -> {
                var fsl = fbs.type(new FbsFixedSizeList());
                yield new DType.FixedSizeList(convertDType(fsl.elementType(), depth + 1), (int) fsl.size(), fsl.nullable());
            }
            case FbsType.FbsExtension -> {
                var e = fbs.type(new FbsExtension());
                DType storage = convertDType(e.storageDtype(), depth + 1);
                yield new DType.Extension(
                        e.id(),
                        storage,
                        e.metadataAsSegment(),
                        storage.nullable());
            }
            case FbsType.FbsVariant -> new DType.Variant(fbs.type(new FbsVariant()).nullable());
            default -> throw new VortexException("unsupported DType typeType=" + typeType);
        };
    }

    private static PType convertPType(int fbsPType) {
        return switch (fbsPType) {
            case io.github.dfa1.vortex.core.fbs.FbsPType.U8 -> PType.U8;
            case io.github.dfa1.vortex.core.fbs.FbsPType.U16 -> PType.U16;
            case io.github.dfa1.vortex.core.fbs.FbsPType.U32 -> PType.U32;
            case io.github.dfa1.vortex.core.fbs.FbsPType.U64 -> PType.U64;
            case io.github.dfa1.vortex.core.fbs.FbsPType.I8 -> PType.I8;
            case io.github.dfa1.vortex.core.fbs.FbsPType.I16 -> PType.I16;
            case io.github.dfa1.vortex.core.fbs.FbsPType.I32 -> PType.I32;
            case io.github.dfa1.vortex.core.fbs.FbsPType.I64 -> PType.I64;
            case io.github.dfa1.vortex.core.fbs.FbsPType.F16 -> PType.F16;
            case io.github.dfa1.vortex.core.fbs.FbsPType.F32 -> PType.F32;
            case io.github.dfa1.vortex.core.fbs.FbsPType.F64 -> PType.F64;
            default -> throw new VortexException("unrecognized PType=" + fbsPType);
        };
    }

    record ParsedFile(Footer footer, DType dtype, Layout layout) {
    }
}
