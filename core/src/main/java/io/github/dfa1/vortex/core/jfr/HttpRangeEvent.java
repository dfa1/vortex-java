package io.github.dfa1.vortex.core.jfr;

import jdk.jfr.Category;
import jdk.jfr.DataAmount;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/// An HTTP range request for part of a remote Vortex file.
@Name("io.github.dfa1.vortex.HttpRange")
@Label("HTTP Range Request")
@Category({"Vortex", "I/O"})
@Description("An HTTP range request for part of a remote Vortex file.")
@StackTrace(false)
public final class HttpRangeEvent extends Event {

    /// The URL without credentials, query or fragment.
    @Label("Source")
    public String source;

    /// The first byte requested, or `-1` for the tail of the file.
    @Label("Offset")
    public long offset;

    /// The number of bytes requested.
    @Label("Length")
    @DataAmount
    public long length;

    /// The HTTP status, or `0` when there was no response.
    @Label("Status")
    public int status;

    /// Creates an empty event; the emitting code fills the fields before committing it.
    public HttpRangeEvent() {
    }
}
