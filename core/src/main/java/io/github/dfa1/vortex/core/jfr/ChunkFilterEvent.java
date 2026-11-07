package io.github.dfa1.vortex.core.jfr;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/// A filtered scan finished; counts how its chunks were pruned.
@Name("io.github.dfa1.vortex.ChunkFilter")
@Label("Chunk Filter")
@Category({"Vortex", "Filter"})
@Description("A filtered scan finished; counts how its chunks were pruned.")
@StackTrace(false)
public final class ChunkFilterEvent extends Event {

    /// The chunks the scan considered.
    @Label("Total Chunks")
    public int totalChunks;

    /// The chunks skipped by zone-map pruning.
    @Label("Chunks Pruned")
    public int chunksPruned;

    /// The chunks read but without any matching row.
    @Label("Chunks Without Match")
    public int chunksWithoutMatch;

    /// The chunks returned to the caller.
    @Label("Chunks Returned")
    public int chunksReturned;

    /// The rows in the returned chunks.
    @Label("Rows Returned")
    public long rowsReturned;

    /// Creates an empty event; the emitting code fills the fields before committing it.
    public ChunkFilterEvent() {
    }
}
