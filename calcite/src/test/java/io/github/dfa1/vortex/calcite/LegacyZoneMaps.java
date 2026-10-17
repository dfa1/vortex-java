package io.github.dfa1.vortex.calcite;

import io.github.dfa1.vortex.core.model.Editions;
import io.github.dfa1.vortex.writer.WriteOptions;

/// Editions targeting `core2025.10.0`, the last core edition before Rust's `vortex.zoned`: the
/// writer then emits the legacy `vortex.stats` zone map, one zone per `writeChunk` batch with a
/// per-zone sum. Default writes use 8192-row `vortex.zoned` zones without sums, so a `SUM` over
/// them falls back to a scan.
final class LegacyZoneMaps {

    static final WriteOptions OPTIONS = WriteOptions.defaults().withEdition(Editions.CORE_2025_10_0);

    private LegacyZoneMaps() {
    }
}
