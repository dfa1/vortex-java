package io.github.dfa1.vortex.calcite;

import io.github.dfa1.vortex.core.model.Edition;
import io.github.dfa1.vortex.core.model.EditionFamily;
import io.github.dfa1.vortex.core.model.Editions;
import io.github.dfa1.vortex.writer.WriteOptions;

import java.util.Map;

/// Editions targeting `core2025.10.0`, the last core edition before Rust's `vortex.zoned`: the
/// writer then emits the legacy `vortex.stats` zone map, one zone per `writeChunk` batch with a
/// per-zone sum — the shape the zone fold folds. Default writes use 8192-row `vortex.zoned` zones
/// without sums, which the fold cannot use yet, so those aggregates fall back to a scan.
final class LegacyZoneMaps {

    static final Map<EditionFamily, Edition> EDITIONS = Map.of(EditionFamily.CORE, Editions.CORE_2025_10_0);

    static final WriteOptions OPTIONS = WriteOptions.defaults().withEdition(Editions.CORE_2025_10_0);

    private LegacyZoneMaps() {
    }
}
