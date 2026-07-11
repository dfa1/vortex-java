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

    private static final List<EncodingEncoder> INNER_FALLBACK = List.of(
            new PrimitiveEncodingEncoder(),
            new VarBinEncodingEncoder(),
            new FixedSizeListEncodingEncoder());

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
        EncodeResult validityResult = new BoolEncodingEncoder().encode(DType.BOOL, validity, ctx);

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
            // The NullableData Utf8/Binary carrier keeps null elements in the String[] at null
            // positions (validity masks them). Dict/FSST call getBytes() on every element, so
            // substitute the empty string for nulls first — matching VarBinEncodingEncoder, which
            // encodes a null as a zero-length slot. Those slots are never read: the enclosing
            // vortex.masked validity bitmap marks the rows null.
            Object dense = denseValues(values);
            List<EncodingEncoder> candidates =
                    List.copyOf(ctx.registry().encoderMap().values());
            return new CascadingCompressor(candidates).encode(nonNullable, dense, ctx);
        }
        return pickInner(nonNullable).encode(nonNullable, values, ctx);
    }

    /// Returns `values` unchanged, except a `String[]` with null elements is copied with each null
    /// replaced by the empty string so cascade encoders (Dict, FSST) can call `getBytes()` safely.
    ///
    /// @param values the non-nullable values carrier extracted from the [NullableData]
    /// @return the same array, or a null-free copy when it is a `String[]` containing nulls
    private static Object denseValues(Object values) {
        if (!(values instanceof String[] strings)) {
            return values;
        }
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
