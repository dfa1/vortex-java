package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.DTypes;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.proto.ProtoListMetadata;
import io.github.dfa1.vortex.core.proto.ProtoPType;
import io.github.dfa1.vortex.core.simd.SimdOperationsSupport;

import java.lang.foreign.MemorySegment;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
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

    // a list's offsets: signed 64-bit, the width `vortex.list` records in its metadata
    private static final DType OFFSETS = DTypes.I64;

    private static final EncodingEncoder CANONICAL_PRIMITIVE = new PrimitiveEncodingEncoder();
    private static final EncodingEncoder CANONICAL_VARBINVIEW = new VarBinViewEncodingEncoder();
    private static final EncodingEncoder CANONICAL_BOOL = new BoolEncodingEncoder();
    /// Bytes of a `vortex.constant` bool: Rust keeps a bool array whose packed bits are no larger.
    private static final int CONSTANT_BOOL_BYTES = 2;
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
            case NullableData(var values, var validity) -> new NullableData(
                    stratifiedSample(values, sampleSize, seed), (boolean[]) stratifiedSample(validity, sampleSize, seed));
            case boolean[] a -> {
                boolean[] out = new boolean[sampleSize];
                forEachStride(a.length, sampleSize, seed, (srcOff, dstOff, len) ->
                        System.arraycopy(a, srcOff, out, dstOff, len));
                yield out;
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

    /// Rust's `exact_uncompressed_size` of a primitive array: the values, plus a packed bitmap
    /// when some row is null (`validity_uncompressed_size_in_bytes`, nothing when all are valid).
    private static long uncompressedBytes(DType dtype, Object data) {
        int n = RowCount.of(data);
        long values = primitiveBytes(dtype, n);
        if (data instanceof NullableData nullable && nullable.nullCount() > 0) {
            return values + (n + 7) / 8;
        }
        return values;
    }

    /// Whether `enc` may compete for `data`: it accepts the dtype, is not excluded, and, for a
    /// nullable primitive, keeps the validity itself, as every Rust scheme does on its array.
    private static boolean eligible(EncodingEncoder enc, DType dtype, Object data, EncodeContext ctx) {
        if (!enc.accepts(dtype) || ctx.excluded().contains(enc.encodingId())) {
            return false;
        }
        // Rust's canonical text is `vortex.varbinview`, the baseline every scheme must beat, and
        // its `VarBinScheme` re-encodes binary only
        if (dtype instanceof DType.Utf8 || dtype instanceof DType.Binary) {
            EncodingId id = enc.encodingId();
            if (id.equals(EncodingId.VORTEX_VARBINVIEW) || id.equals(EncodingId.VORTEX_VARBIN) && dtype instanceof DType.Utf8) {
                return false;
            }
        }
        return !(data instanceof NullableData) || enc.acceptsNullable(dtype);
    }

    /// Whether a nullable column of `dtype` keeps its validity in the encoding that stores its
    /// values, as every Rust array does, rather than in a `vortex.masked` wrapper (#604): every
    /// primitive does.
    ///
    /// @param dtype the column's dtype
    /// @return `true` for a primitive
    public static boolean keepsValidity(DType dtype) {
        return dtype instanceof DType.Primitive;
    }

    /// Whether a nullable column of `dtype` carries its validity as a child of its own encoding, as
    /// Rust's `StructArray`, `ListArray` and `DecimalArray` do, rather than in a `vortex.masked`
    /// wrapper (#648, #646).
    ///
    /// @param dtype the column's dtype
    /// @return `true` for a struct, a list or a decimal
    public static boolean carriesValidity(DType dtype) {
        return dtype instanceof DType.Struct || dtype instanceof DType.List || dtype instanceof DType.Decimal;
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
        // A column without nulls has no validity to store, as Rust imports an Arrow array whose
        // null count is zero (`Validity::AllValid`). Deeper down a NullableData keeps its bitmap
        // even when every bit is set, as a filtered or sampled Rust array does.
        // An extension's storage is that array too.
        if ((keepsValidity(dtype) || dtype instanceof DType.Extension || dtype instanceof DType.Decimal)
                && data instanceof NullableData nullable && nullable.nullCount() == 0) {
            return encodeWithCtx(dtype, nullable.values(), ctx);
        }
        return encodeWithCtx(dtype, data, ctx);
    }

    private EncodeResult encodeWithCtx(DType dtype, Object data, EncodeContext ctx) {
        EncodeResult result = encodeArray(dtype, data, ctx);
        NodeStats own = result.rootNode().stats();
        if (ctx.sample() || own != null && !own.isEmpty()) {
            return result;
        }
        return new EncodeResult(result.rootNode().withStats(nodeStats(dtype, data, result.rootNode().encodingId())),
                result.encodedBuffers(), result.statsMin(), result.statsMax());
    }

    /// The stats Rust keeps on the node of an array it compressed, here a child of the segment's root
    /// as much as the root itself ([CachedNodeStats]).
    private static NodeStats nodeStats(DType dtype, Object data, EncodingId root) {
        if (dtype instanceof DType.Bool) {
            if (!root.equals(EncodingId.VORTEX_BOOL) || !(data instanceof boolean[] bools)) {
                return NodeStats.NONE;
            }
            return CachedNodeStats.ofBool(bools.length, SimdOperationsSupport.preferred().allEqual(bools));
        }
        if (dtype instanceof DType.Utf8 || dtype instanceof DType.Binary) {
            if (!root.equals(EncodingId.VORTEX_VARBINVIEW)) {
                return NodeStats.NONE;
            }
            long nulls = data instanceof NullableData nullable ? nullable.nullCount() : 0L;
            return CachedNodeStats.ofView(nulls, viewBytes(data, Integer.MAX_VALUE, 0L));
        }
        if (!(dtype instanceof DType.Primitive)) {
            return NodeStats.NONE;
        }
        MinMax bounds = dtype instanceof DType.Primitive p && !p.ptype().isFloating() ? ZoneMapStats.of(dtype, data) : null;
        long nullCount = data instanceof NullableData nullable ? nullable.nullCount() : 0L;
        return CachedNodeStats.of(dtype, root, bounds == null ? null : bounds.min(), bounds == null ? null : bounds.max(),
                RowCount.of(data), nullCount);
    }

    private EncodeResult encodeArray(DType dtype, Object data, EncodeContext ctx) {
        if (keepsValidity(dtype) && data instanceof NullableData nullable) {
            return encodeNullablePrimitive(dtype, nullable, ctx);
        }
        // An all-null decimal is one null constant, before any scheme, as for a primitive.
        if (dtype instanceof DType.Decimal && data instanceof NullableData nullable
                && MaskedEncodingEncoder.isNullConstant(nullable.validity(), ctx)) {
            return MaskedEncodingEncoder.nullConstant();
        }
        if (dtype instanceof DType.Decimal decimal) {
            Optional<EncodeResult> constant = decimalConstant(decimal, data, ctx);
            if (constant.isPresent()) {
                return constant.get();
            }
        }
        if (dtype instanceof DType.Struct structDtype) {
            return data instanceof NullableData(StructData fields, boolean[] validity)
                    ? encodeStruct(structDtype, fields, validity, ctx)
                    : encodeStruct(structDtype, (StructData) data, null, ctx);
        }

        // A list column carries ListData, or ListViewData when the caller picked list-view storage;
        // only the former is a vortex.list the cascade can compress.
        if (dtype instanceof DType.List listDtype) {
            if (data instanceof ListData list) {
                return encodeList(listDtype, list, null, ctx);
            }
            if (data instanceof NullableData(ListData list, boolean[] validity)) {
                return encodeList(listDtype, list, validity, ctx);
            }
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
                    sampleSize -> viewBytes(data, sampleSize, ctx.sampleSeed()));
        }

        // Remaining non-primitives (extension types, List, ...): find the accepting encoding and
        // splice through it so its cascaded children (e.g. datetimeparts → days/seconds/
        // subseconds) are recursively compressed rather than stored as raw primitives. Honor the
        // excluded set so spliceResult's notApplicable retry can rotate to the next accepting
        // encoding (e.g. DateTimePartsEncoding → ExtEncoding for a date, or an all-null chunk).
        if (dtype instanceof DType.Extension) {
            return encodeExtension(dtype, data, ctx);
        }
        if (dtype instanceof DType.Decimal decimal) {
            return encodeDecimal(decimal, data, ctx);
        }
        if (dtype instanceof DType.Bool && data instanceof boolean[] bools) {
            return encodeBool(bools, ctx);
        }
        if (!(dtype instanceof DType.Primitive)) {
            return spliceResult(findPrimitiveEncoding(dtype, data, ctx.excluded()), dtype, data, ctx);
        }

        return competeAndEncode(dtype, withStats(dtype, data, ctx), ctx,
                sampleSize -> primitiveBytes(dtype, sampleSize));
    }

    /// A bool array stays `vortex.bool` unless it is one value and that is smaller as a
    /// `vortex.constant`: Rust's `ConstantScheme` only wins when the constant is smaller than the packed
    /// bits, which it is from 17 rows (3 bytes) on. A zone table's three flags, or a few rows, stay bool.
    private EncodeResult encodeBool(boolean[] bools, EncodeContext ctx) {
        boolean constantPays = (bools.length + 7) / 8 > CONSTANT_BOOL_BYTES;
        if (constantPays && !ctx.sample() && !ctx.excluded().contains(EncodingId.VORTEX_CONSTANT)
                && SimdOperationsSupport.preferred().allEqual(bools)) {
            return new ConstantEncodingEncoder().encode(DTypes.BOOL, bools, ctx);
        }
        return CANONICAL_BOOL.encode(DTypes.BOOL, bools, ctx);
    }

    /// Rust's constant check for a decimal array, run in the compressor before any scheme and never
    /// on a sample: when every valid value is equal the array is one `vortex.constant`, wrapped in
    /// `vortex.masked` with the validity when some row is null, and kept only if that is smaller than
    /// the plain values (`compress_as_constant`).
    private static Optional<EncodeResult> decimalConstant(DType.Decimal dtype, Object data, EncodeContext ctx) {
        if (ctx.sample() || ctx.excluded().contains(EncodingId.VORTEX_CONSTANT)) {
            return Optional.empty();
        }
        boolean[] validity = null;
        Object values = data;
        if (data instanceof NullableData nullable) {
            validity = nullable.validity();
            values = nullable.values();
        }
        if (!(values instanceof BigDecimal[] decimals)) {
            return Optional.empty();
        }
        BigDecimal[] valid = decimals;
        if (validity != null) {
            valid = new BigDecimal[decimals.length - ((NullableData) data).nullCount()];
            for (int i = 0, j = 0; i < decimals.length; i++) {
                if (validity[i]) {
                    valid[j++] = decimals[i];
                }
            }
        }
        Optional<EncodeResult> constant = ConstantEncodingEncoder.encodeDecimalIfConstant(dtype, valid);
        if (constant.isEmpty() || totalBytes(constant.get()) >= (long) decimals.length * dtype.byteWidth()) {
            return Optional.empty();
        }
        if (validity == null) {
            return constant;
        }
        EncodeResult bool = new BoolEncodingEncoder().encode(DTypes.BOOL, validity, ctx);
        EncodeNode validityNode = EncodeNode.remapBufferIndices(bool.rootNode(), constant.get().encodedBuffers().size());
        List<EncodedBuffer> buffers = new ArrayList<>(constant.get().encodedBuffers());
        buffers.addAll(bool.encodedBuffers());
        EncodeNode root = new EncodeNode(EncodingId.VORTEX_MASKED, null,
                new EncodeNode[]{constant.get().rootNode(), validityNode}, new int[0]);
        return Optional.of(new EncodeResult(root, List.copyOf(buffers), constant.get().statsMin(),
                constant.get().statsMax()));
    }

    /// Rust's `DecimalScheme`: a decimal is split into `vortex.decimal_byte_parts` only when that is
    /// strictly smaller than the plain `vortex.decimal` (its values, plus a packed bitmap when some
    /// row is null). On a few rows, as in a zone-stats table, the parts cost as much as the plain
    /// values and the decimal stays canonical (#646).
    private EncodeResult encodeDecimal(DType.Decimal dtype, Object data, EncodeContext ctx) {
        EncodeResult scheme = spliceResult(findPrimitiveEncoding(dtype, data, ctx.excluded()), dtype, data, ctx);
        EncodingId root = scheme.rootNode().encodingId();
        if (!root.equals(EncodingId.VORTEX_DECIMAL_BYTE_PARTS)) {
            return scheme;
        }
        int n = RowCount.of(data);
        long plain = (long) n * dtype.byteWidth();
        if (data instanceof NullableData nullable && nullable.nullCount() > 0) {
            plain += (n + 7) / 8;
        }
        if (totalBytes(scheme) < plain) {
            return scheme;
        }
        return CANONICAL_DECIMAL.encode(dtype, data, ctx);
    }

    /// Rust's `compress_canonical` for an extension array (`compressor/cascade.rs`): an extension
    /// scheme such as `vortex.datetimeparts` is kept only when it comes out strictly smaller than
    /// the storage compressed as `vortex.ext`. On a few rows, as in a zone-stats table, the split
    /// parts cost more than the plain storage (#634). A constant needs no comparison.
    private EncodeResult encodeExtension(DType dtype, Object data, EncodeContext ctx) {
        EncodeResult scheme = spliceResult(findPrimitiveEncoding(dtype, ctx.excluded()), dtype, data, ctx);
        EncodingId root = scheme.rootNode().encodingId();
        if (root.equals(EncodingId.VORTEX_EXT) || root.equals(EncodingId.VORTEX_CONSTANT)
                || ctx.excluded().contains(EncodingId.VORTEX_EXT)) {
            return scheme;
        }
        // the Parquet importer's pre-split carrier holds the plain i64 storage
        Object storageData = data instanceof DateTimePartsData parts ? parts.timestamps() : data;
        EncodeResult storage = encodeExtension(dtype, storageData, ctx.withExcluded(root));
        return totalBytes(scheme) < totalBytes(storage) ? scheme : storage;
    }

    /// Bundles `data` with stats under the [StatsOptions] merged from every eligible encoder,
    /// so the one scan, run lazily by the first encoder whose expectedRatio() reads stats,
    /// satisfies every later one (Rust vortex-compressor pattern).
    private ArrayAndStats withStats(DType dtype, Object data, EncodeContext ctx) {
        StatsOptions merged = StatsOptions.defaults();
        for (EncodingEncoder enc : encodings) {
            if (eligible(enc, dtype, data, ctx)) {
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
            DType dtype, ArrayAndStats input, EncodeContext ctx, IntToLongFunction baselineFn) {
        Object data = input.data();
        int n = RowCount.of(data);
        int sampleSize = sampleSize(n, ctx);
        Choice choice = chooseBest(dtype, input, ctx, baselineFn.applyAsLong(sampleSize),
                () -> sampleSize < n ? stratifiedSample(data, sampleSize, ctx.sampleSeed()) : data);
        return encodeChoice(choice, dtype, data, ctx);
    }

    /// Runs the winner of a selection on the full data, or the first accepting encoder when no
    /// candidate beat the baseline.
    ///
    /// A primitive winner is kept only when it comes out smaller than the array it encodes, as
    /// Rust's `choose_and_compress` accepts it (`compressor/cascade.rs`): a sample can favor a
    /// scheme the full array does not, such as bit-packing a short array that pads to a whole
    /// 1024-value block. Otherwise the array stays canonical.
    private EncodeResult encodeChoice(Choice choice, DType dtype, Object data, EncodeContext ctx) {
        EncodingEncoder canonical = findPrimitiveEncoding(dtype, ctx.excluded());
        if (choice.encoder() == null) {
            return spliceResult(canonical, dtype, data, ctx);
        }
        EncodeResult result = spliceResult(choice.encoder(), dtype, data, ctx);
        if (dtype instanceof DType.Primitive && choice.encoder() != canonical
                && totalBytes(result) >= uncompressedBytes(dtype, data)) {
            return spliceResult(canonical, dtype, data, ctx);
        }
        return result;
    }

    /// The size of the canonical text array of the `sampleSize` rows a trial encodes: Rust's
    /// `vortex.varbinview` is a 16-byte view per row plus the bytes of each value too long to be
    /// inlined in it.
    private static long viewBytes(Object data, int sampleSize, long seed) {
        Object sample = sampleSize < RowCount.of(data) ? stratifiedSample(data, sampleSize, seed) : data;
        if (sample instanceof NullableData nullable) {
            sample = nullable.values();
        }
        long total = 0;
        for (byte[] row : VarBinBytes.toByteArrays(sample)) {
            total += 16 + (row.length > 12 ? row.length : 0);
        }
        return total;
    }

    private static long totalBytes(EncodeResult result) {
        long total = 0;
        for (EncodedBuffer buffer : result.encodedBuffers()) {
            total += buffer.data().byteSize();
        }
        return total;
    }

    /// Rust's `choose_and_compress` for a primitive array with nulls: an all-null array is a null
    /// constant and one whose valid rows hold a single value is a constant masked by the validity,
    /// both decided by the compressor before any scheme (`compressor/cascade.rs`). Otherwise the
    /// schemes compete with the validity in their arrays: a sampled scheme is measured with its
    /// validity child, against the values plus a packed bitmap.
    private EncodeResult encodeNullablePrimitive(DType dtype, NullableData data, EncodeContext ctx) {
        int n = data.length();
        if (MaskedEncodingEncoder.isNullConstant(data.validity(), ctx)) {
            return MaskedEncodingEncoder.nullConstant();
        }
        if (n > 0 && ctx.allowedCascading() > 0 && !ctx.sample()
                && !ctx.excluded().contains(EncodingId.VORTEX_CONSTANT)) {
            Object valid = data.validValues();
            PType ptype = ((DType.Primitive) dtype).ptype();
            if (SimdOperationsSupport.preferred().allEqual(valid, ptype)) {
                return MaskedEncodingEncoder.maskedConstant(dtype, data, valid, ctx);
            }
        }
        ArrayAndStats input = withStats(dtype, data, ctx);
        int sampleSize = sampleSize(n, ctx);
        Object sample = sampleSize < n ? stratifiedSample(data, sampleSize, ctx.sampleSeed()) : data;
        Choice choice = chooseBest(dtype, input, ctx, uncompressedBytes(dtype, sample), () -> sample);
        return encodeChoice(choice, dtype, data, ctx);
    }

    /// The winner of a selection and the size it was scored at on the sample (or its estimate).
    ///
    /// @param encoder   the winning encoder, or `null` when none beat the baseline
    /// @param size      the winner's sample size in bytes, or the baseline when there is none;
    ///                  unmeasured when `estimated`
    /// @param estimated whether the winner won without being encoded: by [Estimate#ALWAYS_USE] or
    ///                  a stats [Estimate.Ratio], so `size` is not what it encodes to
    private record Choice(EncodingEncoder encoder, double size, boolean estimated) {
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
        boolean bestEstimated = false;
        List<EncodingEncoder> deferred = new ArrayList<>();
        for (EncodingEncoder enc : encodings) {
            if (!eligible(enc, dtype, input.data(), ctx)) {
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
                    bestEstimated = true;
                }
            } else if (est == Estimate.COMPLETE) {
                deferred.add(enc);
            }
        }
        if (deferred.isEmpty()) {
            return new Choice(best, bestSize, bestEstimated);
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
                bestEstimated = false;
            }
        }
        return new Choice(best, bestSize, bestEstimated);
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
        int n = RowCount.of(data);
        long baseline = uncompressedBytes(dtype, data);
        if (n == 0) {
            return baseline;
        }
        Choice choice = chooseBest(dtype, withStats(dtype, data, ctx), ctx, baseline, () -> data);
        if (choice.estimated()) {
            // Rust compresses a child for real even on a sample (`compress_child`), so an estimate
            // picks the winner but its cost is what it encodes to, and the child stays canonical
            // when that is not smaller: a FoR ratio says nothing of the 1024-value block its
            // bit-packing pads a short child to
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
            EncodeResult fieldResult = encodeChild(fieldDtype, fieldData, ctx);
            int bufOffset = allBuffers.size();
            children[fieldOffset + i] = EncodeNode.remapBufferIndices(fieldResult.rootNode(), bufOffset);
            allBuffers.addAll(fieldResult.encodedBuffers());
        }
        EncodeNode root = new EncodeNode(EncodingId.VORTEX_STRUCT, null, children, new int[0]);
        return new EncodeResult(root, List.copyOf(allBuffers), null, null);
    }

    /// Encodes a child of a struct or list. A nullable child arrives as `NullableData(values,
    /// validity)`, not the dense array (`String[]`, `byte[][]`, ...) the per-dtype dispatch
    /// expects. A nullable primitive keeps its validity in the encoding that stores its values and
    /// a nullable struct or list carries it as a child of its own, so those recurse; any other
    /// nullable child is masked.
    private EncodeResult encodeChild(DType dtype, Object data, EncodeContext ctx) {
        boolean masked = data instanceof NullableData && !(dtype instanceof DType.Extension)
                && !carriesValidity(dtype) && !keepsValidity(dtype);
        if (masked) {
            return new MaskedEncodingEncoder().encode(dtype, data, ctx);
        }
        return encode(dtype, data, ctx);
    }

    /// Encodes a list as `vortex.list`: its elements and its offsets, each compressed by the
    /// cascade like a column of their own, and its row validity as a third child when `validity`
    /// is non-null, the shape the reader (and Rust) expect for a nullable list.
    private EncodeResult encodeList(DType.List dtype, ListData data, boolean[] validity, EncodeContext ctx) {
        List<EncodedBuffer> allBuffers = new ArrayList<>();
        EncodeResult elements = encodeChild(dtype.elementType(), data.elements(), ctx);
        EncodeNode elementsNode = EncodeNode.remapBufferIndices(elements.rootNode(), 0);
        allBuffers.addAll(elements.encodedBuffers());

        long[] offsets = Arrays.copyOf(data.offsets(), (int) data.outerLen() + 1);
        EncodeResult offsetsResult = encode(OFFSETS, offsets, ctx);
        EncodeNode offsetsNode = EncodeNode.remapBufferIndices(offsetsResult.rootNode(), allBuffers.size());
        allBuffers.addAll(offsetsResult.encodedBuffers());

        EncodeNode[] children = new EncodeNode[validity == null ? 2 : 3];
        children[0] = elementsNode;
        children[1] = offsetsNode;
        if (validity != null) {
            EncodeResult validityResult = MaskedEncodingEncoder.encodeValidity(validity, ctx);
            children[2] = EncodeNode.remapBufferIndices(validityResult.rootNode(), allBuffers.size());
            allBuffers.addAll(validityResult.encodedBuffers());
        }
        byte[] metadata = new ProtoListMetadata(offsets[offsets.length - 1],
                ProtoPType.fromValue(PType.I64.ordinal())).encode();
        EncodeNode root = new EncodeNode(EncodingId.VORTEX_LIST, MemorySegment.ofArray(metadata), children,
                new int[0]);
        return new EncodeResult(root, List.copyOf(allBuffers), null, null);
    }

    private EncodingEncoder findPrimitiveEncoding(DType dtype, Set<EncodingId> excluded) {
        return findPrimitiveEncoding(dtype, null, excluded);
    }

    private EncodingEncoder findPrimitiveEncoding(DType dtype, Object data, Set<EncodingId> excluded) {
        // a nullable decimal reaches only the encodings that keep its validity themselves
        boolean nullableDecimal = dtype instanceof DType.Decimal && data instanceof NullableData;
        for (EncodingEncoder enc : encodings) {
            if (excluded.contains(enc.encodingId()) || nullableDecimal && !enc.acceptsNullable(dtype)) {
                continue;
            }
            if (enc.encodingId().equals(EncodingId.VORTEX_PRIMITIVE) && enc.accepts(dtype)) {
                return enc;
            }
        }
        if ((dtype instanceof DType.Utf8 || dtype instanceof DType.Binary)
                && !excluded.contains(EncodingId.VORTEX_VARBINVIEW)) {
            return CANONICAL_VARBINVIEW;
        }
        // Fall through to any accepting encoding (still honoring exclusions so that
        // spliceResult's notApplicable retry rotates to the next candidate).
        for (EncodingEncoder enc : encodings) {
            if (excluded.contains(enc.encodingId()) || nullableDecimal && !enc.acceptsNullable(dtype)) {
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
            case DType.Utf8 _, DType.Binary _ -> CANONICAL_VARBINVIEW;
            case DType.Decimal _ -> CANONICAL_DECIMAL;
            case DType.Extension _ -> CANONICAL_EXTENSION;
            default -> throw new UnsupportedOperationException("no encoder for dtype: " + dtype);
        };
    }
}
