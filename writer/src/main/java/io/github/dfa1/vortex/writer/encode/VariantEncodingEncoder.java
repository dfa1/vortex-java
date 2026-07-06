package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.proto.ProtoScalar;
import io.github.dfa1.vortex.core.proto.ProtoScalarValue;
import io.github.dfa1.vortex.core.proto.ProtoVariantMetadata;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;

/// Write-only encoder for `vortex.variant`.
///
/// Emits the canonical variant container: a single `core_storage` child holding the
/// full value per row, no shredded child, and `ProtoVariantMetadata` with no `shredded_dtype`.
/// The container itself owns no buffers.
///
/// `core_storage` is built from the per-row scalars in [VariantData], coalescing adjacent
/// equal values into constant runs:
/// - all rows equal → one `vortex.constant` child (the constant broadcasts to every row);
/// - otherwise → a `vortex.chunked` whose first child is the cumulative `u64` run offsets
///   and whose remaining children are one `vortex.constant` per run.
///
/// This mirrors the Rust reference, where a non-constant variant column is a chunked
/// array of constant variant scalars under the canonical variant array.
public final class VariantEncodingEncoder implements EncodingEncoder {

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_VARIANT;
    }

    @Override
    public boolean accepts(DType dtype) {
        return dtype instanceof DType.Variant;
    }

    @Override
    public EncodeResult encode(DType dtype, Object data, EncodeContext ctx) {
        if (!(dtype instanceof DType.Variant)) {
            throw new VortexException(EncodingId.VORTEX_VARIANT, "encode requires Variant dtype, got " + dtype);
        }
        if (!(data instanceof VariantData variantData)) {
            throw new VortexException(EncodingId.VORTEX_VARIANT,
                    "encode requires VariantData, got " + (data == null ? "null" : data.getClass().getName()));
        }

        List<ProtoScalar> values = variantData.values();
        List<ProtoScalar> runValues = new ArrayList<>();
        List<Long> runLengths = new ArrayList<>();
        coalesceRuns(values, runValues, runLengths);

        List<MemorySegment> buffers = new ArrayList<>();
        EncodeNode coreStorage = runValues.size() == 1
                ? constantChild(runValues.get(0), buffers)
                : chunkedConstants(runValues, runLengths, ctx, buffers);

        EncodeNode[] children;
        io.github.dfa1.vortex.core.proto.ProtoDType shreddedProto = null;
        if (variantData.shreddedData() != null) {
            children = new EncodeNode[]{coreStorage, encodeShredded(variantData, ctx, buffers)};
            shreddedProto = toProtoDtype(variantData.shreddedDtype());
        } else {
            children = new EncodeNode[]{coreStorage};
        }

        MemorySegment containerMeta = MemorySegment.ofArray(new ProtoVariantMetadata(shreddedProto).encode());
        EncodeNode root = new EncodeNode(EncodingId.VORTEX_VARIANT, containerMeta, children, new int[0]);
        return new EncodeResult(root, List.copyOf(buffers), null, null);
    }

    /// Encoders eligible to back the shredded child, tried in order by dtype.
    private static final List<EncodingEncoder> SHREDDED_FALLBACK = List.of(
            new PrimitiveEncodingEncoder(), new VarBinEncodingEncoder(),
            new BoolEncodingEncoder(), new NullEncodingEncoder());

    /// Encodes the shredded typed column as a child node, appending its buffers (remapped
    /// to follow the core-storage buffers already in `buffers`).
    private static EncodeNode encodeShredded(VariantData data, EncodeContext ctx, List<MemorySegment> buffers) {
        EncodingEncoder enc = null;
        for (EncodingEncoder e : SHREDDED_FALLBACK) {
            if (e.accepts(data.shreddedDtype())) {
                enc = e;
                break;
            }
        }
        if (enc == null) {
            throw new VortexException(EncodingId.VORTEX_VARIANT,
                    "no encoder for shredded dtype: " + data.shreddedDtype());
        }
        EncodeResult shredded = enc.encode(data.shreddedDtype(), data.shreddedData(), ctx);
        EncodeNode child = EncodeNode.remapBufferIndices(shredded.rootNode(), buffers.size());
        buffers.addAll(shredded.buffers());
        return child;
    }

    /// Converts a shreddable scalar dtype to its protobuf form for `ProtoVariantMetadata`.
    private static io.github.dfa1.vortex.core.proto.ProtoDType toProtoDtype(DType dtype) {
        return switch (dtype) {
            case DType.Primitive(var ptype, var nullable) -> io.github.dfa1.vortex.core.proto.ProtoDType.ofPrimitive(
                    new io.github.dfa1.vortex.core.proto.ProtoPrimitive(
                            io.github.dfa1.vortex.core.proto.ProtoPType.fromValue(ptype.ordinal()), nullable));
            case DType.Bool(var nullable) -> io.github.dfa1.vortex.core.proto.ProtoDType.ofBool(
                    new io.github.dfa1.vortex.core.proto.ProtoBool(nullable));
            case DType.Utf8(var nullable) -> io.github.dfa1.vortex.core.proto.ProtoDType.ofUtf8(
                    new io.github.dfa1.vortex.core.proto.ProtoUtf8(nullable));
            case DType.Binary(var nullable) -> io.github.dfa1.vortex.core.proto.ProtoDType.ofBinary(
                    new io.github.dfa1.vortex.core.proto.ProtoBinary(nullable));
            default -> throw new VortexException(EncodingId.VORTEX_VARIANT,
                    "shredded dtype not supported: " + dtype);
        };
    }

    /// Groups adjacent equal scalars into runs, appending each run's value and length.
    private static void coalesceRuns(List<ProtoScalar> values, List<ProtoScalar> runValues, List<Long> runLengths) {
        ProtoScalar prev = null;
        long runLen = 0;
        for (ProtoScalar s : values) {
            if (prev != null && prev.equals(s)) {
                runLen++;
            } else {
                if (prev != null) {
                    runValues.add(prev);
                    runLengths.add(runLen);
                }
                prev = s;
                runLen = 1;
            }
        }
        runValues.add(prev);
        runLengths.add(runLen);
    }

    /// Builds a buffer-backed `vortex.constant` child for one variant scalar, appending
    /// its serialized scalar to `buffers`.
    private static EncodeNode constantChild(ProtoScalar value, List<MemorySegment> buffers) {
        ProtoScalarValue scalar = ProtoScalarValue.ofVariantValue(value);
        int bufIdx = buffers.size();
        buffers.add(MemorySegment.ofArray(scalar.encode()));
        return EncodeNode.leaf(EncodingId.VORTEX_CONSTANT, bufIdx);
    }

    /// Builds a `vortex.chunked` node: child 0 is the cumulative `u64` run offsets, the
    /// rest are one constant child per run. Appends all buffers to `buffers`.
    private static EncodeNode chunkedConstants(List<ProtoScalar> runValues, List<Long> runLengths,
            EncodeContext ctx, List<MemorySegment> buffers) {
        int nruns = runValues.size();
        long[] offsets = new long[nruns + 1];
        for (int i = 0; i < nruns; i++) {
            offsets[i + 1] = offsets[i] + runLengths.get(i);
        }

        EncodeResult offsetsResult = ctx.lookupEncoder(EncodingId.VORTEX_PRIMITIVE).encode(DType.U64, offsets, ctx);
        buffers.addAll(offsetsResult.buffers());

        EncodeNode[] children = new EncodeNode[nruns + 1];
        children[0] = offsetsResult.rootNode();
        for (int i = 0; i < nruns; i++) {
            children[i + 1] = constantChild(runValues.get(i), buffers);
        }
        return new EncodeNode(EncodingId.VORTEX_CHUNKED, MemorySegment.ofArray(new byte[0]), children, new int[0]);
    }
}
