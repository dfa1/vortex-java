package io.github.dfa1.vortex.core.jfr;

import jdk.jfr.Category;
import jdk.jfr.DataAmount;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/// A flat segment was decoded.
@Name("io.github.dfa1.vortex.SegmentDecoded")
@Label("Segment Decoded")
@Category({"Vortex", "Decode"})
@Description("A flat segment was decoded.")
@StackTrace(false)
public final class SegmentDecodedEvent extends Event {

    /// The root encoding id of the segment.
    @Label("Encoding")
    public String encoding;

    /// The number of rows in the segment.
    @Label("Row Count")
    public long rowCount;

    /// The serialized segment size in bytes.
    @Label("Segment Size")
    @DataAmount
    public long segmentSize;

    /// Creates an empty event; the emitting code fills the fields before committing it.
    public SegmentDecodedEvent() {
    }
}
