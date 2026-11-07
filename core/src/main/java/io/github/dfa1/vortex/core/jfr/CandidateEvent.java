package io.github.dfa1.vortex.core.jfr;

import jdk.jfr.Category;
import jdk.jfr.DataAmount;
import jdk.jfr.Description;
import jdk.jfr.Enabled;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/// The compressor evaluated one scheme for an array (Rust's `scheme_eval` span).
@Name("io.github.dfa1.vortex.Candidate")
@Label("Candidate")
@Category({"Vortex", "Compressor"})
@Enabled(false)
@Description("The compressor evaluated one scheme for an array (Rust's `scheme_eval` span).")
@StackTrace(false)
public final class CandidateEvent extends Event {

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

    /// The encoding id evaluated.
    @Label("Scheme Candidate")
    public String schemeCandidate;

    /// `skip` and `always_use` and `estimate` come from the stats before any encoding, `measured` is a trial encode, and `cascade_exhausted` is a scheme that needs a child with no cascade depth left.
    @Label("Verdict")
    public String verdict;

    /// Whether the numbers are on the sample (a trial encode, or any evaluation inside one) rather than the full array.
    @Label("Sample")
    public boolean sample;

    /// The uncompressed size a candidate has to beat.
    @Label("Baseline Bytes")
    @DataAmount
    public long baselineNbytes;

    /// The stats estimate of `baselineNbytes / size`; `NaN` unless the verdict is `estimate`.
    @Label("Estimated Ratio")
    public double estimatedRatio;

    /// The measured size of a trial encode; `-1` unless the verdict is `measured`.
    @Label("Sample Bytes")
    @DataAmount
    public long sampleNbytes;

    /// Creates an empty event; the emitting code fills the fields before committing it.
    public CandidateEvent() {
    }
}
