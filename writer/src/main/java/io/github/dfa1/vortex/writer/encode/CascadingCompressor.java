package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.EncodingId;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.IntToLongFunction;
import java.util.function.Supplier;

/// Cascading compressor: evaluates multiple encodings on a sample and picks the one
/// producing the smallest output. With `allowedCascading > 0`, also recurses
/// into open child slots (e.g. ALP → Bitpacked for F64 columns).
///
/// Encodings that override [EncodingEncoder#encodeCascade] expose intermediate
/// representations as open children; encodings that use the default are terminal.
/// At depth 0 only terminal encodings are considered.
public final class CascadingCompressor {

    private static final EncodingEncoder CANONICAL_PRIMITIVE = new PrimitiveEncodingEncoder();
    private static final EncodingEncoder CANONICAL_BOOL = new BoolEncodingEncoder();
    private static final EncodingEncoder CANONICAL_VARBIN = new VarBinEncodingEncoder();
    private static final EncodingEncoder CANONICAL_DECIMAL = new DecimalEncodingEncoder();
    private static final EncodingEncoder CANONICAL_EXTENSION = new ExtEncodingEncoder();

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
            case byte[][] a -> a.length;
            default -> throw new IllegalArgumentException("unsupported data type: " + data.getClass());
        };
    }

    /// Rows per sample stride: Rust's `SAMPLE_SIZE`.
    private static final int SAMPLE_STRIDE = 64;

    /// Strides are taken in multiples of this many: Rust rounds its sample count up to a multiple
    /// of 16 (`next_multiple_of(.., 16)`), so `16 * SAMPLE_STRIDE` keeps every sample a multiple of
    /// 1024 rows.
    private static final int STRIDE_MULTIPLE = 16;

    /// Rust's `sample_count_approx_one_percent` scaled by `ctx`: about `sampleFraction` of `n` in
    /// [#SAMPLE_STRIDE]-row strides, the stride count rounded up to [#STRIDE_MULTIPLE] and at least
    /// `minSampleSize` rows. The sample is then always a multiple of 1024 rows, as in Rust. Taking
    /// exactly 1% instead (2622 of 262144 rows) penalized FastLanes bit-packing, which pads to
    /// 1024-value blocks: on such a sample 7-bit codes measured larger than raw bytes and lost,
    /// though they win on the full chunk (#458).
    private static int sampleSize(int n, EncodeContext ctx) {
        long strides = (long) (n * ctx.sampleFraction()) / SAMPLE_STRIDE;
        strides = (strides + STRIDE_MULTIPLE - 1) / STRIDE_MULTIPLE * STRIDE_MULTIPLE;
        strides = Math.max(strides, Math.max(1, ctx.minSampleSize() / SAMPLE_STRIDE));
        return (int) Math.min(strides * SAMPLE_STRIDE, n);
    }

    /// Build a stratified sample: pick [#SAMPLE_STRIDE]-row contiguous strides at random
    /// offsets, concatenated. Preserves local run structure (so RunEnd/RLE can win)
    /// while covering breadth (so cardinality-based encoders see realistic distinct counts).
    /// Falls back to first-N when the data is short enough for one stride to span it.
    static Object stratifiedSample(Object data, int sampleSize, long seed) {
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
            case byte[][] a -> {
                byte[][] out = new byte[sampleSize][];
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
    ///
    /// The draw is Rust's, bit for bit ([SampleRng]), including the draw for a partition with no
    /// slack: the sample picks the winner on borderline arrays, so it has to be the same sample.
    private static void forEachStride(int n, int sampleSize, long seed, StrideCopy copier) {
        int strideCount = Math.max(1, sampleSize / SAMPLE_STRIDE);
        SampleRng rng = new SampleRng(seed);
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
            int offsetInPart = rng.nextIntInclusive(0, maxStart);
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
            return data instanceof NullableData(StructData fields, boolean[] validity)
                    ? encodeStruct(structDtype, fields, validity, ctx)
                    : encodeStruct(structDtype, (StructData) data, null, ctx);
        }

        // Utf8/Binary: same sample-and-measure competition as Primitive below (Dict/VarBin
        // compete on Utf8 too; FSST, VarBinView, and Zstd compete on both — all genuinely measured)
        // rather than the extension-type first-match dispatch. No stats are computed —
        // DictEncodingEncoder.expectedRatio() already defers to this path for Utf8 rather than
        // consuming them, Binary isn't a Dict candidate at all — and there is no cheap analytic
        // "no compression" baseline the way primitiveBytes is for fixed-width types, so the
        // competition simply keeps whichever accepting encoder measures smallest
        // (VarBinEncodingEncoder unconditionally accepts both, so a winner always exists in
        // practice).
        if (dtype instanceof DType.Utf8 || dtype instanceof DType.Binary) {
            return competeAndEncode(dtype, new ArrayAndStats(dtype, data, StatsOptions.defaults()), ctx,
                    sampleSize -> Long.MAX_VALUE);
        }

        // Remaining non-primitives (extension types, List, ...): find the accepting encoding and
        // splice through it so its cascaded children (e.g. datetimeparts → days/seconds/
        // subseconds) are recursively compressed rather than stored as raw primitives. Honor the
        // excluded set so spliceResult's notApplicable retry can rotate to the next accepting
        // encoding (e.g. DateTimePartsEncoding → ExtEncoding when the input is raw storage rather
        // than DateTimePartsData).
        if (!(dtype instanceof DType.Primitive)) {
            return spliceResult(findPrimitiveEncoding(dtype, ctx.excluded()), dtype, data, ctx);
        }

        return competeAndEncode(dtype, withStats(dtype, data, ctx), ctx,
                sampleSize -> primitiveBytes(dtype, sampleSize));
    }

    /// Bundles `data` with stats under the [StatsOptions] merged from every eligible encoder,
    /// so the one scan, run lazily by the first encoder whose expectedRatio() reads stats,
    /// satisfies every later one (Rust vortex-compressor pattern).
    private ArrayAndStats withStats(DType dtype, Object data, EncodeContext ctx) {
        StatsOptions merged = StatsOptions.defaults();
        for (EncodingEncoder enc : encodings) {
            if (enc.accepts(dtype) && !ctx.excluded().contains(enc.encodingId())) {
                merged = StatsOptions.merge(merged, enc.statsOptions());
            }
        }
        return new ArrayAndStats(dtype, data, merged);
    }

    /// Whether `dtype` goes through the competition ([#competeAndEncode]) rather than a
    /// first-match dispatch — and so whether stats verdicts apply to it.
    private static boolean competes(DType dtype) {
        return dtype instanceof DType.Primitive || dtype instanceof DType.Utf8 || dtype instanceof DType.Binary;
    }

    /// Shared sample-and-measure competition: stats-based skip/always-use sweep, then a
    /// stratified-sample cost measurement across every remaining accepting encoder, picking
    /// whichever measures smallest against `baselineFn`'s reference size.
    ///
    /// @param dtype      the logical type of the data to encode
    /// @param input      the full input data with its lazily computed stats for the
    ///                   [EncodingEncoder#expectedRatio] sweep
    /// @param ctx        encoding context supplying the arena, encoder map, and cascade parameters
    /// @param baselineFn given the sample size, returns the reference size a candidate must
    ///                   beat to win
    /// @return the [EncodeResult] produced by the winning encoding
    private EncodeResult competeAndEncode(
            DType dtype, ArrayAndStats input, EncodeContext ctx, IntToLongFunction baselineFn
    ) {
        Object data = input.data();
        int n = dataLength(data);
        int sampleSize = sampleSize(n, ctx);
        Choice choice = chooseBest(dtype, input, ctx, baselineFn.applyAsLong(sampleSize),
                () -> sampleSize < n ? stratifiedSample(data, sampleSize, ctx.sampleSeed()) : data);

        if (choice.encoder() == null) {
            // No encoding beats the baseline — fall back to the first accepting encoder
            return spliceResult(findPrimitiveEncoding(dtype, ctx.excluded()), dtype, data, ctx);
        }
        // Run the winner on the full data
        return spliceResult(choice.encoder(), dtype, data, ctx);
    }

    /// The winner of a selection and the size it was scored at on the sample (or its estimate).
    ///
    /// @param encoder   the winning encoder, or `null` when none beat the baseline
    /// @param size      the winner's sample size in bytes, or the baseline when there is none;
    ///                  unmeasured when `alwaysUse`
    /// @param alwaysUse whether the winner was settled by [Estimate#ALWAYS_USE]
    private record Choice(EncodingEncoder encoder, double size, boolean alwaysUse) {
    }

    /// Rust's `choose_best_scheme` (`compressor/select.rs`), for one array.
    ///
    /// The verdict pass runs first, over the array's own stats: SKIP drops a candidate,
    /// ALWAYS_USE wins outright, and a [Estimate.Ratio] competes without encoding anything. Only
    /// the candidates left deferred ([Estimate#COMPLETE]) are trial-encoded, on the sample, which
    /// is built only if one of them needs it. Scores compare as Rust's ratios do (higher wins,
    /// only above 1 counts): a ratio `r` stands for `baseline / r` sample bytes, and a candidate
    /// must come in strictly under the best so far, starting from the baseline.
    ///
    /// @param dtype    the logical type of the array
    /// @param input    the array with its lazily computed stats
    /// @param ctx      the context the array is encoded under
    /// @param baseline the uncompressed size of the sample in bytes
    /// @param sample   the sample deferred candidates are measured on
    /// @return the winner, if any
    private Choice chooseBest(DType dtype, ArrayAndStats input, EncodeContext ctx, long baseline,
                              Supplier<Object> sample) {
        EncodingEncoder best = null;
        double bestSize = baseline;
        List<EncodingEncoder> deferred = new ArrayList<>();
        for (EncodingEncoder enc : encodings) {
            if (!enc.accepts(dtype) || ctx.excluded().contains(enc.encodingId())) {
                continue;
            }
            Estimate est = competes(dtype) ? enc.expectedRatio(dtype, input, ctx) : Estimate.COMPLETE;
            if (est == Estimate.ALWAYS_USE) {
                return new Choice(enc, 0, true);
            }
            if (est instanceof Estimate.Ratio(double ratio)) {
                double size = baseline / ratio;
                if (size < bestSize) {
                    best = enc;
                    bestSize = size;
                }
            } else if (est == Estimate.COMPLETE) {
                deferred.add(enc);
            }
        }
        if (deferred.isEmpty()) {
            return new Choice(best, bestSize, false);
        }

        Object measured = sample.get();
        EncodeContext sampleCtx = ctx.withSampling();
        for (EncodingEncoder enc : deferred) {
            CascadeStep step = enc.encodeCascade(dtype, measured, sampleCtx);
            // At depth 0, skip encodings that require cascade
            if (!step.isTerminal() && sampleCtx.allowedCascading() <= 0) {
                continue;
            }
            long size = measureStep(enc, step, sampleCtx);
            if (size < bestSize) {
                best = enc;
                bestSize = size;
            }
        }
        return new Choice(best, bestSize, false);
    }

    /// Derives the context a child slot is filled under: one cascade level deeper, with the slot's
    /// own exclusions unioned in. Both the sample measurement and the real encode go through here,
    /// so a child can never be measured under one exclusion policy and encoded under another.
    private static EncodeContext childContext(EncodeContext ctx, ChildSlot slot) {
        return ctx.withDecrementedDepth().withExcluded(slot.excluded());
    }

    private long measureStep(EncodingEncoder enc, CascadeStep step, EncodeContext ctx) {
        long total = step.ownedBytes();
        for (ChildSlot slot : step.openChildren()) {
            total += measureBestChild(slot.childDtype(), slot.childData(), childContext(ctx, slot));
        }
        return total;
    }

    /// Smallest size any candidate encodes the (already sampled) child `data` to: the same
    /// [#chooseBest] selection as a full array, run on the sample itself.
    ///
    /// An empty child is returned before any selection, as Rust's `compress` does
    /// (`compressor/cascade.rs`), so it costs nothing.
    private long measureBestChild(DType dtype, Object data, EncodeContext ctx) {
        int n = dataLength(data);
        long baseline = primitiveBytes(dtype, n);
        if (n == 0) {
            return baseline;
        }
        Choice choice = chooseBest(dtype, withStats(dtype, data, ctx), ctx, baseline, () -> data);
        if (choice.alwaysUse()) {
            // ALWAYS_USE: settled without a measurement, so measure the winner for its size
            CascadeStep step = choice.encoder().encodeCascade(dtype, data, ctx);
            return Math.min(baseline, measureStep(choice.encoder(), step, ctx));
        }
        return Math.round(choice.size());
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

        List<EncodedBuffer> allBuffers = new ArrayList<>(step.ownedBuffers());
        EncodeNode[] children = step.partialRoot().children().clone();

        for (ChildSlot slot : step.openChildren()) {
            EncodeResult childResult = encodeWithCtx(slot.childDtype(), slot.childData(), childContext(ctx, slot));

            int bufOffset = allBuffers.size();
            children[slot.parentChildIdx()] = EncodeNode.remapBufferIndices(childResult.rootNode(), bufOffset);
            allBuffers.addAll(childResult.encodedBuffers());
        }

        EncodeNode root = new EncodeNode(
                step.partialRoot().encodingId(),
                step.partialRoot().metadata(),
                children,
                step.partialRoot().bufferIndices());
        return new EncodeResult(root, List.copyOf(allBuffers), step.statsMin(), step.statsMax());
    }

    /// Encodes a struct, with its row validity as child 0 ahead of the fields when `validity` is
    /// non-null — the `vortex.struct` shape the reader (and Rust) expect for a nullable struct.
    private EncodeResult encodeStruct(DType.Struct dtype, StructData data, boolean[] validity, EncodeContext ctx) {
        List<Object> fields = data.fieldArrays();
        List<DType> fieldTypes = dtype.fieldTypes();
        List<EncodedBuffer> allBuffers = new ArrayList<>();
        int fieldOffset = validity == null ? 0 : 1;
        EncodeNode[] children = new EncodeNode[fields.size() + fieldOffset];
        if (validity != null) {
            EncodeResult validityResult = MaskedEncodingEncoder.encodeValidity(validity, ctx);
            children[0] = validityResult.rootNode();
            allBuffers.addAll(validityResult.encodedBuffers());
        }
        for (int i = 0; i < fields.size(); i++) {
            DType fieldDtype = fieldTypes.get(i);
            Object fieldData = fields.get(i);
            // Mirrors StructEncodingEncoder's own field loop: a nullable field arrives as
            // NullableData(values, validity), not the dense array (String[], byte[][], ...)
            // encodeWithCtx's per-dtype dispatch expects. A nullable struct carries its validity
            // as its own child instead, so it recurses rather than being masked.
            boolean masked = fieldData instanceof NullableData
                    && !(fieldDtype instanceof DType.Extension) && !(fieldDtype instanceof DType.Struct);
            EncodeResult fieldResult = masked
                    ? new MaskedEncodingEncoder().encode(fieldDtype, fieldData, ctx)
                    : encodeWithCtx(fieldDtype, fieldData, ctx);
            int bufOffset = allBuffers.size();
            children[fieldOffset + i] = EncodeNode.remapBufferIndices(fieldResult.rootNode(), bufOffset);
            allBuffers.addAll(fieldResult.encodedBuffers());
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
        // Rust keeps the canonical array when no configured scheme applies: canonical encodings
        // are not schemes, so a restricted candidate list never leaves a canonical type unencodable.
        return switch (dtype) {
            case DType.Primitive _ -> CANONICAL_PRIMITIVE;
            case DType.Bool _ -> CANONICAL_BOOL;
            case DType.Utf8 _, DType.Binary _ -> CANONICAL_VARBIN;
            case DType.Decimal _ -> CANONICAL_DECIMAL;
            case DType.Extension _ -> CANONICAL_EXTENSION;
            default -> throw new UnsupportedOperationException("no encoder for dtype: " + dtype);
        };
    }
}
