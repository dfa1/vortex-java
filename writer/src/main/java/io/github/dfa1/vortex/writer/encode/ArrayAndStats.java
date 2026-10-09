package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.model.DType;

/// An array bundled with its lazily computed [ArrayStats]: Rust's `ArrayAndStats`
/// (`vortex-compressor/src/stats/cache.rs`).
///
/// The cascade hands one of these to every candidate's [EncodingEncoder#expectedRatio]. The stats
/// scan runs on the first [#stats()] call, with the [StatsOptions] merged from every candidate,
/// and is shared by every later call — so a verdict reached without stats never pays for the
/// scan, and one scan still serves every candidate that does read them.
///
/// The stats belong to this array only: an encoding that derives a new array (a FoR-shifted
/// child, ALP's encoded integers) must wrap it in a new [ArrayAndStats] rather than reuse these.
///
/// Not thread-safe; one cascade step owns it.
public final class ArrayAndStats {

    private final DType dtype;
    private final Object data;
    private final StatsOptions options;
    private ArrayStats stats;

    /// Bundles `data` with stats computed on demand under `options`.
    ///
    /// @param dtype   the logical type of `data`
    /// @param data    the input array
    /// @param options the stats the first [#stats()] call computes
    public ArrayAndStats(DType dtype, Object data, StatsOptions options) {
        this.dtype = dtype;
        this.data = data;
        this.options = options;
    }

    /// @return the input array
    public Object data() {
        return data;
    }

    /// The array's stats, computed on the first call and cached.
    ///
    /// Only primitive arrays have them; any other type reports [ArrayStats#EMPTY], which every
    /// stats consumer already treats as "decide by sample" for a non-primitive dtype.
    ///
    /// @return the stats under this bundle's [StatsOptions]
    public ArrayStats stats() {
        if (stats == null) {
            stats = dtype instanceof DType.Primitive p
                    ? ArrayStats.compute(p.ptype(), data, options)
                    : ArrayStats.EMPTY;
        }
        return stats;
    }
}
