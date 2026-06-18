package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.encoding.EncodingId;
import io.github.dfa1.vortex.proto.Scalar;
import io.github.dfa1.vortex.proto.ScalarValue;
import io.github.dfa1.vortex.proto.VariantMetadata;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/// Write-only encoder for `vortex.variant`.
///
/// Emits the canonical variant container: a single `core_storage` child holding the
/// full value per row, no shredded child, and `VariantMetadata` with no `shredded_dtype`.
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

    private static final DType U64 = new DType.Primitive(PType.U64, false);

    /// Public no-arg constructor required by {@link java.util.ServiceLoader}.
    public VariantEncodingEncoder() {
    }

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

        List<Scalar> values = variantData.values();
        List<Scalar> runValues = new ArrayList<>();
        List<Long> runLengths = new ArrayList<>();
        coalesceRuns(values, runValues, runLengths);

        List<MemorySegment> buffers = new ArrayList<>();
        EncodeNode coreStorage = runValues.size() == 1
                ? constantChild(runValues.get(0), buffers)
                : chunkedConstants(runValues, runLengths, ctx, buffers);

        ByteBuffer containerMeta = ByteBuffer.wrap(new VariantMetadata(null).encode());
        EncodeNode root = new EncodeNode(EncodingId.VORTEX_VARIANT, containerMeta,
                new EncodeNode[]{coreStorage}, new int[0]);
        return new EncodeResult(root, List.copyOf(buffers), null, null);
    }

    /// Groups adjacent equal scalars into runs, appending each run's value and length.
    private static void coalesceRuns(List<Scalar> values, List<Scalar> runValues, List<Long> runLengths) {
        Scalar prev = null;
        long runLen = 0;
        for (Scalar s : values) {
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
    private static EncodeNode constantChild(Scalar value, List<MemorySegment> buffers) {
        ScalarValue scalar = ScalarValue.ofVariantValue(value);
        int bufIdx = buffers.size();
        buffers.add(MemorySegment.ofArray(scalar.encode()));
        return EncodeNode.leaf(EncodingId.VORTEX_CONSTANT, bufIdx);
    }

    /// Builds a `vortex.chunked` node: child 0 is the cumulative `u64` run offsets, the
    /// rest are one constant child per run. Appends all buffers to `buffers`.
    private static EncodeNode chunkedConstants(List<Scalar> runValues, List<Long> runLengths,
            EncodeContext ctx, List<MemorySegment> buffers) {
        int nruns = runValues.size();
        long[] offsets = new long[nruns + 1];
        for (int i = 0; i < nruns; i++) {
            offsets[i + 1] = offsets[i] + runLengths.get(i);
        }

        EncodeResult offsetsResult = ctx.lookupEncoder(EncodingId.VORTEX_PRIMITIVE).encode(U64, offsets, ctx);
        buffers.addAll(offsetsResult.buffers());

        EncodeNode[] children = new EncodeNode[nruns + 1];
        children[0] = offsetsResult.rootNode();
        for (int i = 0; i < nruns; i++) {
            children[i + 1] = constantChild(runValues.get(i), buffers);
        }
        return new EncodeNode(EncodingId.VORTEX_CHUNKED, ByteBuffer.wrap(new byte[0]), children, new int[0]);
    }
}
