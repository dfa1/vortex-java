package io.github.dfa1.vortex.core;

import java.nio.ByteBuffer;
import java.util.List;

/// Node in the Vortex layout tree. Describes physical arrangement of data in the file.
///
/// Typical tree shape per column:
/// ```
/// Struct → Zoned(Stats) → Chunked → [Flat, Flat, ...]
/// ```
///
/// @param encodingId encoding id string (e.g. {@code "vortex.flat"})
/// @param rowCount   number of logical rows covered by this node
/// @param metadata   optional encoding-specific metadata bytes, or {@code null}
/// @param children   child layout nodes (empty for leaf nodes)
/// @param segments   indices into the file's segment table for buffers owned by this node
public record Layout(
		String encodingId,
		long rowCount,
		ByteBuffer metadata,
		List<Layout> children,
		List<Integer> segments
) {
	/// Encoding id for flat (leaf) layouts ({@code "vortex.flat"}).
	public static final String FLAT = "vortex.flat";
	/// Encoding id for chunked layouts ({@code "vortex.chunked"}).
	public static final String CHUNKED = "vortex.chunked";
	/// Encoding id for struct layouts ({@code "vortex.struct"}).
	public static final String STRUCT = "vortex.struct";
	/// Encoding id for zone-map layouts ({@code "vortex.stats"}).
	public static final String ZONED = "vortex.stats";
	/// Encoding id for dictionary layouts ({@code "vortex.dict"}).
	public static final String DICT = "vortex.dict";

	/// Returns {@code true} if this layout is a flat (leaf) layout.
	///
	/// @return {@code true} when {@code encodingId} equals {@link #FLAT}
	public boolean isFlat() {
		return FLAT.equals(encodingId);
	}

	/// Returns {@code true} if this layout is a chunked layout.
	///
	/// @return {@code true} when {@code encodingId} equals {@link #CHUNKED}
	public boolean isChunked() {
		return CHUNKED.equals(encodingId);
	}

	/// Returns {@code true} if this layout is a struct layout.
	///
	/// @return {@code true} when {@code encodingId} equals {@link #STRUCT}
	public boolean isStruct() {
		return STRUCT.equals(encodingId);
	}

	/// Returns {@code true} if this layout is a zone-map (stats) layout.
	///
	/// @return {@code true} when {@code encodingId} equals {@link #ZONED}
	public boolean isZoned() {
		return ZONED.equals(encodingId);
	}

	/// Returns {@code true} if this layout is a dictionary layout.
	///
	/// @return {@code true} when {@code encodingId} equals {@link #DICT}
	public boolean isDict() {
		return DICT.equals(encodingId);
	}
}
