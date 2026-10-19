package io.github.dfa1.vortex.reader;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.io.IoBounds;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.model.ExtensionId;
import io.github.dfa1.vortex.core.model.LayoutId;
import io.github.dfa1.vortex.core.model.MemorySize;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.fbs.FbsBinary;
import io.github.dfa1.vortex.core.fbs.FbsBool;
import io.github.dfa1.vortex.core.fbs.FbsDecimal;
import io.github.dfa1.vortex.core.fbs.FbsExtension;
import io.github.dfa1.vortex.core.fbs.FbsFixedSizeList;
import io.github.dfa1.vortex.core.fbs.FbsMap;
import io.github.dfa1.vortex.core.fbs.FbsPostscript;
import io.github.dfa1.vortex.core.fbs.FbsPrimitive;
import io.github.dfa1.vortex.core.fbs.FbsStruct;
import io.github.dfa1.vortex.core.fbs.FbsType;
import io.github.dfa1.vortex.core.fbs.FbsUtf8;
import io.github.dfa1.vortex.core.fbs.FbsVariant;
import io.github.dfa1.vortex.reader.layout.Layout;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

final class PostscriptParser {

    /// Hard cap on per-layout metadata size. The FlatBuffer runtime returns an unbounded slice
    /// from `metadataAsSegment()`; a crafted file can claim a multi-gigabyte metadata
    /// blob and force later allocators into pathological behavior. 4 MiB is well above any
    /// real encoding's metadata footprint (the largest is FSST's symbol table at ~32 KiB).
    static final MemorySize MAX_LAYOUT_METADATA_BYTES = MemorySize.ofMiB(4);

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
            Layout layout = convertLayout(fbsLayout, footer.layoutSpecs());

            // Rust opens a file without an embedded dtype only when the caller supplies one
            // (VortexOpenOptions::with_dtype); Java has no such option, so such a file is unreadable.
            if (dtypeBuf == null || dtypeBuf.byteSize() == 0) {
                throw new VortexException("Vortex file doesn't embed a DType");
            }
            DType dtype = convertDType(io.github.dfa1.vortex.core.fbs.FbsDType.getRootAsFbsDType(dtypeBuf));
            checkStructLayouts(layout, dtype);

            return new ParsedFile(footer, dtype, layout);
        } catch (VortexException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new VortexException("malformed footer/layout/dtype blob", e);
        }
    }

    /// Rejects at open a struct layout whose children do not match its struct dtype, as Rust does
    /// while deserializing the layout tree (`Layout::<Struct>::validate_children`): one child per
    /// field, plus a leading validity child when the struct is nullable. Left to the scan, a
    /// mismatch indexed the field list by layout child and escaped as a raw JDK exception.
    /// Recurses only through directly nested struct layouts, whose dtype is the field's own.
    private static void checkStructLayouts(Layout layout, DType dtype) {
        if (!layout.isStruct() || !(dtype instanceof DType.Struct struct)) {
            return;
        }
        int validity = struct.nullable() ? 1 : 0;
        int expected = struct.fieldTypes().size() + validity;
        if (layout.children().size() != expected) {
            throw new VortexException("vortex.struct layout has " + layout.children().size()
                    + " children but its struct dtype needs " + expected);
        }
        for (int i = 0; i < struct.fieldTypes().size(); i++) {
            checkStructLayouts(layout.children().get(validity + i), struct.fieldTypes().get(i));
        }
    }

    private static MemorySegment slice(MemorySegment seg, long offset, long length) {
        return IoBounds.slice(seg, offset, length);
    }

    /// Parses one array-spec wire string to its [EncodingId] at the footer boundary. A blank id
    /// is untrusted-input corruption; [EncodingId#parse] would reject it with an
    /// [IllegalArgumentException], so surface it as a [VortexException] with the spec index.
    ///
    /// @param raw   the wire string from the array spec dictionary
    /// @param index the array spec index, for the error message
    /// @return the typed [EncodingId]
    private static EncodingId parseEncodingSpec(String raw, int index) {
        if (raw.isBlank()) {
            throw new VortexException("blank encoding id at array spec index " + index);
        }
        return EncodingId.parse(raw);
    }

    /// Parses an extension dtype's wire id at the schema boundary, so every [DType.Extension] read
    /// from a file carries an id [ExtensionId#parse] accepts. A missing, blank or control-character
    /// id is untrusted-input corruption that would otherwise surface as an [IllegalArgumentException].
    ///
    /// @param raw the wire string from the extension dtype, or `null` when the field is absent
    /// @return the typed [ExtensionId]
    private static ExtensionId parseExtensionId(String raw) {
        if (raw == null) {
            throw new VortexException("extension dtype missing id");
        }
        try {
            return ExtensionId.parse(raw);
        } catch (IllegalArgumentException ex) {
            throw new VortexException("invalid extension id in file schema: " + ex.getMessage());
        }
    }

    /// Parses one layout-spec wire string to its [LayoutId] at the footer boundary. A blank id
    /// is untrusted-input corruption; [LayoutId#parse] would reject it with an
    /// [IllegalArgumentException], so surface it as a [VortexException] with the spec index.
    ///
    /// @param raw   the wire string from the layout spec dictionary
    /// @param index the layout spec index, for the error message
    /// @return the typed [LayoutId]
    private static LayoutId parseLayoutSpec(String raw, int index) {
        if (raw.isBlank()) {
            throw new VortexException("blank layout id at layout spec index " + index);
        }
        return LayoutId.parse(raw);
    }

    static Footer convertFooter(io.github.dfa1.vortex.core.fbs.FbsFooter f) {
        var arraySpecs = new ArrayList<EncodingId>(f.arraySpecsLength());
        for (int i = 0; i < f.arraySpecsLength(); i++) {
            arraySpecs.add(parseEncodingSpec(f.arraySpecs(i).id(), i));
        }

        var layoutSpecs = new ArrayList<LayoutId>(f.layoutSpecsLength());
        for (int i = 0; i < f.layoutSpecsLength(); i++) {
            layoutSpecs.add(parseLayoutSpec(f.layoutSpecs(i).id(), i));
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

    private static Layout convertLayout(io.github.dfa1.vortex.core.fbs.FbsLayout l, List<LayoutId> layoutSpecs) {
        int encIdx = l.encoding();
        if (encIdx < 0 || encIdx >= layoutSpecs.size()) {
            throw new VortexException(
                    "layout encoding index " + encIdx
                            + " out of bounds (layoutSpecs.size=" + layoutSpecs.size() + ")");
        }
        // Already parsed (and blank-guarded) at the footer boundary.
        LayoutId layoutId = layoutSpecs.get(encIdx);

        MemorySegment metadata = l.metadataAsSegment();
        if (metadata != null && metadata.byteSize() > MAX_LAYOUT_METADATA_BYTES.bytes()) {
            throw new VortexException(
                    "layout metadata size " + metadata.byteSize()
                            + " exceeds limit (" + MAX_LAYOUT_METADATA_BYTES.bytes() + ")");
        }

        var children = new ArrayList<Layout>(l.childrenLength());
        for (int i = 0; i < l.childrenLength(); i++) {
            children.add(convertLayout(l.children(i), layoutSpecs));
        }

        var segments = new ArrayList<Integer>(l.segmentsLength());
        for (int i = 0; i < l.segmentsLength(); i++) {
            segments.add((int) l.segments(i));
        }

        return new Layout(layoutId, l.rowCount(), metadata, List.copyOf(children), List.copyOf(segments));
    }

    private static DType convertDType(io.github.dfa1.vortex.core.fbs.FbsDType fbs) {
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
                // DType.Decimal.of enforces Rust's DecimalDType::try_new bounds as VortexException
                yield DType.Decimal.of(d.precision(), d.scale(), d.nullable());
            }
            case FbsType.FbsUtf8 -> new DType.Utf8(fbs.type(new FbsUtf8()).nullable());
            case FbsType.FbsBinary -> new DType.Binary(fbs.type(new FbsBinary()).nullable());
            case FbsType.FbsStruct -> {
                var s = fbs.type(new FbsStruct());
                // Rust (StructFields::from_fb) requires both vectors; only an empty one is a 0-field struct.
                if (!s.hasNames()) {
                    throw new VortexException("failed to parse struct names from flatbuffer");
                }
                if (!s.hasDtypes()) {
                    throw new VortexException("failed to parse struct dtypes from flatbuffer");
                }
                if (s.namesLength() != s.dtypesLength()) {
                    throw new VortexException("struct names/dtypes length mismatch: "
                            + s.namesLength() + " names, " + s.dtypesLength() + " dtypes");
                }
                var names = new ArrayList<io.github.dfa1.vortex.core.model.ColumnName>(s.namesLength());
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
                    // Blank names are wire-legal and produced by the Rust reference (e.g. an
                    // empty name at field index 0); ColumnName accepts them. Control characters
                    // are rejected on both the read and write paths — wrap as VortexException
                    // here so the caller sees file context rather than a raw IllegalArgumentException.
                    final int fieldIndex = i;
                    io.github.dfa1.vortex.core.model.ColumnName.violation(name).ifPresent(reason -> {
                        throw new io.github.dfa1.vortex.core.error.VortexException(
                                "invalid field name at field index " + fieldIndex + " in file schema: " + reason);
                    });
                    names.add(new io.github.dfa1.vortex.core.model.ColumnName(name));
                }
                for (int i = 0; i < s.dtypesLength(); i++) {
                    types.add(convertDType(s.dtypes(i)));
                }
                yield new DType.Struct(List.copyOf(names), List.copyOf(types), s.nullable());
            }
            case FbsType.FbsList -> {
                var l = fbs.type(new io.github.dfa1.vortex.core.fbs.FbsList());
                yield new DType.List(convertDType(l.elementType()), l.nullable());
            }
            case FbsType.FbsFixedSizeList -> {
                var fsl = fbs.type(new FbsFixedSizeList());
                yield new DType.FixedSizeList(convertDType(fsl.elementType()), (int) fsl.size(), fsl.nullable());
            }
            case FbsType.FbsMap -> {
                var m = fbs.type(new FbsMap());
                // Both child dtypes are mandatory; a crafted table can omit either field, which
                // the generated reader surfaces as null.
                if (m.keyType() == null || m.valueType() == null) {
                    throw new VortexException("map dtype missing key_type or value_type");
                }
                DType keyType = convertDType(m.keyType());
                DType valueType = convertDType(m.valueType());
                // A nullable key type is rejected by DType.Map's own constructor, which raises
                // VortexException — the security contract holds without a second check here.
                yield new DType.Map(keyType, valueType, m.keysSorted(), m.nullable());
            }
            case FbsType.FbsExtension -> {
                var e = fbs.type(new FbsExtension());
                DType storage = convertDType(e.storageDtype());
                // Rust requires the field, empty when there is no metadata
                MemorySegment metadata = e.metadataAsSegment();
                if (metadata == null) {
                    throw new VortexException("extension dtype missing metadata");
                }
                yield new DType.Extension(
                        parseExtensionId(e.id()).id(),
                        storage,
                        metadata,
                        storage.nullable());
            }
            case FbsType.FbsVariant -> new DType.Variant(fbs.type(new FbsVariant()).nullable());
            case FbsType.FbsUnion -> {
                var u = fbs.type(new io.github.dfa1.vortex.core.fbs.FbsUnion());
                var names = new ArrayList<io.github.dfa1.vortex.core.model.ColumnName>(u.namesLength());
                for (int i = 0; i < u.namesLength(); i++) {
                    String name = u.names(i);
                    final int variantIndex = i;
                    io.github.dfa1.vortex.core.model.ColumnName.violation(name).ifPresent(reason -> {
                        throw new VortexException("invalid union variant name at index " + variantIndex
                                + " in file schema: " + reason);
                    });
                    names.add(new io.github.dfa1.vortex.core.model.ColumnName(name));
                }
                var types = new ArrayList<DType>(u.dtypesLength());
                for (int i = 0; i < u.dtypesLength(); i++) {
                    types.add(convertDType(u.dtypes(i)));
                }
                var typeIds = new ArrayList<Integer>(u.typeIdsLength());
                for (int i = 0; i < u.typeIdsLength(); i++) {
                    // Unsigned on the wire: FlatBuffers' `byte` is signed, Rust reads it as u8.
                    typeIds.add(u.typeIds(i) & 0xFF);
                }
                // DType.Union validates the shape (lengths, 1..256 variants, distinct ids/names)
                // and throws VortexException, so a crafted schema never escapes as anything else.
                yield new DType.Union(names, types, typeIds, u.nullable());
            }
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
