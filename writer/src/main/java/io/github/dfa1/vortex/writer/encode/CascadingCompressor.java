package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.EncodingId;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.IntToLongFunction;

/// Cascading compressor: evaluates multiple encodings on a sample and picks the one
/// producing the smallest output. With `allowedCascading > 0`, also recurses
/// into open child slots (e.g. ALP → Bitpacked for F64 columns).
///
/// Encodings that override [EncodingEncoder#encodeCascade] expose intermediate
/// representations as open children; encodings that use the default are terminal.
/// At depth 0 only terminal encodings are considered.
public final class CascadingCompressor {

    private final List<EncodingEncoder> encodings;

    /// Constructs a `CascadingCompressor` with the given candidate encoders.
    ///
    /// @param encodings candidate encoders evaluated during compression
    public CascadingCompressor(List<EncodingEncoder> encodings) {
        this.encodings = List.copyOf(encodings);
    }

    private static int dataLength(Object data) {
        return switch (data) {
            case StructData(var fieldArrays) -> fieldArrays.isEmpty() ? 0 : dataLength(fieldArrays.getFirst());
            case byte[] a -> a.length;
            case short[] a -> a.length;
            case int[] a -> a.length;
            case long[] a -> a.length;
            case float[] a -> a.length;
            case double[] a -> a.length;
            case String[] a -> a.length;
            default -> throw new IllegalArgumentException("unsupported data type: " + data.getClass());
        };
    }

    private static final int STRIDE_COUNT = 32;

    /// Build a stratified sample: pick `STRIDE_COUNT` contiguous strides at random
    /// offsets, concatenated. Preserves local run structure (so RunEnd/RLE can win)
    /// while covering breadth (so cardinality-based encoders see realistic distinct counts).
    /// Falls back to first-N when the data is short enough for one stride to span it.
    private static Object stratifiedSample(Object data, int sampleSize, long seed) {
        return switch (data) {
            case StructData(var fieldArrays) -> {
                List<Object> sliced = fieldArrays.stream()
                        .map(f -> stratifiedSample(f, sampleSize, seed)).toList();
                yield new StructData(sliced);
            }
            case byte[] a -> {
                byte[] out = new byte[sampleSize];
                forEachStride(a.length, sampleSize, seed, (srcOff, dstOff, len) ->
                        System.arraycopy(a, srcOff, out, dstOff, len));
                yield out;
            }
            case short[] a -> {
                short[] out = new short[sampleSize];
                forEachStride(a.length, sampleSize, seed, (srcOff, dstOff, len) ->
                        System.arraycopy(a, srcOff, out, dstOff, len));
                yield out;
            }
            case int[] a -> {
                int[] out = new int[sampleSize];
                forEachStride(a.length, sampleSize, seed, (srcOff, dstOff, len) ->
                        System.arraycopy(a, srcOff, out, dstOff, len));
                yield out;
            }
            case long[] a -> {
                long[] out = new long[sampleSize];
                forEachStride(a.length, sampleSize, seed, (srcOff, dstOff, len) ->
                        System.arraycopy(a, srcOff, out, dstOff, len));
                yield out;
            }
            case float[] a -> {
                float[] out = new float[sampleSize];
                forEachStride(a.length, sampleSize, seed, (srcOff, dstOff, len) ->
                        System.arraycopy(a, srcOff, out, dstOff, len));
                yield out;
            }
            case double[] a -> {
                double[] out = new double[sampleSize];
                forEachStride(a.length, sampleSize, seed, (srcOff, dstOff, len) ->
                        System.arraycopy(a, srcOff, out, dstOff, len));
                yield out;
            }
            case String[] a -> {
                String[] out = new String[sampleSize];
                forEachStride(a.length, sampleSize, seed, (srcOff, dstOff, len) ->
                        System.arraycopy(a, srcOff, out, dstOff, len));
                yield out;
            }
            default -> throw new IllegalArgumentException("unsupported data type: " + data.getClass());
        };
    }

    @FunctionalInterface
    private interface StrideCopy {
        void copy(int srcOff, int dstOff, int len);
    }

    /// Rust-style partitioned stratified sample (vortex-compressor::sample::stratified_slices):
    /// divide [0, n) into `strideCount` contiguous partitions, draw one random contiguous
    /// slice from each. Strides cannot overlap or cluster — every region is represented.
    @SuppressWarnings("java:S2245") // Deterministic PRNG is the contract: stratified sampling
                                    // must be reproducible across builds for stable compression
                                    // heuristics. No security boundary — output is sample indices.
    private static void forEachStride(int n, int sampleSize, long seed, StrideCopy copier) {
        int strideCount = Math.min(STRIDE_COUNT, sampleSize);
        Random rng = new Random(seed);
        int dstOff = 0;
        int partRemainder = n % strideCount;
        int partShortStep = n / strideCount;
        int partLongStep = partShortStep + 1;
        int sampleRemainder = sampleSize % strideCount;
        int sampleShortStep = sampleSize / strideCount;
        int sampleLongStep = sampleShortStep + 1;
        for (int s = 0; s < strideCount; s++) {
            int partStart = s * partShortStep + Math.min(s, partRemainder);
            int partLen = s < partRemainder ? partLongStep : partShortStep;
            int sampleLen = s < sampleRemainder ? sampleLongStep : sampleShortStep;
            int maxStart = Math.max(0, partLen - sampleLen);
            int offsetInPart = maxStart == 0 ? 0 : rng.nextInt(maxStart + 1);
            int srcOff = partStart + offsetInPart;
            copier.copy(srcOff, dstOff, sampleLen);
            dstOff += sampleLen;
        }
    }

    private static long primitiveBytes(DType dtype, int n) {
        if (!(dtype instanceof DType.Primitive p)) {
            return (long) n * 8;
        }
        return (long) n * p.ptype().byteSize();
    }

    /// Entry point: encode `data` using the best cascading strategy.
    ///
    /// Cascade parameters (depth, sampling, exclusions) are taken from `ctx`.
    /// Use [EncodeContext#ofDepth(int, Arena, WriteRegistry)]
    /// to build a context with cascade depth set.
    ///
    /// @param dtype the logical type of the data to encode
    /// @param data  input data in the format expected by the candidate encodings
    /// @param ctx   encoding context supplying the arena, encoder map, and cascade parameters
    /// @return the [EncodeResult] produced by the winning encoding
    public EncodeResult encode(DType dtype, Object data, EncodeContext ctx) {
        return encodeWithCtx(dtype, data, ctx);
    }

    private EncodeResult encodeWithCtx(DType dtype, Object data, EncodeContext ctx) {
        if (dtype instanceof DType.Struct structDtype) {
            return encodeStruct(structDtype, (StructData) data, ctx);
        }

        // Utf8: same sample-and-measure competition as Primitive below (Dict, FSST, VarBin,
        // Zstd all genuinely compete on measured size) rather than the extension-type
        // first-match dispatch. No stats are computed — DictEncodingEncoder.expectedRatio()
        // already defers to this path for Utf8 rather than consuming them — and there is no
        // cheap analytic "no compression" baseline the way primitiveBytes is for fixed-width
        // types, so the competition simply keeps whichever accepting encoder measures
        // smallest (VarBinEncodingEncoder unconditionally accepts Utf8, so a winner always
        // exists in practice).
        if (dtype instanceof DType.Utf8) {
            return competeAndEncode(dtype, data, ctx, ArrayStats.EMPTY, sampleSize -> Long.MAX_VALUE);
        }

        // Remaining non-primitives (extension types, Binary, List, ...): find the accepting
        // encoding and splice through it so its cascaded children (e.g. datetimeparts →
        // days/seconds/subseconds) are recursively compressed rather than stored as raw
        // primitives. Honor the excluded set so spliceResult's notApplicable retry can rotate
        // to the next accepting encoding (e.g. DateTimePartsEncoding → ExtEncoding when the
        // input is raw storage rather than DateTimePartsData).
        if (!(dtype instanceof DType.Primitive p)) {
            return spliceResult(findPrimitiveEncoding(dtype, ctx.excluded()), dtype, data, ctx);
        }

        // Stats-first selection (Rust vortex-compressor pattern): merge every eligible
        // encoder's StatsOptions, compute stats in one pass, query each encoder's
        // expectedRatio(). ALWAYS_USE short-circuits; SKIP excludes; COMPLETE defers
        // to the sample-encoded path below.
        StatsOptions merged = StatsOptions.NONE;
        for (EncodingEncoder enc : encodings) {
            if (enc.accepts(dtype) && !ctx.excluded().contains(enc.encodingId())) {
                merged = StatsOptions.merge(merged, enc.statsOptions());
            }
        }
        ArrayStats stats = merged == StatsOptions.NONE
                                   ? ArrayStats.EMPTY
                                   : ArrayStats.compute(p.ptype(), data, merged);

        return competeAndEncode(dtype, data, ctx, stats, sampleSize -> primitiveBytes(dtype, sampleSize));
    }

    /// Shared sample-and-measure competition: stats-based skip/always-use sweep, then a
    /// stratified-sample cost measurement across every remaining accepting encoder, picking
    /// whichever measures smallest against `baselineFn`'s reference size.
    ///
    /// @param dtype      the logical type of the data to encode
    /// @param data       the full input data
    /// @param ctx        encoding context supplying the arena, encoder map, and cascade parameters
    /// @param stats      pre-computed stats for the [EncodingEncoder#expectedRatio] sweep,
    ///                   or [ArrayStats#EMPTY] when the dtype has no stats support
    /// @param baselineFn given the sample size, returns the reference size a candidate must
    ///                   beat to win
    /// @return the [EncodeResult] produced by the winning encoding
    private EncodeResult competeAndEncode(
            DType dtype, Object data, EncodeContext ctx, ArrayStats stats, IntToLongFunction baselineFn
    ) {
        // First sweep: stats verdicts. ALWAYS_USE short-circuits; SKIP excludes from
        // the sample-encoded competition below; COMPLETE defers to it.
        boolean[] skipMask = new boolean[encodings.size()];
        for (int i = 0; i < encodings.size(); i++) {
            EncodingEncoder enc = encodings.get(i);
            if (!enc.accepts(dtype) || ctx.excluded().contains(enc.encodingId())) {
                skipMask[i] = true;
                continue;
            }
            Estimate est = enc.expectedRatio(dtype, data, stats);
            if (est == Estimate.ALWAYS_USE) {
                return spliceResult(enc, dtype, data, ctx);
            }
            if (est == Estimate.SKIP) {
                skipMask[i] = true;
            }
        }

        int n = dataLength(data);

        // Build sample
        int sampleSize = (int) Math.max(ctx.minSampleSize(), Math.ceil(n * ctx.sampleFraction()));
        sampleSize = Math.min(sampleSize, n);
        Object sample = (sampleSize < n) ? stratifiedSample(data, sampleSize, ctx.sampleSeed()) : data;

        long bestSampleSize = baselineFn.applyAsLong(sampleSize);
        EncodingEncoder winner = null;

        for (int i = 0; i < encodings.size(); i++) {
            if (skipMask[i]) {
                continue;
            }
            EncodingEncoder enc = encodings.get(i);
            CascadeStep step = enc.encodeCascade(dtype, sample, ctx);

            // At depth 0, skip encodings that require cascade
            if (!step.isTerminal() && ctx.allowedCascading() == 0) {
                continue;
            }

            long size = measureStep(enc, step, ctx);
            if (size < bestSampleSize) {
                bestSampleSize = size;
                winner = enc;
            }
        }

        if (winner == null) {
            // No encoding beats the baseline — fall back to the first accepting encoder
            return spliceResult(findPrimitiveEncoding(dtype, ctx.excluded()), dtype, data, ctx);
        }

        // Re-run winner on full data
        return spliceResult(winner, dtype, data, ctx);
    }

    private long measureStep(EncodingEncoder enc, CascadeStep step, EncodeContext ctx) {
        long total = step.ownedBytes();
        for (ChildSlot slot : step.openChildren()) {
            EncodeContext childCtx = ctx.withDecrementedDepth().withExcluded(enc.encodingId());
            total += measureBestChild(slot.childDtype(), slot.childData(), childCtx);
        }
        return total;
    }

    private long measureBestChild(DType dtype, Object data, EncodeContext ctx) {
        int n = dataLength(data);
        long best = primitiveBytes(dtype, n);
        for (EncodingEncoder enc : encodings) {
            if (!enc.accepts(dtype) || ctx.excluded().contains(enc.encodingId())) {
                continue;
            }
            CascadeStep step = enc.encodeCascade(dtype, data, ctx);
            if (!step.isTerminal() && ctx.allowedCascading() == 0) {
                continue;
            }
            long size = measureStep(enc, step, ctx);
            if (size < best) {
                best = size;
            }
        }
        return best;
    }

    private EncodeResult spliceResult(EncodingEncoder winner, DType dtype, Object data, EncodeContext ctx) {
        CascadeStep step = winner.encodeCascade(dtype, data, ctx);

        if (!step.applicable()) {
            // Winner was selected on a sample that looked applicable (e.g. all-constant prefix),
            // but full data is not. Re-run without this encoding.
            return encodeWithCtx(dtype, data, ctx.withExcluded(winner.encodingId()));
        }

        if (step.isTerminal()) {
            return new EncodeResult(step.partialRoot(), step.ownedBuffers(), step.statsMin(), step.statsMax());
        }

        List<MemorySegment> allBuffers = new ArrayList<>(step.ownedBuffers());
        EncodeNode[] children = step.partialRoot().children().clone();

        for (ChildSlot slot : step.openChildren()) {
            EncodeContext childCtx = ctx.withDecrementedDepth().withExcluded(winner.encodingId());
            EncodeResult childResult = encodeWithCtx(slot.childDtype(), slot.childData(), childCtx);

            int bufOffset = allBuffers.size();
            children[slot.parentChildIdx()] = EncodeNode.remapBufferIndices(childResult.rootNode(), bufOffset);
            allBuffers.addAll(childResult.buffers());
        }

        EncodeNode root = new EncodeNode(
                step.partialRoot().encodingId(),
                step.partialRoot().metadata(),
                children,
                step.partialRoot().bufferIndices());
        return new EncodeResult(root, List.copyOf(allBuffers), step.statsMin(), step.statsMax());
    }

    private EncodeResult encodeStruct(DType.Struct dtype, StructData data, EncodeContext ctx) {
        List<Object> fields = data.fieldArrays();
        List<DType> fieldTypes = dtype.fieldTypes();
        List<MemorySegment> allBuffers = new ArrayList<>();
        EncodeNode[] children = new EncodeNode[fields.size()];
        for (int i = 0; i < fields.size(); i++) {
            DType fieldDtype = fieldTypes.get(i);
            Object fieldData = fields.get(i);
            // Mirrors StructEncodingEncoder's own field loop: a nullable field arrives as
            // NullableData(values, validity), not the dense array encodeWithCtx's per-dtype
            // dispatch expects (e.g. VarBinEncodingEncoder casts data straight to String[]).
            EncodeResult fieldResult = (fieldData instanceof NullableData && !(fieldDtype instanceof DType.Extension))
                    ? new MaskedEncodingEncoder().encode(fieldDtype, fieldData, ctx)
                    : encodeWithCtx(fieldDtype, fieldData, ctx);
            int bufOffset = allBuffers.size();
            children[i] = EncodeNode.remapBufferIndices(fieldResult.rootNode(), bufOffset);
            allBuffers.addAll(fieldResult.buffers());
        }
        EncodeNode root = new EncodeNode(EncodingId.VORTEX_STRUCT, null, children, new int[0]);
        return new EncodeResult(root, List.copyOf(allBuffers), null, null);
    }

    private EncodingEncoder findPrimitiveEncoding(DType dtype, Set<EncodingId> excluded) {
        for (EncodingEncoder enc : encodings) {
            if (excluded.contains(enc.encodingId())) {
                continue;
            }
            if (enc.encodingId().equals(EncodingId.VORTEX_PRIMITIVE) && enc.accepts(dtype)) {
                return enc;
            }
        }
        // Fall through to any accepting encoding (still honoring exclusions so that
        // spliceResult's notApplicable retry rotates to the next candidate).
        for (EncodingEncoder enc : encodings) {
            if (excluded.contains(enc.encodingId())) {
                continue;
            }
            if (enc.accepts(dtype)) {
                return enc;
            }
        }
        throw new UnsupportedOperationException("no encoder for dtype: " + dtype);
    }
}
