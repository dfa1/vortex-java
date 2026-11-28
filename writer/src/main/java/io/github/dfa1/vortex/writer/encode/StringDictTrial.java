package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.EncodingId;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleSupplier;

/// Rust 0.95's string dictionary estimate (`vortex-compressor` `builtins/dict/bytes_candidate.rs`):
/// a bounded trial dictionary of the complete input, measured exactly, instead of a sample that
/// misses the repeats of a column with thousands of distinct values.
///
/// A probe of complete values from ranges spread over the input estimates the distinct count, and
/// a cost model turns it into an optimistic ratio: the trial runs only when that could beat the
/// best estimate so far. A probe over its byte budget, or a dictionary too large for the trial,
/// falls back to the ordinary sample estimate. The trial's output is kept on the input
/// ([ArrayAndStats#keepTrial]) for the compressor to reuse.
final class StringDictTrial {

    /// Entries a trial dictionary may hold, so its codes stay two bytes wide.
    private static final int MAX_DICTIONARY_ENTRIES = 1 << 16;
    /// The width of a trial dictionary's codes.
    private static final int TRIAL_CODE_BYTES = 2;
    /// Value and view storage a trial dictionary may hold.
    private static final long MAX_DICTIONARY_BYTES = 1L << 22;
    /// Code storage while the trial scans the complete input.
    private static final long MAX_DICTIONARY_CODE_BYTES = 1L << 24;
    /// Input bytes hashed while building a trial dictionary.
    private static final long MAX_DICTIONARY_INPUT_BYTES = 1L << 28;
    /// Value bytes the probe may hash.
    private static final long MAX_PROBE_BYTES = 1L << 20;
    /// Adjacent rows probed from each range.
    private static final int PROBE_RANGE_SIZE = 64;
    /// Ranges the probe spreads over the input.
    private static final int PROBE_RANGE_COUNT = 128;
    /// Adjacent rows per range of the head probe that screens out unique inputs.
    private static final int HEAD_PROBE_RANGE_SIZE = 16;
    /// Rows the head probe needs before it may reject an incomplete input.
    private static final int MIN_HEAD_PROBE_ROWS = 1024;
    /// The best a string scheme can compress distinct values: FSST 8 bytes to a 1-byte code,
    /// OnPair 16 to a 2-byte one.
    private static final double MAX_VALUES_RATIO = 8.0;
    /// `size_of::<BinaryView>()`.
    private static final int VIEW_BYTES = 16;
    /// Values up to this long live inside their view.
    private static final int MAX_INLINED = 12;

    private StringDictTrial() {
    }

    /// Resolves the deferred dictionary estimate: Rust's `estimate_bytes_dictionary`.
    ///
    /// @param dict       the dictionary encoder
    /// @param compressor the compressor the trial compresses the dictionary's children with
    /// @param dtype      the Utf8 dtype
    /// @param input      the input and the slot the trial output is kept in
    /// @param ctx        the encode context
    /// @param bestRatio  the best ratio so far, `null` when none, infinite when one sampled to
    ///                   no bytes
    /// @param sampled    the ordinary sample estimate's ratio, run on demand
    /// @return a ratio when the dictionary beats `bestRatio`, else [Estimate#SKIP]
    static Estimate estimate(EncodingEncoder dict, CascadingCompressor compressor, DType dtype, ArrayAndStats input,
                             EncodeContext ctx, Double bestRatio, DoubleSupplier sampled) {
        Profile profile = Profile.of(input.data());
        if (profile.validRows == 0) {
            return Estimate.SKIP;
        }
        Probe head = Probe.of(profile, HEAD_PROBE_RANGE_SIZE, ctx.sampleSeed());
        if (head != null && head.isUnique() && admit(profile, head, bestRatio) == Admission.REJECT) {
            return Estimate.SKIP;
        }
        Probe probe = Probe.of(profile, PROBE_RANGE_SIZE, ctx.sampleSeed());
        if (probe == null) {
            return verdict(sampled.getAsDouble(), bestRatio);
        }
        switch (admit(profile, probe, bestRatio)) {
            case REJECT -> {
                return Estimate.SKIP;
            }
            case TOO_LARGE -> {
                return verdict(sampled.getAsDouble(), bestRatio);
            }
            case TRIAL -> {
                // build it below
            }
        }
        if (!fitsTrial(profile)) {
            return verdict(sampled.getAsDouble(), bestRatio);
        }
        EncodeResult trial = compressor.encodeWith(dict, dtype, input.data(), ctx);
        if (!trial.rootNode().encodingId().equals(EncodingId.VORTEX_DICT)) {
            return verdict(sampled.getAsDouble(), bestRatio);
        }
        // kept even when it loses: the compressor prefers it to a winner that comes out larger
        input.keepTrial(EncodingId.VORTEX_DICT, trial);
        long bytes = CascadingCompressor.totalBytes(trial);
        double ratio = bytes == 0 ? Double.NaN : (double) profile.uncompressedBytes / bytes;
        return verdict(ratio, bestRatio);
    }

    /// `score_is_best` then `score_verdict`: a valid ratio strictly over the best so far.
    private static Estimate verdict(double ratio, Double bestRatio) {
        if (Double.isNaN(ratio) || Double.isInfinite(ratio) || (bestRatio != null && !(ratio > bestRatio))) {
            return Estimate.SKIP;
        }
        return Estimate.ratio(ratio);
    }

    private enum Admission { TRIAL, REJECT, TOO_LARGE }

    /// `admit_trial`: the cost model, then the trial's bounds.
    private static Admission admit(Profile profile, Probe probe, Double bestRatio) {
        if (bestRatio != null && Double.isInfinite(bestRatio)) {
            return Admission.REJECT;
        }
        double best = bestRatio == null ? 1.0 : Math.max(bestRatio, 1.0);
        double distinct = probe.estimatedDistinct(profile.validRows);
        double entries = distinct + (profile.validRows < profile.rows ? 1 : 0);
        double valuesRatio = Math.min(best, MAX_VALUES_RATIO);
        if (optimisticRatio(profile, probe, entries, valuesRatio) <= best) {
            return Admission.REJECT;
        }
        if (entries > MAX_DICTIONARY_ENTRIES
                || distinct * profile.meanValueBytes() > MAX_DICTIONARY_BYTES
                || profile.valueBytes > MAX_DICTIONARY_INPUT_BYTES
                || (long) profile.rows * TRIAL_CODE_BYTES > MAX_DICTIONARY_CODE_BYTES) {
            return Admission.TOO_LARGE;
        }
        return Admission.TRIAL;
    }

    /// `optimistic_ratio`: the values compressed by `valuesRatio`, the codes at their bit width
    /// over the runs the probe saw.
    private static double optimisticRatio(Profile profile, Probe probe, double entries, double valuesRatio) {
        double valuesBytes = entries * (profile.meanValueBytes() + VIEW_BYTES);
        double codeBits = Math.ceil(Math.log(Math.max(entries, 2.0)) / Math.log(2));
        double codeBytes = profile.rows * probe.runStartFraction() * codeBits / 8.0;
        return profile.uncompressedBytes / Math.max(valuesBytes / valuesRatio + codeBytes, 1.0);
    }

    /// `build_candidate`'s constraints: `dict_encode_with_constraints` stops at
    /// [#MAX_DICTIONARY_ENTRIES] entries or [#MAX_DICTIONARY_BYTES] of views and out-of-line
    /// values, the first entry always admitted, and a dictionary that stopped short is no trial.
    private static boolean fitsTrial(Profile profile) {
        Map<ByteBuffer, Boolean> seen = new HashMap<>();
        long bytes = 0;
        boolean nullEntry = false;
        for (int i = 0; i < profile.rows; i++) {
            long additional;
            if (!profile.valid(i)) {
                if (nullEntry) {
                    continue;
                }
                additional = VIEW_BYTES;
            } else {
                byte[] value = profile.values[i];
                if (seen.containsKey(ByteBuffer.wrap(value))) {
                    continue;
                }
                additional = VIEW_BYTES + (value.length > MAX_INLINED ? value.length : 0);
            }
            int entries = seen.size() + (nullEntry ? 1 : 0);
            if (entries > 0 && (entries >= MAX_DICTIONARY_ENTRIES || bytes + additional > MAX_DICTIONARY_BYTES)) {
                return false;
            }
            bytes += additional;
            if (profile.valid(i)) {
                seen.put(ByteBuffer.wrap(profile.values[i]), Boolean.TRUE);
            } else {
                nullEntry = true;
            }
        }
        return true;
    }

    /// `InputProfile`: the complete input, one pass over its rows.
    private record Profile(byte[][] values, boolean[] validity, int rows, int validRows, long valueBytes,
                           long uncompressedBytes) {

        static Profile of(Object data) {
            Object rowsData = data;
            boolean[] validity = null;
            if (data instanceof NullableData(Object values, boolean[] valid)) {
                rowsData = values;
                validity = valid;
            }
            byte[][] values = VarBinBytes.toByteArrays(rowsData);
            int validRows = 0;
            long valueBytes = 0;
            for (int i = 0; i < values.length; i++) {
                if (validity == null || validity[i]) {
                    validRows++;
                    valueBytes += values[i].length;
                }
            }
            long uncompressed = CascadingCompressor.viewBytes(data, values.length, 0);
            return new Profile(values, validity, values.length, validRows, valueBytes, uncompressed);
        }

        boolean valid(int i) {
            return validity == null || validity[i];
        }

        double meanValueBytes() {
            return (double) valueBytes / validRows;
        }
    }

    /// `ProbeSample`: complete values from ranges spread over the input.
    private record Probe(int rows, int distinct, int singletons, int doubletons, int adjacentPairs,
                         int adjacentChanges, boolean complete) {

        /// Probes `rangeSize` adjacent rows of each range, or `null` when they hold more than
        /// [#MAX_PROBE_BYTES] or no valid row.
        static Probe of(Profile profile, int rangeSize, long seed) {
            List<int[]> ranges = CascadingCompressor.sampleSlices(profile.rows, rangeSize, PROBE_RANGE_COUNT, seed);
            boolean complete = ranges.size() == 1 && ranges.getFirst()[0] == 0 && ranges.getFirst()[1] == profile.rows;
            long probeBytes = 0;
            for (int[] range : ranges) {
                for (int i = range[0]; i < range[1]; i++) {
                    if (profile.valid(i)) {
                        probeBytes += profile.values[i].length;
                    }
                }
            }
            if (probeBytes > MAX_PROBE_BYTES) {
                return null;
            }
            Map<ByteBuffer, int[]> counts = new HashMap<>();
            int pairs = 0;
            int changes = 0;
            for (int[] range : ranges) {
                boolean first = true;
                ByteBuffer previous = null;
                for (int i = range[0]; i < range[1]; i++) {
                    ByteBuffer value = profile.valid(i) ? ByteBuffer.wrap(profile.values[i]) : null;
                    if (!first) {
                        pairs++;
                        if (!java.util.Objects.equals(previous, value)) {
                            changes++;
                        }
                    }
                    first = false;
                    previous = value;
                    if (value != null) {
                        counts.computeIfAbsent(value, _ -> new int[1])[0]++;
                    }
                }
            }
            int rows = 0;
            int singletons = 0;
            int doubletons = 0;
            for (int[] count : counts.values()) {
                rows += count[0];
                if (count[0] == 1) {
                    singletons++;
                } else if (count[0] == 2) {
                    doubletons++;
                }
            }
            if (rows == 0) {
                return null;
            }
            return new Probe(rows, counts.size(), singletons, doubletons, pairs, changes, complete);
        }

        /// Every probed value distinct, in a probe large enough to tell.
        boolean isUnique() {
            return distinct == rows && (complete || rows >= MIN_HEAD_PROBE_ROWS);
        }

        /// The Chao1 estimate of the input's distinct valid values, which tends to under-count:
        /// that only costs a trial the dictionary then loses.
        double estimatedDistinct(int validRows) {
            if (complete) {
                return distinct;
            }
            double unseen;
            if (doubletons > 0) {
                unseen = (double) singletons * singletons / (2.0 * doubletons);
            } else {
                unseen = singletons * (singletons - 1.0) / 2.0;
            }
            return Math.min(distinct + unseen, validRows);
        }

        /// The share of rows that start a run of equal values, at least one per range.
        double runStartFraction() {
            if (adjacentPairs == 0) {
                return 1.0;
            }
            return Math.max((double) adjacentChanges / adjacentPairs, 1.0 / PROBE_RANGE_SIZE);
        }
    }
}
