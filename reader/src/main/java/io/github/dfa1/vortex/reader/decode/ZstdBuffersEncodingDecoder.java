package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.io.IoBounds;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.proto.ProtoZstdBuffersMetadata;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.zstd.ZstdDecompressContext;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Arrays;

/// Read-only decoder for `vortex.zstd_buffers` (the opt-in `zstd2026.02.0` edition): an arbitrary
/// inner array whose buffers were each Zstd-compressed independently, keeping their layout. Mirrors
/// Rust's `ZstdBuffers::deserialize` + `build_inner`: the metadata names the inner encoding and
/// carries its metadata plus each buffer's uncompressed size and alignment; the children are the
/// inner array's own, passed through untouched.
///
/// Decoding decompresses every buffer into an arena segment at its declared alignment, appends
/// those segments to the context's segment table, and decodes a synthetic inner node over them
/// through the registry, so any registered encoding can sit inside.
public final class ZstdBuffersEncodingDecoder implements EncodingDecoder {

    // ponytail: Rust stores alignment as a u16-sized power of two; capping at 64 KiB keeps a crafted
    // value from turning a small buffer into a huge aligned allocation. Raise it if Rust ever does.
    private static final long MAX_ALIGNMENT = 1L << 16;

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_ZSTD_BUFFERS;
    }

    @Override
    public Array decode(DecodeContext ctx) {
        ZstdEncodingDecoder.requireBinding(ZstdEncodingDecoder.ZSTD_BINDING_PRESENT, EncodingId.VORTEX_ZSTD_BUFFERS);
        // A zstd_buffers inner array may itself be zstd_buffers, nested through the metadata rather
        // than the node tree, so the file's depth guard never sees it. Unwrap those levels in a loop
        // instead of recursing through the registry: each nested metadata is strictly smaller, so
        // the loop ends, and a crafted chain cannot overflow the stack.
        DecodeContext current = ctx;
        do {
            current = unwrap(current);
        } while (current.node().encodingId() == EncodingId.VORTEX_ZSTD_BUFFERS);
        return current.registry().decode(current);
    }

    /// Decompresses one `vortex.zstd_buffers` level, returning the context for its inner array.
    private static DecodeContext unwrap(DecodeContext ctx) {
        ProtoZstdBuffersMetadata meta = parseMetadata(ctx.metadata());

        int nBuffers = ctx.node().bufferIndices().length;
        if (meta.uncompressed_sizes().size() != nBuffers || meta.buffer_alignments().size() != nBuffers) {
            throw new VortexException(EncodingId.VORTEX_ZSTD_BUFFERS, nBuffers + " compressed buffers but "
                    + meta.uncompressed_sizes().size() + " sizes and " + meta.buffer_alignments().size() + " alignments");
        }
        int nChildren = ctx.node().children().length;
        if (meta.child_dtypes().size() != nChildren || meta.child_lens().size() != nChildren) {
            throw new VortexException(EncodingId.VORTEX_ZSTD_BUFFERS, nChildren + " children but "
                    + meta.child_dtypes().size() + " child dtypes and " + meta.child_lens().size() + " child lengths");
        }
        if (meta.inner_encoding_id() == null || meta.inner_encoding_id().isBlank()) {
            throw new VortexException(EncodingId.VORTEX_ZSTD_BUFFERS, "missing inner encoding id");
        }

        int base = ctx.segmentBuffers().length;
        MemorySegment[] segments = Arrays.copyOf(ctx.segmentBuffers(), base + nBuffers);
        int[] innerBuffers = new int[nBuffers];
        try (ZstdDecompressContext dctx = new ZstdDecompressContext();
             Arena scratch = Arena.ofConfined()) {
            for (int i = 0; i < nBuffers; i++) {
                int size = IoBounds.toIntSize(meta.uncompressed_sizes().get(i));
                MemorySegment dst = ctx.arena().allocate(size, alignment(meta.buffer_alignments().get(i)));
                MemorySegment src = ZstdEncodingDecoder.asNative(ctx.buffer(i), scratch);
                ZstdEncodingDecoder.decompressFrame(dctx, dst, src, null, EncodingId.VORTEX_ZSTD_BUFFERS, i);
                segments[base + i] = dst;
                innerBuffers[i] = base + i;
            }
        }

        // Empty inner metadata stays an empty segment, not null: an all-default proto (e.g. varbin
        // with U8 offsets) encodes to zero bytes, and decoders read null as "missing metadata".
        byte[] innerMetadata = meta.inner_metadata() == null ? new byte[0] : meta.inner_metadata();
        ArrayNode inner = new ArrayNode(EncodingId.parse(meta.inner_encoding_id()),
                MemorySegment.ofArray(innerMetadata), ctx.node().children(), innerBuffers);
        return new DecodeContext(inner, ctx.dtype(), ctx.rowCount(), segments, ctx.registry(), ctx.arena());
    }

    private static long alignment(int declared) {
        long value = Integer.toUnsignedLong(declared);
        if (value == 0 || Long.bitCount(value) != 1 || value > MAX_ALIGNMENT) {
            throw new VortexException(EncodingId.VORTEX_ZSTD_BUFFERS,
                    "buffer alignment must be a power of two up to " + MAX_ALIGNMENT + ", got " + value);
        }
        return value;
    }

    private static ProtoZstdBuffersMetadata parseMetadata(MemorySegment rawMeta) {
        if (rawMeta == null || rawMeta.byteSize() == 0) {
            throw new VortexException(EncodingId.VORTEX_ZSTD_BUFFERS, "missing metadata");
        }
        try {
            return ProtoZstdBuffersMetadata.decode(rawMeta, 0, rawMeta.byteSize());
        } catch (IOException e) {
            throw new VortexException(EncodingId.VORTEX_ZSTD_BUFFERS, "invalid metadata", e);
        }
    }
}
