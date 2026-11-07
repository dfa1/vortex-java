package io.github.dfa1.vortex.core.jfr;

import jdk.jfr.Category;
import jdk.jfr.DataAmount;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/// A flat segment was encoded.
@Name("io.github.dfa1.vortex.SegmentEncoded")
@Label("Segment Encoded")
@Category({"Vortex", "Encode"})
@Description("A flat segment was encoded.")
@StackTrace(false)
public final class SegmentEncodedEvent extends Event {

    /// The column the segment belongs to.
    @Label("Column")
    public String column;

    /// The index of the segment in the file.
    @Label("Segment")
    public int segment;

    /// The root encoding id of the segment.
    @Label("Encoding")
    public String encoding;

    /// The number of buffers in the segment.
    @Label("Buffer Count")
    public int bufferCount;

    /// The encoded size in bytes.
    @Label("Encoded Size")
    @DataAmount
    public long encodedSize;

    /// Creates an empty event; the emitting code fills the fields before committing it.
    public SegmentEncodedEvent() {
    }
}
