package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.proto.ProtoParquetVariantMetadata;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.StructArray;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;

/// Read-only decoder for `vortex.parquet.variant`: per-row [Apache Variant](https://github.com/apache/parquet-format/blob/master/VariantEncoding.md)
/// binaries, as Rust's `encodings/parquet-variant` `deserialize` lays them out. No buffers;
/// children `[validity?, metadata, value?, typed_value?]`, with `value` / `typed_value` presence
/// and their dtypes declared in [ProtoParquetVariantMetadata].
///
/// Decodes to a [StructArray] `{metadata, value?, typed_value?}`: the shape Arrow's own
/// `arrow.parquet.variant` storage uses, so callers read the Variant binaries per field. Row
/// validity, when present, wraps the struct in a [MaskedArray].
public final class ParquetVariantEncodingDecoder implements EncodingDecoder {

    private static final DType BINARY = new DType.Binary(false);

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_PARQUET_VARIANT;
    }

    @Override
    public Array decode(DecodeContext ctx) {
        if (!(ctx.dtype() instanceof DType.Variant)) {
            throw new VortexException(EncodingId.VORTEX_PARQUET_VARIANT,
                    "expected variant dtype, got " + ctx.dtype());
        }
        ProtoParquetVariantMetadata meta = parseMetadata(ctx.metadata());
        DType typedValueDtype = meta.typed_value_dtype() == null
                ? null : VariantEncodingDecoder.dtypeFromProto(meta.typed_value_dtype());
        if (!meta.has_value() && typedValueDtype == null) {
            throw new VortexException(EncodingId.VORTEX_PARQUET_VARIANT,
                    "at least one of value or typed_value must be present");
        }
        if (ctx.node().bufferIndices().length != 0) {
            throw new VortexException(EncodingId.VORTEX_PARQUET_VARIANT,
                    "expected 0 buffers, got " + ctx.node().bufferIndices().length);
        }

        int expected = 1 + (meta.has_value() ? 1 : 0) + (typedValueDtype != null ? 1 : 0);
        int numChildren = ctx.node().children().length;
        if (numChildren != expected && numChildren != expected + 1) {
            throw new VortexException(EncodingId.VORTEX_PARQUET_VARIANT,
                    "expected " + expected + " or " + (expected + 1) + " children, got " + numChildren);
        }

        long n = ctx.rowCount();
        int child = 0;
        Array validity = numChildren == expected ? null : ctx.decodeChild(child++, DType.BOOL, n);

        List<ColumnName> names = new ArrayList<>(3);
        List<DType> types = new ArrayList<>(3);
        List<Array> fields = new ArrayList<>(3);
        names.add(ColumnName.of("metadata"));
        types.add(BINARY);
        fields.add(ctx.decodeChild(child++, BINARY, n));
        if (meta.has_value()) {
            DType valueDtype = new DType.Binary(meta.value_nullable());
            names.add(ColumnName.of("value"));
            types.add(valueDtype);
            fields.add(ctx.decodeChild(child++, valueDtype, n));
        }
        if (typedValueDtype != null) {
            names.add(ColumnName.of("typed_value"));
            types.add(typedValueDtype);
            fields.add(ctx.decodeChild(child, typedValueDtype, n));
        }

        StructArray struct = new StructArray(new DType.Struct(names, types, false), n, fields);
        if (validity == null) {
            return struct;
        }
        return new MaskedArray(struct,
                MaskedArray.requireBoolArray(validity, EncodingId.VORTEX_PARQUET_VARIANT, "validity child"));
    }

    private static ProtoParquetVariantMetadata parseMetadata(MemorySegment rawMeta) {
        if (rawMeta == null || rawMeta.byteSize() == 0) {
            // proto3: an all-default message encodes to zero bytes (has_value=false, no typed_value),
            // which the presence check above then rejects, as Rust does.
            return new ProtoParquetVariantMetadata(false, null, false);
        }
        try {
            return ProtoParquetVariantMetadata.decode(rawMeta, 0, rawMeta.byteSize());
        } catch (IOException e) {
            throw new VortexException(EncodingId.VORTEX_PARQUET_VARIANT, "invalid metadata", e);
        }
    }
}
