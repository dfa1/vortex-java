package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.jfr.CandidateEvent;
import io.github.dfa1.vortex.core.jfr.CompressEvent;
import io.github.dfa1.vortex.core.jfr.WinnerEvent;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.EncodingId;

/// Fills and commits the [CascadingCompressor]'s JFR events, mirroring the spans of Rust's
/// `vortex-compressor/src/trace.rs`. Every method returns at once when recording is off, so the
/// strings (dtype, cascade path) are built only for an event that is kept.
final class CompressorEvents {

    private CompressorEvents() {
    }

    /// Commits a `Compress` event for an array the cascade encoded.
    static void compress(CompressEvent event, EncodeContext ctx, DType dtype, Object data, EncodeResult result) {
        if (!event.shouldCommit()) {
            return;
        }
        EncodeTrace trace = ctx.trace();
        event.column = trace.column();
        event.segment = trace.segment();
        event.cascadePath = trace.pathString();
        event.cascadeDepth = trace.depth();
        event.dtype = dtype.toString();
        event.arrayLen = RowCount.of(data);
        event.inputNbytes = CascadingCompressor.inputBytes(dtype, data);
        event.compressedNbytes = CascadingCompressor.totalBytes(result);
        event.compressionRatio = ratio(event.inputNbytes, event.compressedNbytes);
        event.commit();
    }

    /// What the compressor learned about a scheme for an array.
    ///
    /// @param scheme         the encoding evaluated
    /// @param verdict        `skip`, `always_use`, `estimate`, `measured` or `cascade_exhausted`
    /// @param sample         whether the numbers are on the sample
    /// @param baseline       the uncompressed size to beat
    /// @param estimatedRatio the stats estimate, `NaN` unless estimated
    /// @param sampleBytes    the trial encode's size, `-1` unless measured
    record Evaluation(EncodingId scheme, String verdict, boolean sample, long baseline,
            double estimatedRatio, long sampleBytes) {
    }

    /// How a chosen scheme fared on the full array.
    ///
    /// @param scheme          the encoding picked
    /// @param estimatedRatio  the ratio it was picked at, `NaN` when always-use
    /// @param estimated       whether it won on its stats estimate
    /// @param compressedBytes the size it encoded to, `-1` if it failed
    /// @param accepted        whether the result was kept
    /// @param error           why it was dropped, empty otherwise
    record Outcome(EncodingId scheme, double estimatedRatio, boolean estimated, long compressedBytes,
            boolean accepted, String error) {
    }

    /// Commits a `Candidate` event for a scheme evaluated on an array.
    static void candidate(CandidateEvent event, EncodeContext ctx, Evaluation eval) {
        if (!event.shouldCommit()) {
            return;
        }
        EncodeTrace trace = ctx.trace();
        event.column = trace.column();
        event.segment = trace.segment();
        event.cascadePath = trace.pathString();
        event.cascadeDepth = trace.depth();
        event.parent = trace.parentString();
        event.schemeCandidate = eval.scheme().id();
        event.verdict = eval.verdict();
        event.sample = eval.sample();
        event.baselineNbytes = eval.baseline();
        event.estimatedRatio = eval.estimatedRatio();
        event.sampleNbytes = eval.sampleBytes();
        event.commit();
    }

    /// Commits a `Winner` event for the scheme picked for an array.
    static void winner(WinnerEvent event, EncodeContext ctx, DType dtype, Object data, Outcome outcome) {
        if (!event.shouldCommit()) {
            return;
        }
        EncodeTrace trace = ctx.trace();
        event.column = trace.column();
        event.segment = trace.segment();
        event.cascadePath = trace.pathString();
        event.cascadeDepth = trace.depth();
        event.parent = trace.parentString();
        event.schemeChosen = outcome.scheme().id();
        event.inputNbytes = CascadingCompressor.inputBytes(dtype, data);
        event.compressedNbytes = outcome.compressedBytes();
        event.estimatedRatio = outcome.estimatedRatio();
        event.achievedRatio = ratio(event.inputNbytes, outcome.compressedBytes());
        event.accepted = outcome.accepted();
        event.estimated = outcome.estimated();
        event.error = outcome.error();
        event.commit();
    }

    private static double ratio(long input, long compressed) {
        if (input <= 0 || compressed <= 0) {
            return Double.NaN;
        }
        return (double) input / compressed;
    }
}
