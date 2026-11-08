package io.github.dfa1.vortex.reader;

import io.github.dfa1.vortex.core.model.ArrayStats;

/// One row of a column's zone-map table, together with the rows of the column it describes.
///
/// @param firstRow first row of the column this zone covers
/// @param rowCount number of rows this zone covers
/// @param stats    the zone's statistics
public record Zone(long firstRow, long rowCount, ArrayStats stats) {
}
