package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.BoolArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.StructArray;
import io.github.dfa1.vortex.encoding.EncodingId;

import java.util.ArrayList;
import java.util.List;

/// Read-only decoder for `vortex.struct`.
public final class StructEncodingDecoder implements EncodingDecoder {

    /// Public no-arg constructor required by [java.util.ServiceLoader].
    public StructEncodingDecoder() {
    }

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_STRUCT;
    }

    @Override
    public boolean accepts(DType dtype) {
        return dtype instanceof DType.Struct;
    }

    @Override
    public Array decode(DecodeContext ctx) {
        int numChildren = ctx.node().children().length;

        if (ctx.dtype() instanceof DType.Struct structDtype) {
            int nfields = structDtype.fieldTypes().size();
            if (numChildren != nfields && numChildren != nfields + 1) {
                throw new VortexException(EncodingId.VORTEX_STRUCT,
                        "expected %d or %d children for struct dtype, got %d"
                                .formatted(nfields, nfields + 1, numChildren));
            }
            boolean hasValidity = (numChildren == nfields + 1);
            int fieldOffset = hasValidity ? 1 : 0;

            BoolArray structValidity = null;
            if (hasValidity) {
                ArrayNode validityNode = ctx.node().children()[0];
                var validityCtx = new DecodeContext(validityNode, new DType.Bool(false),
                        ctx.rowCount(), ctx.segmentBuffers(), ctx.registry(), ctx.arena());
                Array va = ctx.registry().decode(validityCtx);
                if (!(va instanceof BoolArray ba)) {
                    throw new VortexException(EncodingId.VORTEX_STRUCT,
                            "struct validity decoded to unexpected type: " + va.getClass().getSimpleName());
                }
                structValidity = ba;
            }

            if (nfields == 1) {
                DType fieldDtype = structDtype.fieldTypes().getFirst();
                ArrayNode fieldNode = ctx.node().children()[fieldOffset];
                var fieldCtx = new DecodeContext(fieldNode, fieldDtype.withNullable(false),
                        ctx.rowCount(), ctx.segmentBuffers(), ctx.registry(), ctx.arena());
                Array field = ctx.registry().decode(fieldCtx);
                return structValidity != null ? new MaskedArray(field, structValidity) : field;
            }

            List<Array> fieldArrays = new ArrayList<>(nfields);
            for (int i = 0; i < nfields; i++) {
                ArrayNode fieldNode = ctx.node().children()[fieldOffset + i];
                DType fieldDtype = structDtype.fieldTypes().get(i);
                var fieldCtx = new DecodeContext(fieldNode, fieldDtype.withNullable(false),
                        ctx.rowCount(), ctx.segmentBuffers(), ctx.registry(), ctx.arena());
                Array field = ctx.registry().decode(fieldCtx);
                fieldArrays.add(structValidity != null ? new MaskedArray(field, structValidity) : field);
            }
            return new StructArray(structDtype, ctx.rowCount(), fieldArrays);
        }

        if (numChildren == 1) {
            ArrayNode valuesNode = ctx.node().children()[0];
            var valuesCtx = new DecodeContext(
                    valuesNode, ctx.dtype(), ctx.rowCount(),
                    ctx.segmentBuffers(), ctx.registry(), ctx.arena());
            return ctx.registry().decode(valuesCtx);
        } else if (numChildren == 2) {
            ArrayNode validityNode = ctx.node().children()[0];
            var validityCtx = new DecodeContext(validityNode, new DType.Bool(false),
                    ctx.rowCount(), ctx.segmentBuffers(), ctx.registry(), ctx.arena());
            Array va = ctx.registry().decode(validityCtx);
            if (!(va instanceof BoolArray validity)) {
                throw new VortexException(EncodingId.VORTEX_STRUCT,
                        "scalar wrapper validity decoded to unexpected type: " + va.getClass().getSimpleName());
            }
            ArrayNode valuesNode = ctx.node().children()[1];
            var valuesCtx = new DecodeContext(
                    valuesNode, ctx.dtype().withNullable(false), ctx.rowCount(),
                    ctx.segmentBuffers(), ctx.registry(), ctx.arena());
            Array values = ctx.registry().decode(valuesCtx);
            return new MaskedArray(values, validity);
        } else {
            throw new VortexException(EncodingId.VORTEX_STRUCT,
                    "unexpected child count " + numChildren + " for scalar wrapper");
        }
    }
}
