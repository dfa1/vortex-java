package io.github.dfa1.vortex.writer;

import io.github.dfa1.vortex.core.model.Editions;

/// Write options targeting `core2025.10.0`, the last core edition before Rust's `vortex.zoned`
/// layout: the writer then emits the legacy `vortex.stats` zone map, one zone per `writeChunk`
/// batch with a per-zone sum. Tests of that layout, and reader-pruning tests that need small,
/// separately-zoned batches, write with these; default writes use 8192-row `vortex.zoned` zones.
final class LegacyZoneMaps {

    static final WriteOptions OPTIONS = WriteOptions.defaults().withEdition(Editions.CORE_2025_10_0);

    private LegacyZoneMaps() {
    }
}
