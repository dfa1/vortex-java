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
        event.dtype = dtypeName(dtype);
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

    /// A dtype as Rust displays it: `i64`, `utf8?` for a nullable one, `list(i64)`, `struct{a: i64}`.
    ///
    /// @param dtype the dtype
    /// @return its short name
    static String dtypeName(DType dtype) {
        String name = switch (dtype) {
            case DType.Null _ -> "null";
            case DType.Bool _ -> "bool";
            case DType.Primitive p -> p.ptype().name().toLowerCase();
            case DType.Decimal d -> "decimal(" + d.precision() + "," + d.scale() + ")";
            case DType.Utf8 _ -> "utf8";
            case DType.Binary _ -> "binary";
            case DType.List l -> "list(" + dtypeName(l.elementType()) + ")";
            case DType.FixedSizeList l -> "fixed_size_list(" + dtypeName(l.elementType()) + "," + l.fixedSize() + ")";
            case DType.Map m -> "map(" + dtypeName(m.keyType()) + "," + dtypeName(m.valueType()) + ")";
            case DType.Struct s -> structName(s);
            case DType.Extension e -> "ext(" + e.extensionId().id() + ")";
            default -> dtype.getClass().getSimpleName().toLowerCase();
        };
        if (dtype.nullable()) {
            return name + "?";
        }
        return name;
    }

    private static String structName(DType.Struct struct) {
        var sb = new StringBuilder("struct{");
        for (int i = 0; i < struct.fieldNames().size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(struct.fieldNames().get(i).value()).append(": ").append(dtypeName(struct.fieldTypes().get(i)));
        }
        return sb.append('}').toString();
    }

    private static double ratio(long input, long compressed) {
        if (input <= 0 || compressed <= 0) {
            return Double.NaN;
        }
        return (double) input / compressed;
    }
}
