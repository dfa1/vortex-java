package io.github.dfa1.vortex.core.jfr;

import jdk.jfr.Category;
import jdk.jfr.DataAmount;
import jdk.jfr.Description;
import jdk.jfr.Enabled;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/// The compressor encoded one array, at some level of the cascade (Rust's `compress` span).
@Name("io.github.dfa1.vortex.Compress")
@Label("Compress")
@Category({"Vortex", "Compressor"})
@Enabled(false)
@Description("The compressor encoded one array, at some level of the cascade (Rust's `compress` span).")
@StackTrace(false)
public final class CompressEvent extends Event {

    /// The column being encoded; empty when unknown.
    @Label("Column")
    public String column;

    /// The index of the segment being encoded; `-1` when unknown.
    @Label("Segment")
    public int segment;

    /// The cascade down to this array as `parent.child/parent.child` with the `vortex.` prefix dropped, e.g. `datetimeparts.0/fastlanes.rle.1`; empty at the root. Mirrors Rust's `cascade_path`, though not its rendering.
    @Label("Cascade Path")
    public String cascadePath;

    /// The number of steps in the cascade path.
    @Label("Cascade Depth")
    public int cascadeDepth;

    /// The array's logical type.
    @Label("DType")
    public String dtype;

    /// The number of rows.
    @Label("Array Length")
    public long arrayLen;

    /// The uncompressed size; `-1` when the dtype has no cheap size.
    @Label("Input Bytes")
    @DataAmount
    public long inputNbytes;

    /// The size of the encoded buffers.
    @Label("Compressed Bytes")
    @DataAmount
    public long compressedNbytes;

    /// `inputNbytes / compressedNbytes`; `NaN` when either is unknown or zero.
    @Label("Compression Ratio")
    public double compressionRatio;

    /// Creates an empty event; the emitting code fills the fields before committing it.
    public CompressEvent() {
    }
}
