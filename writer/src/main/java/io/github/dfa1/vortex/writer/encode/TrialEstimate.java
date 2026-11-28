package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.model.DType;

import java.util.function.DoubleSupplier;

/// Rust's `DeferredEstimate::Callback`: an estimate the compressor resolves after every sampled
/// one, handing it the best ratio so far so it can skip work that cannot win. The string
/// dictionary's trial ([StringDictTrial]) is one.
///
/// @param trial resolves the estimate
record TrialEstimate(Trial trial) implements Estimate {

    /// Resolves a deferred estimate.
    @FunctionalInterface
    interface Trial {

        /// @param compressor the compressor running the selection
        /// @param dtype      the input's dtype
        /// @param input      the input, which keeps a trial's output
        /// @param ctx        the encode context
        /// @param bestRatio  the best ratio so far, `null` when there is none, infinite when one
        ///                   sampled to no bytes
        /// @param sampled    the ordinary sample estimate's ratio, run on demand
        /// @return a [Estimate.Ratio] or [Estimate#SKIP]
        Estimate run(CascadingCompressor compressor, DType dtype, ArrayAndStats input, EncodeContext ctx,
                     Double bestRatio, DoubleSupplier sampled);
    }
}
