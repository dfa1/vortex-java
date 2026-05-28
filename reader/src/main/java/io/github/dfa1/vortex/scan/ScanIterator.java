package io.github.dfa1.vortex.scan;

import io.github.dfa1.vortex.core.Array;
import io.github.dfa1.vortex.core.ArrayStats;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.Layout;
import io.github.dfa1.vortex.core.SegmentSpec;
import io.github.dfa1.vortex.io.VortexReader;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
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

	private final VortexReader file;
	private final ScanOptions options;
	private final Arena arena;

	private List<ChunkSpec> chunks;
	private Map<String, DType> columnDtypes;
	private List<String> projectedNames;
	private List<DType> projectedDtypes;
	private int chunkIndex;
	private long rowsReturned;
	private ScanResult current;

	public ScanIterator(VortexReader file, ScanOptions options, Arena arena) {
		this.file = file;
		this.options = options;
		this.arena = arena;
	}

	private static void collectFlats(Layout layout, List<Layout> out) {
		if (layout.isFlat()) {
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
			throw new IllegalStateException("call hasNext() first");
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
	}

	// ── Zone-map pruning ──────────────────────────────────────────────────────

	// Map.of with 1 or 2 args allocates Map1/Map2 (~2-4 fields) — avoids the
	// LinkedHashMap + Map.copyOf pair that would otherwise allocate per chunk.
	// Direct array index into ChunkSpec.columnLayouts avoids HashMap.get() per column.
	private Map<String, Array> buildColumnMap(ChunkSpec chunk) {
		Layout[] layouts = chunk.columnLayouts();
		int n = projectedNames.size();
		if (n == 1) {
			return Map.of(projectedNames.get(0), decodeFlat(layouts[0], projectedDtypes.get(0), arena));
		}
		if (n == 2) {
			return Map.of(
					projectedNames.get(0), decodeFlat(layouts[0], projectedDtypes.get(0), arena),
					projectedNames.get(1), decodeFlat(layouts[1], projectedDtypes.get(1), arena));
		}
		var scratch = new LinkedHashMap<String, Array>(n);
		for (int i = 0; i < n; i++) {
			scratch.put(projectedNames.get(i), decodeFlat(layouts[i], projectedDtypes.get(i), arena));
		}
		return Map.copyOf(scratch);
	}

	private Array decodeFlat(Layout flat, DType dtype, Arena arena) {
		if (flat.segments().isEmpty()) {
			throw new IllegalStateException("vortex: Flat layout has no segments");
		}
		int segIdx = flat.segments().get(0);
		SegmentSpec spec = file.footer().segmentSpecs().get(segIdx);
		MemorySegment seg = file.slice(spec.offset(), spec.length());
		return file.registry().decodeSegment(seg, file.footer().arraySpecs(), dtype, flat.rowCount(), arena);
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
		int segLen = spec.length();
		MemorySegment seg = file.slice(spec.offset(), segLen);
		ByteBuffer bb = seg.asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);

		int fbLen = bb.getInt(segLen - 4);
		int fbStart = segLen - 4 - fbLen;
		ByteBuffer fbBuf = bb.slice(fbStart, fbLen).order(ByteOrder.LITTLE_ENDIAN);
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
