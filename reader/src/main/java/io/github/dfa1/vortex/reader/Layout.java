package io.github.dfa1.vortex.reader;

import io.github.dfa1.vortex.core.model.LayoutId;

import java.lang.foreign.MemorySegment;
import java.util.List;

/// Node in the Vortex layout tree. Describes physical arrangement of data in the file.
///
/// Typical tree shape per column:
/// ```
/// Struct → Zoned(Stats) → Chunked → [Flat, Flat, ...]
/// ```
///
/// @param layoutId typed layout id (e.g. [LayoutId#FLAT])
/// @param rowCount number of logical rows covered by this node
/// @param metadata optional encoding-specific metadata bytes, or `null`
/// @param children child layout nodes (empty for leaf nodes)
/// @param segments indices into the file's segment table for buffers owned by this node
public record Layout(
        LayoutId layoutId,
        long rowCount,
        MemorySegment metadata,
        List<Layout> children,
        List<Integer> segments
) {
    /// Returns `true` if this layout is a flat (leaf) layout.
    ///
    /// @return `true` when `layoutId` is [LayoutId#FLAT]
    public boolean isFlat() {
        return layoutId == LayoutId.FLAT;
    }

    /// Returns `true` if this layout is a chunked layout.
    ///
    /// @return `true` when `layoutId` is [LayoutId#CHUNKED]
    public boolean isChunked() {
        return layoutId == LayoutId.CHUNKED;
    }

    /// Returns `true` if this layout is a struct layout.
    ///
    /// @return `true` when `layoutId` is [LayoutId#STRUCT]
    public boolean isStruct() {
        return layoutId == LayoutId.STRUCT;
    }

    /// Returns `true` if this layout is a zone-map layout.
    ///
    /// Both the canonical [LayoutId#ZONED] (`vortex.zoned`) and its legacy alias
    /// [LayoutId#STATS] (`vortex.stats`, which vortex-java currently writes) count as zoned.
    ///
    /// @return `true` when `layoutId` is [LayoutId#ZONED] or [LayoutId#STATS]
    public boolean isZoned() {
        return layoutId == LayoutId.ZONED || layoutId == LayoutId.STATS;
    }

    /// Returns `true` if this layout is a dictionary layout.
    ///
    /// @return `true` when `layoutId` is [LayoutId#DICT]
    public boolean isDict() {
        return layoutId == LayoutId.DICT;
    }
}
