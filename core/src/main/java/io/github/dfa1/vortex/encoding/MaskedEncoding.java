package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.array.BoolArray;
import io.github.dfa1.vortex.core.array.MaskedArray;
import io.github.dfa1.vortex.core.array.NullableData;

import java.util.ArrayList;
import java.util.List;

/// Encoder/decoder for {@code vortex.masked}.
///
/// <p>Wire format:
/// <ul>
///   <li>Metadata: empty.</li>
///   <li>Buffers: none.</li>
///   <li>Child 0: payload array encoded with non-nullable dtype (no actual nulls by invariant).</li>
///   <li>Child 1 (optional): validity bitmap, dtype {@code Bool(false)}. Absent means AllValid.</li>
/// </ul>
///
/// <p>Encode input: a [NullableData] carrier whose {@code values} field matches the shape
/// the inner encoding expects (primitive array, byte[], ...) and whose {@code validity}
/// is parallel to it. Null positions in the source must already be zero-filled placeholders.
public final class MaskedEncoding implements Encoding {

    /// Creates a new {@code MaskedEncoding} instance; use via {@link Registry}.
    public MaskedEncoding() {
    }

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_MASKED;
    }

    @Override
    public boolean accepts(DType dtype) {
        // Not registry-pickable. Callers (writer auto-route, ExtEncoding) invoke encode
        // directly when they hold a NullableData carrier.
        return false;
    }

    @Override
    public EncodeResult encode(DType dtype, Object data, EncodeContext ctx) {
        return Encoder.encode(dtype, data, ctx);
    }

    @Override
    public Array decode(DecodeContext ctx) {
        return Decoder.decode(ctx);
    }

    private static final List<Encoding> INNER_FALLBACK = List.of(
            new PrimitiveEncoding(),
            new FixedSizeListEncoding());

    private static final class Encoder {

        static EncodeResult encode(DType dtype, Object data, EncodeContext ctx) {
            if (!(data instanceof NullableData nd)) {
                throw new VortexException(EncodingId.VORTEX_MASKED,
                        "expected NullableData, got " + (data == null ? "null" : data.getClass().getName()));
            }
            DType nonNullable = dtype.withNullable(false);
            Encoding inner = pickInner(nonNullable);
            EncodeResult valuesResult = inner.encode(nonNullable, nd.values(), ctx);
            EncodeResult validityResult = new BoolEncoding().encode(new DType.Bool(false), nd.validity(), ctx);

            int valuesBufCount = valuesResult.buffers().size();
            EncodeNode validityNode = EncodeNode.remapBufferIndices(validityResult.rootNode(), valuesBufCount);

            List<java.lang.foreign.MemorySegment> buffers = new ArrayList<>(valuesBufCount + validityResult.buffers().size());
            buffers.addAll(valuesResult.buffers());
            buffers.addAll(validityResult.buffers());

            EncodeNode root = new EncodeNode(
                    EncodingId.VORTEX_MASKED,
                    null,
                    new EncodeNode[]{valuesResult.rootNode(), validityNode},
                    new int[0]);
            // Stats propagate from the values child — they describe the non-null payload.
            return new EncodeResult(root, buffers, valuesResult.statsMin(), valuesResult.statsMax());
        }

        private static Encoding pickInner(DType nonNullable) {
            for (Encoding e : INNER_FALLBACK) {
                if (e.accepts(nonNullable)) {
                    return e;
                }
            }
            throw new VortexException(EncodingId.VORTEX_MASKED,
                    "no inner encoding for " + nonNullable);
        }
    }

    private static final class Decoder {

        static Array decode(DecodeContext ctx) {
            if (ctx.node().bufferIndices().length != 0) {
                throw new VortexException(EncodingId.VORTEX_MASKED,
                        "expected 0 buffers, got " + ctx.node().bufferIndices().length);
            }
            int numChildren = ctx.node().children().length;
            if (numChildren < 1 || numChildren > 2) {
                throw new VortexException(EncodingId.VORTEX_MASKED,
                        "expected 1 or 2 children, got " + numChildren);
            }

            Array child = ctx.decodeChild(0, ctx.dtype().withNullable(false), ctx.rowCount());

            BoolArray validity = null;
            if (numChildren == 2) {
                Array validityArray = ctx.decodeChild(1, new DType.Bool(false), ctx.rowCount());
                if (!(validityArray instanceof BoolArray ba)) {
                    throw new VortexException(EncodingId.VORTEX_MASKED,
                            "validity child decoded to unexpected type: " + validityArray.getClass().getSimpleName());
                }
                validity = ba;
            }

            return new MaskedArray(child, validity);
        }
    }
}
