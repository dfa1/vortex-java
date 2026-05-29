package io.github.dfa1.vortex.io;

import io.github.dfa1.vortex.core.CompressionScheme;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.Footer;
import io.github.dfa1.vortex.core.Layout;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.SegmentSpec;
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

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

final class PostscriptParser {

	private PostscriptParser() {
	}

	static ParsedFile parse(ByteBuffer postscriptBuf, MemorySegment fileSegment) throws IOException {
		var ps = Postscript.getRootAsPostscript(postscriptBuf);

		var footerSeg = ps.footer();
		if (footerSeg == null) {
			throw new IOException("vortex: postscript missing footer segment");
		}
		var fbsFooter = io.github.dfa1.vortex.fbs.Footer.getRootAsFooter(
				slice(fileSegment, footerSeg.offset(), footerSeg.length()));

		var layoutSeg = ps.layout();
		if (layoutSeg == null) {
			throw new IOException("vortex: postscript missing layout segment");
		}
		var fbsLayout = io.github.dfa1.vortex.fbs.Layout.getRootAsLayout(
				slice(fileSegment, layoutSeg.offset(), layoutSeg.length()));

		DType dtype = null;
		var dtypeSeg = ps.dtype();
		if (dtypeSeg != null && dtypeSeg.length() > 0) {
			var fbsDtype = io.github.dfa1.vortex.fbs.DType.getRootAsDType(
					slice(fileSegment, dtypeSeg.offset(), dtypeSeg.length()));
			dtype = convertDType(fbsDtype);
		}

		var footer = convertFooter(fbsFooter);
		var layout = convertLayout(fbsLayout, footer.layoutSpecs());
		return new ParsedFile(footer, dtype, layout);
	}

	private static ByteBuffer slice(MemorySegment seg, long offset, long length) {
		return seg.asSlice(offset, length).asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
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

	private static Layout convertLayout(io.github.dfa1.vortex.fbs.Layout l, List<String> layoutSpecs) {
		String encodingId = layoutSpecs.get(l.encoding());

		ByteBuffer metadata = l.metadataAsByteBuffer();

		var children = new ArrayList<Layout>(l.childrenLength());
		for (int i = 0; i < l.childrenLength(); i++) {
			children.add(convertLayout(l.children(i), layoutSpecs));
		}

		var segments = new ArrayList<Integer>(l.segmentsLength());
		for (int i = 0; i < l.segmentsLength(); i++) {
			segments.add((int) l.segments(i));
		}

		return new Layout(encodingId, l.rowCount(), metadata, List.copyOf(children), List.copyOf(segments));
	}

	private static DType convertDType(io.github.dfa1.vortex.fbs.DType fbs) throws IOException {
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
				yield new DType.Decimal((byte) d.precision(), d.scale(), d.nullable());
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
				yield new DType.Extension(
						e.id(),
						convertDType(e.storageDtype(new io.github.dfa1.vortex.fbs.DType())),
						e.metadataAsByteBuffer(), false);
			}
			default -> throw new IOException("vortex: unsupported DType typeType=" + typeType);
		};
	}

	private static PType convertPType(int fbsPType) throws IOException {
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
			default -> throw new IOException("vortex: unrecognized PType=" + fbsPType);
		};
	}

	record ParsedFile(Footer footer, DType dtype, Layout layout) {
	}
}
