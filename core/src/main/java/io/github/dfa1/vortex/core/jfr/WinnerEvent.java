package io.github.dfa1.vortex.core.jfr;

import jdk.jfr.Category;
import jdk.jfr.DataAmount;
import jdk.jfr.Description;
import jdk.jfr.Enabled;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/// The compressor encoded an array with its chosen scheme (Rust's `winner_compress` span).
@Name("io.github.dfa1.vortex.Winner")
@Label("Winner")
@Category({"Vortex", "Compressor"})
@Enabled(false)
@Description("The compressor encoded an array with its chosen scheme (Rust's `winner_compress` span).")
@StackTrace(false)
public final class WinnerEvent extends Event {

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

    /// The encoding whose child this array is; empty at the root.
    @Label("Parent")
    public String parent;

    /// The encoding id picked.
    @Label("Scheme Chosen")
    public String schemeChosen;

    /// The uncompressed size of the array.
    @Label("Input Bytes")
    @DataAmount
    public long inputNbytes;

    /// The size the scheme encoded the full array to; `-1` when it failed.
    @Label("Compressed Bytes")
    @DataAmount
    public long compressedNbytes;

    /// The ratio the choice was made at, from the stats estimate or the sample; `NaN` when always-use.
    @Label("Estimated Ratio")
    public double estimatedRatio;

    /// `inputNbytes / compressedNbytes` on the full array; `NaN` when unknown.
    @Label("Achieved Ratio")
    public double achievedRatio;

    /// Whether the result was kept; `false` when the scheme failed or did not beat the uncompressed array and the canonical encoding was used.
    @Label("Accepted")
    public boolean accepted;

    /// Whether the scheme won on its stats estimate without a trial encode.
    @Label("Estimated")
    public boolean estimated;

    /// Why the scheme was dropped, empty otherwise.
    @Label("Error")
    public String error;

    /// Creates an empty event; the emitting code fills the fields before committing it.
    public WinnerEvent() {
    }
}
