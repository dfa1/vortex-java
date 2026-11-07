package io.github.dfa1.vortex.core.jfr;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/// The writer waited for the oldest in-flight segment to finish encoding.
@Name("io.github.dfa1.vortex.SegmentWait")
@Label("Segment Wait")
@Category({"Vortex", "Pipeline"})
@Description("The writer waited for the oldest in-flight segment to finish encoding.")
@StackTrace(false)
public final class SegmentWaitEvent extends Event {

    /// The segments in flight, including the one waited for.
    @Label("Segments In Flight")
    public int segmentsInFlight;

    /// Creates an empty event; the emitting code fills the fields before committing it.
    public SegmentWaitEvent() {
    }
}
