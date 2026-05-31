package io.github.dfa1.vortex.io;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.Footer;
import io.github.dfa1.vortex.core.Layout;
import io.github.dfa1.vortex.core.SegmentSpec;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/// Produces a human-readable summary of a Vortex file's structure and encodings.
public final class VortexInspector {

	private VortexInspector() {
	}

	public static String inspect(VortexHandle reader) {
		Footer footer = reader.footer();
		Layout layout = reader.layout();
		DType dtype = reader.dtype();

		var sb = new StringBuilder();

		sb.append("Vortex v").append(reader.version())
				.append("  ").append(formatBytes(reader.fileSize())).append('\n');
		sb.append('\n');

		sb.append("Schema:\n");
		appendSchema(sb, dtype, "  ");
		sb.append('\n');

		sb.append("Registered encodings: ").append(String.join(", ", footer.arraySpecs())).append('\n');
		sb.append('\n');

		Set<String> usedEncodings = collectUsedEncodings(reader);
		sb.append("Used encodings: ").append(String.join(", ", usedEncodings)).append('\n');
		sb.append('\n');

		int segCount = footer.segmentSpecs().size();
		long totalBytes = footer.segmentSpecs().stream().mapToLong(SegmentSpec::length).sum();
		sb.append("Segments: ").append(segCount)
				.append("  total ").append(formatBytes(totalBytes)).append('\n');
		sb.append('\n');

		sb.append("Layout:\n");
		List<String> colNames = (dtype instanceof DType.Struct s) ? s.fieldNames() : List.of();
		appendLayout(sb, layout, colNames, reader, "  ");

		return sb.toString();
	}

	// ── Used encodings ────────────────────────────────────────────────────────

	private static Set<String> collectUsedEncodings(VortexHandle reader) {
		var used = new LinkedHashSet<String>();
		collectLayoutEncodings(reader.layout(), reader, used);
		return used;
	}

	private static void collectLayoutEncodings(Layout layout, VortexHandle reader, Set<String> used) {
		if (layout.isFlat() && !layout.segments().isEmpty()) {
			int segIdx = layout.segments().getFirst();
			SegmentSpec spec = reader.footer().segmentSpecs().get(segIdx);
			if (spec.compression().code == 0) {
				MemorySegment seg = reader.slice(spec.offset(), spec.length());
				peekRootEncoding(seg, reader.footer().arraySpecs(), used);
			}
		}
		for (Layout child : layout.children()) {
			collectLayoutEncodings(child, reader, used);
		}
	}

	/// Reads only the root ArrayNode encoding — ignores child/stats sub-nodes.
	private static void peekRootEncoding(MemorySegment seg, List<String> arraySpecs, Set<String> used) {
		int segLen = (int) seg.byteSize();
		ByteBuffer bb = seg.asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
		int fbLen = bb.getInt(segLen - 4);
		int fbStart = segLen - 4 - fbLen;
		ByteBuffer fbBuf = bb.slice(fbStart, fbLen).order(ByteOrder.LITTLE_ENDIAN);
		var fbArray = io.github.dfa1.vortex.fbs.Array.getRootAsArray(fbBuf);
		if (fbArray.root() != null) {
			used.add(arraySpecs.get(fbArray.root().encoding()));
		}
	}

	// ── Layout tree ───────────────────────────────────────────────────────────

	@SuppressWarnings("SameParameterValue")
	private static void appendLayout(StringBuilder sb, Layout layout, List<String> colNames,
	                                 VortexHandle reader, String indent) {
		if (layout.isStruct()) {
			sb.append(indent).append("struct (").append(layout.rowCount()).append(" rows)\n");
			for (int i = 0; i < layout.children().size(); i++) {
				String name = i < colNames.size() ? colNames.get(i) : "col" + i;
				Set<String> colEncodings = new LinkedHashSet<>();
				collectLayoutEncodings(layout.children().get(i), reader, colEncodings);
				sb.append(indent).append("  ").append(name).append(": ");
				appendLayoutInline(sb, layout.children().get(i));
				if (!colEncodings.isEmpty()) {
					sb.append("  [").append(String.join(", ", colEncodings)).append("]");
				}
				sb.append('\n');
			}
		} else {
			sb.append(indent);
			appendLayoutInline(sb, layout);
			sb.append('\n');
		}
	}

	private static void appendLayoutInline(StringBuilder sb, Layout layout) {
		sb.append(layout.encodingId()).append('(').append(layout.rowCount()).append(" rows)");
		if (layout.children().isEmpty()) {
			return;
		}
		sb.append(" → ");
		if (layout.children().size() == 1) {
			appendLayoutInline(sb, layout.children().getFirst());
		} else {
			sb.append(layout.children().size()).append("× [");
			appendLayoutInline(sb, layout.children().getFirst());
			sb.append("]");
		}
	}

	// ── Formatting ────────────────────────────────────────────────────────────

	@SuppressWarnings("SameParameterValue")
	private static void appendSchema(StringBuilder sb, DType dtype, String indent) {
		if (dtype instanceof DType.Struct s) {
			int maxLen = s.fieldNames().stream().mapToInt(String::length).max().orElse(0);
			for (int i = 0; i < s.fieldNames().size(); i++) {
				String name = s.fieldNames().get(i);
				sb.append(indent).append(name)
						.append(" ".repeat(maxLen - name.length() + 1))
						.append(formatDType(s.fieldTypes().get(i))).append('\n');
			}
		} else {
			sb.append(indent).append(formatDType(dtype)).append('\n');
		}
	}

	private static String formatDType(DType dtype) {
		return switch (dtype) {
			case DType.Primitive(var pt, var nullable) -> pt.name() + (nullable ? "?" : "");
			case DType.Utf8(var nullable) -> "utf8" + (nullable ? "?" : "");
			case DType.Binary(var nullable) -> "binary" + (nullable ? "?" : "");
			case DType.Bool(var nullable) -> "bool" + (nullable ? "?" : "");
			case DType.Null ignored -> "null";
			case DType.Decimal(var p, var s, var nullable) -> "decimal(" + p + "," + s + ")" + (nullable ? "?" : "");
			case DType.Struct ignored -> "struct";
			case DType.List(var elem, var nullable) -> "list<" + formatDType(elem) + ">" + (nullable ? "?" : "");
			case DType.FixedSizeList(var elem, var size, var nullable) ->
					"list<" + formatDType(elem) + ">[" + size + "]" + (nullable ? "?" : "");
			case DType.Extension(var id, var storage, var meta, var nullable) ->
					"ext<" + id + ">" + (nullable ? "?" : "");
		};
	}

	private static String formatBytes(long bytes) {
		if (bytes < 1024) {
			return bytes + " B";
		}
		if (bytes < 1024 * 1024) {
			return String.format("%.1f KB", bytes / 1024.0);
		}
		return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
	}
}
