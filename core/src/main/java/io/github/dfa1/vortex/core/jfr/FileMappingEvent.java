package io.github.dfa1.vortex.core.jfr;

import jdk.jfr.Category;
import jdk.jfr.DataAmount;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/// A Vortex file was memory-mapped.
@Name("io.github.dfa1.vortex.FileMapping")
@Label("File Mapping")
@Category({"Vortex", "I/O"})
@Description("A Vortex file was memory-mapped.")
@StackTrace(false)
public final class FileMappingEvent extends Event {

    /// The file path.
    @Label("File")
    public String file;

    /// The mapped size in bytes.
    @Label("Size")
    @DataAmount
    public long size;

    /// Creates an empty event; the emitting code fills the fields before committing it.
    public FileMappingEvent() {
    }
}
