package io.github.dfa1.vortex.scan;

import com.google.protobuf.InvalidProtocolBufferException;
import dev.vortex.proto.EncodingProtos;
import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.array.StructArray;
import io.github.dfa1.vortex.core.ArrayStats;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.Layout;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.SegmentSpec;
import io.github.dfa1.vortex.core.array.ByteArray;
import io.github.dfa1.vortex.core.array.DoubleArray;
import io.github.dfa1.vortex.core.array.FloatArray;
import io.github.dfa1.vortex.core.array.IntArray;
import io.github.dfa1.vortex.core.array.LongArray;
import io.github.dfa1.vortex.core.array.ShortArray;
import io.github.dfa1.vortex.core.array.VarBinArray;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.encoding.CodecId;
import io.github.dfa1.vortex.io.VortexHandle;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// Iterates over decoded chunks from a [VortexReader].
///
/// Usage:
/// ```java
/// try (var iter = file.scan(ScanOptions.all())) {
///     while (iter.hasNext()) {
///         ScanResult chunk = iter.next();
///     }
/// }
/// ```
public final class ScanIterator implements AutoCloseable {

	private static final ValueLayout.OfShort LE_SHORT = ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
	private static final ValueLayout.OfInt   LE_INT   = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
	private static final ValueLayout.OfLong  LE_LONG  = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

	private static final long SLAB_SIZE = 4 * 1024 * 1024L;

	private final VortexHandle file;
	private final ScanOptions options;
	private final Arena arena;
	private MemorySegment chunkSlab;

	private List<ChunkSpec> chunks;
	private Map<String, DType> columnDtypes;
	private List<String> projectedNames;
	private List<DType> projectedDtypes;
	private int chunkIndex;
	private long rowsReturned;
	private ScanResult current;

	public ScanIterator(VortexHandle file, ScanOptions options, Arena arena) {
		this.file = file;
		this.options = options;
		this.arena = arena;
	}

	private static void collectFlats(Layout layout, List<Layout> out) {
		if (layout.isFlat()) {
			out.add(layout);
		} else if (layout.isDict()) {
			// Dict layout is a leaf chunk — decoded as a unit (values + codes).
			out.add(layout);
		} else if (layout.isZoned()) {
			// vortex.stats wraps one child (the data layout) — pass through for data
			if (!layout.children().isEmpty()) {
				collectFlats(layout.children().get(0), out);
			}
		} else if (layout.isChunked()) {
			// metadata[0] == 1 means children[0] is the per-chunk stats layout; skip it
			int start = (layout.metadata() != null
					&& layout.metadata().hasRemaining()
					&& layout.metadata().get(0) == 1) ? 1 : 0;
			for (int i = start; i < layout.children().size(); i++) {
				collectFlats(layout.children().get(i), out);
			}
		}
	}

	private static List<ChunkSpec> buildChunks(Map<String, List<Layout>> columnFlats) {
		if (columnFlats.isEmpty()) {
			return List.of();
		}
		String[] colNames = columnFlats.keySet().toArray(String[]::new);
		int numCols = colNames.length;
		int numChunks = columnFlats.values().iterator().next().size();
		var result = new ArrayList<ChunkSpec>(numChunks);
		for (int i = 0; i < numChunks; i++) {
			Layout[] layouts = new Layout[numCols];
			for (int j = 0; j < numCols; j++) {
				layouts[j] = columnFlats.get(colNames[j]).get(i);
			}
			result.add(new ChunkSpec(layouts[0].rowCount(), colNames, layouts));
		}
		return List.copyOf(result);
	}

	// ── Layout tree traversal ─────────────────────────────────────────────────

	@SuppressWarnings("unchecked")
	private static int compareValues(Object a, Object b) {
		try {
			return ((Comparable<Object>) a).compareTo(b);
		} catch (ClassCastException e) {
			return 0;
		}
	}

	public boolean hasNext() {
		if (chunks == null) {
			initialize();
		}
		if (rowsReturned >= options.limit()) {
			return false;
		}

		while (chunkIndex < chunks.size()) {
			ChunkSpec chunk = chunks.get(chunkIndex++);
			if (options.hasFilter() && canPruneChunk(chunk, options.rowFilter())) {
				continue;
			}

			current = new ScanResult(chunk.rowCount(), buildColumnMap(chunk));
			rowsReturned += chunk.rowCount();
			return true;
		}
		return false;
	}

	public ScanResult next() {
		if (current == null) {
			throw new VortexException("call hasNext() first");
		}
		return current;
	}

	// ── Column map builder ────────────────────────────────────────────────────

	@Override
	public void close() {
	}

	// ── Flat segment decoding ─────────────────────────────────────────────────

	private void initialize() {
		Layout rootLayout = file.layout();
		DType rootDtype = file.dtype();

		var columnFlats = new LinkedHashMap<String, List<Layout>>();
		columnDtypes = new LinkedHashMap<>();

		if (rootLayout.isStruct() && rootDtype instanceof DType.Struct structDtype) {
			List<String> projection = options.columns();
			for (int i = 0; i < rootLayout.children().size(); i++) {
				String colName = structDtype.fieldNames().get(i);
				DType colDtype = structDtype.fieldTypes().get(i);
				if (!projection.isEmpty() && !projection.contains(colName)) {
					continue;
				}
				var flats = new ArrayList<Layout>();
				collectFlats(rootLayout.children().get(i), flats);
				columnFlats.put(colName, flats);
				columnDtypes.put(colName, colDtype);
			}
		} else {
			var flats = new ArrayList<Layout>();
			collectFlats(rootLayout, flats);
			columnFlats.put("_col", flats);
			columnDtypes.put("_col", rootDtype);
		}

		chunks = buildChunks(columnFlats);
		projectedNames = List.copyOf(columnDtypes.keySet());
		projectedDtypes = List.copyOf(columnDtypes.values());
		chunkSlab = arena.allocate(SLAB_SIZE, 64);
	}

	// ── Zone-map pruning ──────────────────────────────────────────────────────

	// Map.of with 1 or 2 args allocates Map1/Map2 (~2-4 fields) — avoids the
	// LinkedHashMap + Map.copyOf pair that would otherwise allocate per chunk.
	// Direct array index into ChunkSpec.columnLayouts avoids HashMap.get() per column.
	private Map<String, Array> buildColumnMap(ChunkSpec chunk) {
		SegmentAllocator chunkAlloc = SegmentAllocator.slicingAllocator(chunkSlab);
		Layout[] layouts = chunk.columnLayouts();
		int n = projectedNames.size();
		if (n == 1) {
			Array arr = decodeLayout(layouts[0], projectedDtypes.get(0), chunkAlloc);
			if (arr instanceof StructArray sa) {
				return expandStruct(sa);
			}
			return Map.of(projectedNames.get(0), arr);
		}
		if (n == 2) {
			return Map.of(
					projectedNames.get(0), decodeLayout(layouts[0], projectedDtypes.get(0), chunkAlloc),
					projectedNames.get(1), decodeLayout(layouts[1], projectedDtypes.get(1), chunkAlloc));
		}
		var scratch = new LinkedHashMap<String, Array>(n);
		for (int i = 0; i < n; i++) {
			scratch.put(projectedNames.get(i), decodeLayout(layouts[i], projectedDtypes.get(i), chunkAlloc));
		}
		return Map.copyOf(scratch);
	}

	private static Map<String, Array> expandStruct(StructArray sa) {
		DType.Struct sd = (DType.Struct) sa.dtype();
		List<String> names = sd.fieldNames();
		int n = names.size();
		var map = new LinkedHashMap<String, Array>(n);
		for (int i = 0; i < n; i++) {
			map.put(names.get(i), sa.field(i));
		}
		return Map.copyOf(map);
	}

	private Array decodeLayout(Layout layout, DType dtype, SegmentAllocator arena) {
		if (layout.isFlat()) {
			return decodeFlat(layout, dtype, arena);
		}
		if (layout.isDict()) {
			return decodeDictLayout(layout, dtype, arena);
		}
		if (layout.isZoned() && !layout.children().isEmpty()) {
			return decodeLayout(layout.children().get(0), dtype, arena);
		}
		if (layout.isChunked()) {
			var flats = new ArrayList<Layout>();
			collectFlats(layout, flats);
			return decodeConcatPrimitive(flats, dtype, layout.rowCount(), arena);
		}
		throw new VortexException("cannot decode layout " + layout.encodingId());
	}

	private Array decodeConcatPrimitive(List<Layout> flats, DType dtype, long totalRows, SegmentAllocator arena) {
		if (flats.isEmpty()) {
			throw new VortexException(CodecId.VORTEX_CHUNKED, "no flat children");
		}
		if (flats.size() == 1) {
			return decodeFlat(flats.get(0), dtype, arena);
		}
		PType ptype = ((DType.Primitive) dtype).ptype();
		MemorySegment combined = arena.allocate(totalRows * ptype.byteSize());
		long byteOffset = 0;
		for (Layout flat : flats) {
			Array chunk = decodeFlat(flat, dtype, arena);
			MemorySegment src = chunk.buffer(0);
			MemorySegment.copy(src, 0, combined, byteOffset, src.byteSize());
			byteOffset += src.byteSize();
		}
		MemorySegment ro = combined.asReadOnly();
		return switch (ptype) {
			case I64, U64 -> new LongArray(dtype, totalRows, ro, ArrayStats.empty());
			case I32, U32 -> new IntArray(dtype, totalRows, ro, ArrayStats.empty());
			case F64 -> new DoubleArray(dtype, totalRows, ro, ArrayStats.empty());
			case F32 -> new FloatArray(dtype, totalRows, ro, ArrayStats.empty());
			case I16, U16 -> new ShortArray(dtype, totalRows, ro, ArrayStats.empty());
			case I8, U8   -> new ByteArray(dtype, totalRows, ro, ArrayStats.empty());
			default -> throw new VortexException("unsupported ptype for concat: " + ptype);
		};
	}

	private Array decodeFlat(Layout flat, DType dtype, SegmentAllocator arena) {
		if (flat.segments().isEmpty()) {
			throw new VortexException(CodecId.VORTEX_FLAT, "no segments");
		}
		int segIdx = flat.segments().get(0);
		SegmentSpec spec = file.footer().segmentSpecs().get(segIdx);
		MemorySegment seg = file.slice(spec.offset(), spec.length());
		return file.registry().decodeSegment(seg, file.footer().arraySpecs(), dtype, flat.rowCount(), arena);
	}

	private Array decodeDictLayout(Layout dictLayout, DType dtype, SegmentAllocator arena) {
		ByteBuffer rawMeta = dictLayout.metadata();
		if (rawMeta == null) {
			throw new VortexException(CodecId.VORTEX_DICT, "layout: missing metadata");
		}
		EncodingProtos.DictLayoutMetadata meta;
		try {
			meta = EncodingProtos.DictLayoutMetadata.parseFrom(rawMeta.duplicate());
		} catch (InvalidProtocolBufferException e) {
			throw new VortexException(CodecId.VORTEX_DICT, "layout: invalid metadata", e);
		}
		PType codesPType = PType.values()[meta.getCodesPtype().getNumber()];

		// child[0] = values layout; child[1] = codes layout
		Layout valuesLayout = dictLayout.children().get(0);
		Layout codesLayout  = dictLayout.children().get(1);
		long valuesLen = valuesLayout.rowCount();
		long n         = codesLayout.rowCount();

		Array values = decodeLayout(valuesLayout, dtype, arena);
		Array codes  = decodeLayout(codesLayout, new DType.Primitive(codesPType, false), arena);

		if (values instanceof VarBinArray) {
			MemorySegment valOffsets = values.child(0).buffer(0);
			PType valOffPType = ((DType.Primitive) values.child(0).dtype()).ptype();
			return VarBinArray.ofDict(dtype, n, values.buffer(0), valOffsets, valOffPType,
					codes.buffer(0), codesPType, ArrayStats.empty());
		}
		return expandDictStrings(values, codes, codesPType, dtype, n, arena);
	}

	private static Array expandDictStrings(
			Array values, Array codes,
			PType codesPType, DType dtype,
			long n, SegmentAllocator arena
	) {
		MemorySegment valBytes   = values.buffer(0);
		MemorySegment valOffsets = values.child(0).buffer(0);
		PType valOffPType        = ((DType.Primitive) values.child(0).dtype()).ptype();
		MemorySegment codesSegs  = codes.buffer(0);

		// First pass: total output byte length
		long totalBytes = 0L;
		for (long i = 0; i < n; i++) {
			long code  = readUnsigned(codesSegs, i, codesPType);
			long start = readUnsigned(valOffsets, code, valOffPType);
			long end   = readUnsigned(valOffsets, code + 1, valOffPType);
			totalBytes += end - start;
		}

		MemorySegment outBytes   = arena.allocate(totalBytes > 0 ? totalBytes : 1);
		MemorySegment outOffsets = arena.allocate((n + 1) * 4L, 4);
		outOffsets.setAtIndex(LE_INT, 0, 0);

		long bytePos = 0L;
		for (long i = 0; i < n; i++) {
			long code  = readUnsigned(codesSegs, i, codesPType);
			long start = readUnsigned(valOffsets, code, valOffPType);
			long end   = readUnsigned(valOffsets, code + 1, valOffPType);
			long strLen = end - start;
			if (strLen > 0) {
				MemorySegment.copy(valBytes, start, outBytes, bytePos, strLen);
				bytePos += strLen;
			}
			outOffsets.setAtIndex(LE_INT, i + 1, (int) bytePos);
		}

		DType i32 = new DType.Primitive(PType.I32, false);
		Array offsetArr = new IntArray(i32, n + 1, outOffsets.asReadOnly(), ArrayStats.empty());
		return new VarBinArray(dtype, n, outBytes.asReadOnly(), offsetArr, PType.I32, ArrayStats.empty());
	}

	private static long readUnsigned(MemorySegment seg, long idx, PType ptype) {
		return switch (ptype) {
			case U8  -> Byte.toUnsignedLong(seg.get(ValueLayout.JAVA_BYTE, idx));
			case U16 -> Short.toUnsignedLong(seg.get(LE_SHORT, idx * 2));
			case U32 -> Integer.toUnsignedLong(seg.getAtIndex(LE_INT, idx));
			case I32 -> seg.getAtIndex(LE_INT, idx);
			case I64, U64 -> seg.getAtIndex(LE_LONG, idx);
			default  -> throw new VortexException(CodecId.VORTEX_DICT, "layout: unsupported ptype " + ptype);
		};
	}

	private boolean canPruneChunk(ChunkSpec chunk, RowFilter filter) {
		return switch (filter) {
			case RowFilter.And(var filters) -> {
				for (RowFilter f : filters) {
					if (canPruneChunk(chunk, f)) {
						yield true;
					}
				}
				yield false;
			}
			case RowFilter.Gte(var col, var val) -> {
				Layout flat = chunk.layoutFor(col);
				if (flat == null) {
					yield false;
				}
				Object max = readFlatStats(flat).max();
				yield max != null && compareValues(max, val) < 0;
			}
			case RowFilter.Lte(var col, var val) -> {
				Layout flat = chunk.layoutFor(col);
				if (flat == null) {
					yield false;
				}
				Object min = readFlatStats(flat).min();
				yield min != null && compareValues(min, val) > 0;
			}
			case RowFilter.Eq(var col, var val) -> {
				Layout flat = chunk.layoutFor(col);
				if (flat == null) {
					yield false;
				}
				ArrayStats stats = readFlatStats(flat);
				Object min = stats.min();
				Object max = stats.max();
				if (min == null || max == null) {
					yield false;
				}
				try {
					@SuppressWarnings("unchecked")
					Comparable<Object> cv = (Comparable<Object>) val;
					yield cv.compareTo(min) < 0 || cv.compareTo(max) > 0;
				} catch (ClassCastException e) {
					yield false;
				}
			}
		};
	}

	private ArrayStats readFlatStats(Layout flat) {
		if (flat.segments().isEmpty()) {
			return ArrayStats.empty();
		}
		int segIdx = flat.segments().getFirst();
		SegmentSpec spec = file.footer().segmentSpecs().get(segIdx);
		long segLen = spec.length();
		MemorySegment seg = file.slice(spec.offset(), segLen);

		// Stats FlatBuffer lives in the segment's last 4+fbLen bytes; reading the whole
		// segment as a ByteBuffer would fail for segments larger than 2 GB (ByteBuffer cap).
		int fbLen = seg.get(LE_INT, segLen - 4);
		long fbStart = segLen - 4L - fbLen;
		ByteBuffer fbBuf = seg.asSlice(fbStart, fbLen).asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
		var fbArray = io.github.dfa1.vortex.fbs.Array.getRootAsArray(fbBuf);

		io.github.dfa1.vortex.fbs.ArrayNode root = fbArray.root();
		if (root == null) {
			return ArrayStats.empty();
		}
		return ArrayStats.fromFbs(root.stats());
	}

	// ── Internal record ───────────────────────────────────────────────────────

	private record ChunkSpec(long rowCount, String[] columnNames, Layout[] columnLayouts) {
		Layout layoutFor(String col) {
			for (int i = 0; i < columnNames.length; i++) {
				if (columnNames[i].equals(col)) {
					return columnLayouts[i];
				}
			}
			return null;
		}
	}
}
