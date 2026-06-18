package io.github.dfa1.vortex.reader.array;

import java.util.ArrayList;
import java.util.List;

/// Shared helper for the `ChunkedXxxArray.truncate` overrides. Keeps whole prefix
/// children that fit within `rows` and recursively truncates the single boundary
/// child — preserving each chunk's batch iteration / zero-copy backing instead of
/// the per-element view a plain length-cap would impose.
final class ChunkedArrays {

    private ChunkedArrays() {
    }

    /// Selects the children of a chunked array that cover the first `rows` rows.
    ///
    /// @param children chunk arrays in scan order
    /// @param offsets  cumulative row offsets (length = `children.length + 1`)
    /// @param rows     number of leading rows to keep
    /// @return prefix children unchanged, with the boundary child truncated
    static List<Array> truncateChildren(Array[] children, long[] offsets, long rows) {
        var kept = new ArrayList<Array>(children.length);
        for (int i = 0; i < children.length; i++) {
            long start = offsets[i];
            if (start >= rows) {
                break;
            }
            long end = offsets[i + 1];
            if (end <= rows) {
                kept.add(children[i]);
            } else {
                kept.add(Array.truncated(children[i], rows - start));
            }
        }
        return kept;
    }
}
