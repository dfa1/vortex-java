package io.github.dfa1.vortex.writer.encode;

/// Stats requested by an [EncodingEncoder] for cascade selection. The cascading
/// compressor merges every eligible encoder's options before scanning the input once.
///
/// Mirrors `vortex-compressor::stats::options::GenerateStatsOptions`.
///
/// A plain class like the other options types: start from [#defaults()] and adjust with `withXxx`.
public final class StatsOptions {

    private final boolean countDistinct;
    private final boolean trackMostFrequent;

    private StatsOptions(boolean countDistinct, boolean trackMostFrequent) {
        this.countDistinct = countDistinct;
        this.trackMostFrequent = trackMostFrequent;
    }

    /// No stats required.
    ///
    /// @return options requesting no stats
    public static StatsOptions defaults() {
        return new StatsOptions(false, false);
    }

    /// Track both distinct counts and most-frequent value.
    ///
    /// @return options requesting distinct counts and the most-frequent value
    public static StatsOptions distinctAndTop() {
        return new StatsOptions(true, true);
    }

    /// Whether the scan must count distinct value occurrences.
    ///
    /// @return `true` if distinct counts are requested
    public boolean countDistinct() {
        return countDistinct;
    }

    /// Whether the scan must track the most-frequent value and its count.
    ///
    /// @return `true` if the most-frequent value is requested
    public boolean trackMostFrequent() {
        return trackMostFrequent;
    }

    /// Returns a copy with distinct counting set to `enabled`.
    ///
    /// @param enabled `true` to count distinct value occurrences
    /// @return a copy with the flag updated
    public StatsOptions withCountDistinct(boolean enabled) {
        return new StatsOptions(enabled, trackMostFrequent);
    }

    /// Returns a copy with most-frequent tracking set to `enabled`.
    ///
    /// @param enabled `true` to track the most-frequent value and its count
    /// @return a copy with the flag updated
    public StatsOptions withTrackMostFrequent(boolean enabled) {
        return new StatsOptions(countDistinct, enabled);
    }

    /// Merge two option sets by OR-ing each field.
    ///
    /// @param a first option set
    /// @param b second option set
    /// @return merged options enabling any stat enabled in either input
    public static StatsOptions merge(StatsOptions a, StatsOptions b) {
        return new StatsOptions(
                a.countDistinct || b.countDistinct,
                a.trackMostFrequent || b.trackMostFrequent);
    }
}
