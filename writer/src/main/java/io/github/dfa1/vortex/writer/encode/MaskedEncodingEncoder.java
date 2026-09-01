package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.error.VortexException;

import io.github.dfa1.vortex.core.model.EncodingId;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;

/// Write-only encoder for `vortex.masked`. Wraps the payload encode in a values + validity
/// pair driven by a [NullableData] carrier.
///
/// When the context carries cascade depth (positive [EncodeContext#allowedCascading]) the inner
/// non-nullable values are compressed through the full [CascadingCompressor] built from the
/// registry, so nullable columns get the same Dict/FSST/ALP/bitpack selection as dense columns.
/// Without cascade depth the values fall back to a fixed first-match encoder
/// (primitive / varbin / fixed-size-list).
public final class MaskedEncodingEncoder implements EncodingEncoder {

    private static final byte[] EMPTY_BYTES = new byte[0];

    private static final List<EncodingEncoder> INNER_FALLBACK = List.of(
            new PrimitiveEncodingEncoder(),
            new VarBinEncodingEncoder(),
            new FixedSizeListEncodingEncoder(),
            new ListEncodingEncoder());

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_MASKED;
    }

    @Override
    public boolean accepts(DType dtype) {
        return false;
    }

    @Override
    public EncodeResult encode(DType dtype, Object data, EncodeContext ctx) {
        if (!(data instanceof NullableData(var values, var validity))) {
            throw new VortexException(EncodingId.VORTEX_MASKED,
                    "expected NullableData, got " + (data == null ? "null" : data.getClass().getName()));
        }
        DType nonNullable = dtype.withNullable(false);
        EncodeResult valuesResult = encodeValues(nonNullable, values, ctx);
        EncodeResult validityResult = encodeValidity(validity, ctx);

        int valuesBufCount = valuesResult.buffers().size();
        EncodeNode validityNode = EncodeNode.remapBufferIndices(validityResult.rootNode(), valuesBufCount);

        List<MemorySegment> buffers = new ArrayList<>(valuesBufCount + validityResult.buffers().size());
        buffers.addAll(valuesResult.buffers());
        buffers.addAll(validityResult.buffers());

        EncodeNode root = new EncodeNode(
                EncodingId.VORTEX_MASKED,
                null,
                new EncodeNode[]{valuesResult.rootNode(), validityNode},
                new int[0]);
        return new EncodeResult(root, buffers, valuesResult.statsMin(), valuesResult.statsMax());
    }

    /// Encodes the non-null values of a masked column.
    ///
    /// With cascade depth available, runs the full [CascadingCompressor] over the registry's
    /// encoders so low-cardinality strings pick Dict, high-cardinality strings pick FSST, and
    /// numeric values pick ALP/FOR/bitpacking — the same selection dense columns receive. Without
    /// cascade depth, falls back to the fixed first-match encoder. Either way [MaskedEncodingEncoder]
    /// never selects itself: its [#accepts] returns `false`, so the compressor cannot recurse into it.
    private static EncodeResult encodeValues(DType nonNullable, Object values, EncodeContext ctx) {
        if (ctx.allowedCascading() > 0) {
            // The NullableData Utf8/Binary carrier keeps null elements at null positions in the
            // String[]/byte[][] (validity masks them). Dict/FSST/Zstd call getBytes()/read the row
            // bytes of every element, so substitute an empty placeholder for nulls first —
            // matching VarBinEncodingEncoder, which encodes a null as a zero-length slot. Those
            // slots are never read: the enclosing vortex.masked validity bitmap marks the rows null.
            Object dense = denseValues(values);
            List<EncodingEncoder> candidates =
                    List.copyOf(ctx.registry().encoderMap().values());
            return new CascadingCompressor(candidates).encode(nonNullable, dense, ctx);
        }
        return pickInner(nonNullable).encode(nonNullable, values, ctx);
    }

    /// Encodes a masked column's validity bitmap.
    ///
    /// Every row sharing the same validity (the whole chunk all-valid or all-invalid) is
    /// `vortex.constant` ([ConstantEncodingEncoder]) — a common case that otherwise costs a full
    /// `n/8`-byte bitmap for no information. Otherwise, with cascade depth available, also tries
    /// `vortex.sparse` ([SparseEncodingEncoder#encodeBool] — wins on a dominant value with
    /// scattered rare flips, its patch-index array compressed further), `vortex.runend`
    /// ([RunEndEncodingEncoder#encodeBool] — wins on long clustered runs of valid/invalid rows,
    /// regardless of which value dominates), and `fastlanes.rle`
    /// ([RleEncodingEncoder#encodeBool] — the same clustered-run shape as run-end, FastLanes'
    /// vectorized chunked layout instead), keeping whichever candidate is smallest. Without
    /// cascade depth, or when none wins, falls back to a raw `vortex.bool` bitmap.
    ///
    /// @param validity per-row validity bitmap
    /// @param ctx      the encode context
    /// @return the encoded validity child
    private static EncodeResult encodeValidity(boolean[] validity, EncodeContext ctx) {
        if (isConstantValidity(validity)) {
            return new ConstantEncodingEncoder().encode(DType.BOOL, validity, ctx);
        }
        EncodeResult best = new BoolEncodingEncoder().encode(DType.BOOL, validity, ctx);
        if (ctx.allowedCascading() <= 0) {
            return best;
        }
        EncodeResult sparse = SparseEncodingEncoder.encodeBool(validity, ctx);
        if (totalBytes(sparse) < totalBytes(best)) {
            best = sparse;
        }
        EncodeResult runEnd = RunEndEncodingEncoder.encodeBool(validity, ctx);
        if (totalBytes(runEnd) < totalBytes(best)) {
            best = runEnd;
        }
        EncodeResult rle = RleEncodingEncoder.encodeBool(validity, ctx);
        if (totalBytes(rle) < totalBytes(best)) {
            best = rle;
        }
        return best;
    }

    private static long totalBytes(EncodeResult result) {
        long total = 0;
        for (MemorySegment buf : result.buffers()) {
            total += buf.byteSize();
        }
        return total;
    }

    private static boolean isConstantValidity(boolean[] validity) {
        if (validity.length == 0) {
            return true;
        }
        boolean first = validity[0];
        for (boolean b : validity) {
            if (b != first) {
                return false;
            }
        }
        return true;
    }

    /// Returns `values` unchanged, except a `String[]`/`byte[][]` with null elements is copied
    /// with each null replaced by an empty placeholder so cascade encoders (Dict, FSST, Zstd,
    /// VarBinView) can read every element's bytes safely.
    ///
    /// @param values the non-nullable values carrier extracted from the [NullableData]
    /// @return the same array, or a null-free copy when it is a `String[]`/`byte[][]` containing nulls
    private static Object denseValues(Object values) {
        if (values instanceof String[] strings) {
            return densifyStrings(strings);
        }
        if (values instanceof byte[][] raw) {
            return densifyBytes(raw);
        }
        return values;
    }

    private static String[] densifyStrings(String[] strings) {
        String[] out = null;
        for (int i = 0; i < strings.length; i++) {
            if (strings[i] == null) {
                if (out == null) {
                    out = strings.clone();
                }
                out[i] = "";
            }
        }
        return out != null ? out : strings;
    }

    private static byte[][] densifyBytes(byte[][] raw) {
        byte[][] out = null;
        for (int i = 0; i < raw.length; i++) {
            if (raw[i] == null) {
                if (out == null) {
                    out = raw.clone();
                }
                out[i] = EMPTY_BYTES;
            }
        }
        return out != null ? out : raw;
    }

    private static EncodingEncoder pickInner(DType nonNullable) {
        for (EncodingEncoder e : INNER_FALLBACK) {
            if (e.accepts(nonNullable)) {
                return e;
            }
        }
        throw new VortexException(EncodingId.VORTEX_MASKED,
                "no inner encoding for " + nonNullable);
    }
}
