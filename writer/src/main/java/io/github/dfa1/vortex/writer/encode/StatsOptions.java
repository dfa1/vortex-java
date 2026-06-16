package io.github.dfa1.vortex.writer.encode;

/// Stats requested by an [EncodingEncoder] for cascade selection. The cascading
/// compressor merges every eligible encoder's options before scanning the input once.
///
/// Mirrors `vortex-compressor::stats::options::GenerateStatsOptions`.
///
/// @param countDistinct      whether the scan must count distinct value occurrences
/// @param trackMostFrequent  whether the scan must track the most-frequent value + its count
public record StatsOptions(boolean countDistinct, boolean trackMostFrequent) {

    /// No stats required.
    public static final StatsOptions NONE = new StatsOptions(false, false);

    /// Track both distinct counts and most-frequent value.
    public static final StatsOptions DISTINCT_AND_TOP = new StatsOptions(true, true);

    /// Merge two option sets by OR-ing each field.
    ///
    /// @param a first option set
    /// @param b second option set
    /// @return merged options enabling any stat enabled in either input
    public static StatsOptions merge(StatsOptions a, StatsOptions b) {
        if (a == NONE) {
            return b;
        }
        if (b == NONE) {
            return a;
        }
        return new StatsOptions(
                a.countDistinct || b.countDistinct,
                a.trackMostFrequent || b.trackMostFrequent);
    }
}
