package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.model.EncodingId;

/// Where in a write the compressor is, for its JFR events: the column and segment being encoded
/// and the cascade path down to the array in hand, as Rust's `ctx.cascade_path()`.
///
/// The path is a linked list so deriving a child costs one allocation; it is rendered to a string
/// only when an event is committed.
///
/// @param column  the column being encoded, or empty when unknown
/// @param segment the index of the segment being encoded, or `-1` when unknown
/// @param path    the innermost cascade step, or `null` at the root
public record EncodeTrace(String column, int segment, Step path) {

    /// The trace of a write with no known column or segment.
    public static final EncodeTrace NONE = new EncodeTrace("", -1, null);

    /// One cascade step: the encoding whose child slot is being filled.
    ///
    /// @param parent   the enclosing step, or `null`
    /// @param encoding the encoding that owns the child slot
    /// @param child    the child's index in that encoding
    public record Step(Step parent, EncodingId encoding, int child) {
    }

    /// Creates the trace of one segment of a column, at the root of its cascade.
    ///
    /// @param column  the column name
    /// @param segment the segment index
    /// @return the trace
    public static EncodeTrace of(String column, int segment) {
        return new EncodeTrace(column, segment, null);
    }

    /// Descends into a child slot.
    ///
    /// @param parent the encoding that owns the slot
    /// @param child  the child's index in `parent`
    /// @return the trace of the child
    public EncodeTrace child(EncodingId parent, int child) {
        return new EncodeTrace(column, segment, new Step(path, parent, child));
    }

    /// The cascade depth: the number of steps from the root.
    ///
    /// @return the depth, `0` at the root
    public int depth() {
        int depth = 0;
        for (Step s = path; s != null; s = s.parent()) {
            depth++;
        }
        return depth;
    }

    /// The path as `parent.child/parent.child`, root first, with the `vortex.` prefix dropped,
    /// e.g. `datetimeparts.0/rle.1`; empty at the root.
    ///
    /// @return the rendered path
    public String pathString() {
        var sb = new StringBuilder();
        render(path, sb);
        return sb.toString();
    }

    /// The encoding that owns the slot the current array fills, or empty at the root.
    ///
    /// @return the parent encoding id, without the `vortex.` prefix
    public String parentString() {
        if (path == null) {
            return "";
        }
        return shortId(path.encoding());
    }

    private static void render(Step step, StringBuilder sb) {
        if (step == null) {
            return;
        }
        render(step.parent(), sb);
        if (!sb.isEmpty()) {
            sb.append('/');
        }
        sb.append(shortId(step.encoding())).append('.').append(step.child());
    }

    private static String shortId(EncodingId id) {
        String s = id.id();
        return s.startsWith("vortex.") ? s.substring("vortex.".length()) : s;
    }
}
