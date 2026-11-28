package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.compute.PrimitiveArrays;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.DTypes;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.proto.ProtoPType;
import io.github.dfa1.vortex.core.proto.ProtoPatchesMetadata;
import io.github.dfa1.vortex.core.proto.ProtoRunEndMetadata;
import io.github.dfa1.vortex.core.proto.ProtoScalarValue;
import io.github.dfa1.vortex.core.proto.ProtoSparseMetadata;
import io.github.dfa1.vortex.core.simd.SimdOperationsSupport;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/// Rust 0.95's compression of a validity bitmap (`compress_bitmap` in `vortex-compressor`, with the
/// boolean schemes of `vortex-btrblocks/src/schemes/boolean.rs`): run-end over long runs, sparse
/// over a dominant value, whichever Rust estimates better, when either estimates better than the
/// bitmap. The result is kept only when its buffers are smaller than the bitmap's.
///
/// Rust compresses the bitmap from a fresh context, so its children cascade even below a parent
/// whose own cascade budget is spent.
final class BitmapCompressor {

    /// Rust's `MAX_CASCADE`, less the bitmap's own level.
    private static final int CHILD_DEPTH = 2;

    private BitmapCompressor() {
    }

    /// The validity child for `validity`: compressed as Rust compresses it, or the plain bitmap.
    ///
    /// @param validity per-row validity
    /// @param ctx      the encode context of the array the validity belongs to
    /// @return the encoded bitmap
    static EncodeResult compress(boolean[] validity, EncodeContext ctx) {
        EncodeResult bitmap = new BoolEncodingEncoder().encode(DTypes.BOOL, validity, ctx);
        int n = validity.length;
        // ponytail: all false stays a bitmap; Rust makes it a constant, which no validity reaching
        // here has been (all nulls is a null constant). All true is [#isAllValid(boolean[])].
        if (n == 0 || SimdOperationsSupport.preferred().allEqual(validity)) {
            return bitmap;
        }
        long bitmapBytes = (n + 7L) / 8;
        int trueCount = 0;
        int runs = 1;
        for (int i = 0; i < n; i++) {
            if (validity[i]) {
                trueCount++;
            }
            if (i > 0 && validity[i] != validity[i - 1]) {
                runs++;
            }
        }
        // Rust's estimates: a run costs a u64 end and a value bit, an exception a u32 index
        double runEndRatio = bitmapBytes / (runs * 8.125);
        double sparseRatio = bitmapBytes / (Math.min(trueCount, n - trueCount) * 4.0 + 2.0);
        if (runEndRatio <= 1.0 && sparseRatio <= 1.0) {
            return bitmap;
        }
        EncodeContext children = EncodeContext.ofDepth(CHILD_DEPTH, ctx.arena(), ctx.registry(), ctx.excluded())
                .withTrace(ctx.trace());
        // ties go to run-end, registered first
        EncodeResult compressed;
        if (sparseRatio > runEndRatio) {
            compressed = sparse(validity, trueCount, children);
        } else {
            compressed = runEnd(validity, runs, children);
        }
        if (bufferBytes(compressed) >= bitmapBytes) {
            return bitmap;
        }
        return compressed;
    }

    /// Whether Rust drops `validity` altogether: an all-true bitmap compresses to a
    /// `constant(true)` once that is smaller (from 17 rows on, as [CascadingCompressor] keeps
    /// bool arrays), and `child_to_validity` reads a true constant as all valid, which has no
    /// child. The patch values of a sparse with a null fill are one such array: every row valid.
    ///
    /// @param validity per-row validity
    /// @return `true` when the array gets no validity child
    static boolean isAllValid(boolean[] validity) {
        return (validity.length + 7) / 8 > CascadingCompressor.CONSTANT_BOOL_BYTES
                && validity[0] && SimdOperationsSupport.preferred().allEqual(validity);
    }

    /// `BoolSparseScheme`: the majority value as the fill, the other rows' indices as the patches,
    /// whose values are one constant.
    private static EncodeResult sparse(boolean[] validity, int trueCount, EncodeContext ctx) {
        int n = validity.length;
        boolean fill = trueCount > n / 2;
        int[] indices = new int[fill ? n - trueCount : trueCount];
        int p = 0;
        for (int i = 0; i < n; i++) {
            if (validity[i] != fill) {
                indices[p++] = i;
            }
        }
        PType indexPType = PType.narrowestUnsigned(indices[indices.length - 1]);
        EncodeResult indexResult = fullCompressor(ctx).encode(
                new DType.Primitive(indexPType, false),
                PrimitiveArrays.fromIntsArray(indices, indexPType, EncodingId.VORTEX_SPARSE), ctx);
        boolean[] patchValues = new boolean[indices.length];
        Arrays.fill(patchValues, !fill);
        EncodeResult valueResult = new ConstantEncodingEncoder().encode(DTypes.BOOL, patchValues, ctx);

        List<EncodedBuffer> buffers = new ArrayList<>();
        buffers.add(EncodedBuffer.bytes(MemorySegment.ofArray(ProtoScalarValue.ofBoolValue(fill).encode())));
        buffers.addAll(indexResult.encodedBuffers());
        EncodeNode indexNode = EncodeNode.remapBufferIndices(indexResult.rootNode(), 1);
        EncodeNode valueNode = EncodeNode.remapBufferIndices(valueResult.rootNode(), buffers.size());
        buffers.addAll(valueResult.encodedBuffers());
        byte[] metadata = new ProtoSparseMetadata(new ProtoPatchesMetadata(indices.length, 0L,
                ProtoPType.fromValue(indexPType.ordinal()), null, null, null)).encode();
        EncodeNode root = new EncodeNode(EncodingId.VORTEX_SPARSE, MemorySegment.ofArray(metadata),
                new EncodeNode[]{indexNode, valueNode}, new int[]{0});
        return new EncodeResult(root, List.copyOf(buffers), null, null);
    }

    /// `BoolRunEndScheme`: each run's end, narrowed and cascaded, and its value as a plain bitmap.
    private static EncodeResult runEnd(boolean[] validity, int runs, EncodeContext ctx) {
        int n = validity.length;
        int[] ends = new int[runs];
        boolean[] values = new boolean[runs];
        int r = 0;
        for (int i = 1; i < n; i++) {
            if (validity[i] != validity[i - 1]) {
                values[r] = validity[i - 1];
                ends[r++] = i;
            }
        }
        values[r] = validity[n - 1];
        ends[r] = n;
        PType endsPType = PType.narrowestUnsigned(n);
        EncodeResult endsResult = fullCompressor(ctx).encode(
                new DType.Primitive(endsPType, false),
                PrimitiveArrays.fromIntsArray(ends, endsPType, EncodingId.VORTEX_RUNEND), ctx);
        EncodeResult valuesResult = new BoolEncodingEncoder().encode(DTypes.BOOL, values, ctx);

        List<EncodedBuffer> buffers = new ArrayList<>(endsResult.encodedBuffers());
        EncodeNode valuesNode = EncodeNode.remapBufferIndices(valuesResult.rootNode(), buffers.size());
        buffers.addAll(valuesResult.encodedBuffers());
        byte[] metadata = new ProtoRunEndMetadata(ProtoPType.fromValue(endsPType.ordinal()), runs, 0L).encode();
        EncodeNode root = new EncodeNode(EncodingId.VORTEX_RUNEND, MemorySegment.ofArray(metadata),
                new EncodeNode[]{endsResult.rootNode(), valuesNode}, new int[0]);
        return new EncodeResult(root, List.copyOf(buffers), null, null);
    }

    /// Rust's `compress_child`: the indices and ends go through the full compressor, whose
    /// constant check keeps a one-value array primitive instead of a 0-bit pack.
    private static CascadingCompressor fullCompressor(EncodeContext ctx) {
        return new CascadingCompressor(List.copyOf(ctx.registry().encoderMap().values()));
    }

    private static long bufferBytes(EncodeResult result) {
        long total = 0;
        for (MemorySegment buffer : result.buffers()) {
            total += buffer.byteSize();
        }
        return total;
    }
}
