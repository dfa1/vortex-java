package io.github.dfa1.vortex.core.jfr;

import jdk.jfr.Category;
import jdk.jfr.DataAmount;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/// A Vortex file was opened and its footer parsed.
@Name("io.github.dfa1.vortex.FileOpened")
@Label("File Opened")
@Category({"Vortex", "I/O"})
@Description("A Vortex file was opened and its footer parsed.")
@StackTrace(false)
public final class FileOpenedEvent extends Event {

    /// The file path, or the URL without credentials, query or fragment.
    @Label("Source")
    public String source;

    /// The file size in bytes.
    @Label("File Size")
    @DataAmount
    public long fileSize;

    /// The format version from the trailer.
    @Label("Format Version")
    public int formatVersion;

    /// The number of segments in the footer.
    @Label("Segment Count")
    public int segmentCount;

    /// Creates an empty event; the emitting code fills the fields before committing it.
    public FileOpenedEvent() {
    }
}
