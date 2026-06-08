package io.github.dfa1.vortex.encoding;

import com.google.protobuf.CodedInputStream;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.array.ByteArray;
import io.github.dfa1.vortex.core.array.DoubleArray;
import io.github.dfa1.vortex.core.array.FloatArray;
import io.github.dfa1.vortex.core.array.IntArray;
import io.github.dfa1.vortex.core.array.LongArray;
import io.github.dfa1.vortex.core.array.ShortArray;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;

/// Decoder for {@code vortex.patched}.
///
/// <p>Wire format:
/// <ul>
///   <li>Metadata: proto {@code PatchedMetadata {n_patches=1, n_lanes=2, offset=3}} (all uint32).</li>
///   <li>Buffers: none.</li>
///   <li>Child 0: inner — base values, same dtype and row count as the outer array.</li>
///   <li>Child 1: lane_offsets — U32 array of length {@code n_chunks * n_lanes + 1}.</li>
///   <li>Child 2: patch_indices — U16 array of length {@code n_patches}.</li>
///   <li>Child 3: patch_values — same dtype as outer, length {@code n_patches}.</li>
/// </ul>
///
/// <p>Decode: materialise the inner array, then overwrite positions using the
/// lane-based scatter: for each chunk {@code c}, entries at indices
/// {@code lane_offsets[c*n_lanes] .. lane_offsets[c*n_lanes+n_lanes]} in the
/// patch tables address positions {@code c*1024 + patch_indices[i]} in the output.
public final class PatchedEncoding implements Encoding {

    /// Creates a new {@code PatchedEncoding} instance; use via {@link EncodingRegistry}.
    public PatchedEncoding() {
    }

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_PATCHED;
    }

    @Override
    public boolean accepts(DType dtype) {
        return false;
    }

    @Override
    public EncodeResult encode(DType dtype, Object data, EncodeContext ctx) {
        throw new VortexException(EncodingId.VORTEX_PATCHED, "encode not yet implemented");
    }

    @Override
    public Array decode(DecodeContext ctx) {
        return Decoder.decode(ctx);
    }

    private static final class Decoder {

        static Array decode(DecodeContext ctx) {
            ByteBuffer rawMeta = ctx.metadata();
            if (rawMeta == null || !rawMeta.hasRemaining()) {
                throw new VortexException(EncodingId.VORTEX_PATCHED, "missing metadata");
            }

            long nPatches = 0;
            long nLanes = 0;
            long offset = 0;
            try {
                CodedInputStream cin = CodedInputStream.newInstance(rawMeta.duplicate());
                while (!cin.isAtEnd()) {
                    int tag = cin.readTag();
                    switch (tag >>> 3) {
                        case 1 -> nPatches = Integer.toUnsignedLong(cin.readUInt32());
                        case 2 -> nLanes = Integer.toUnsignedLong(cin.readUInt32());
                        case 3 -> offset = Integer.toUnsignedLong(cin.readUInt32());
                        default -> cin.skipField(tag);
                    }
                }
            } catch (IOException e) {
                throw new VortexException(EncodingId.VORTEX_PATCHED, "invalid metadata", e);
            }

            if (nLanes == 0) {
                throw new VortexException(EncodingId.VORTEX_PATCHED, "n_lanes must be > 0");
            }

            if (!(ctx.dtype() instanceof DType.Primitive p)) {
                throw new VortexException(EncodingId.VORTEX_PATCHED,
                        "expected primitive dtype, got " + ctx.dtype());
            }

            PType ptype = p.ptype();
            long n = ctx.rowCount();
            long nChunks = (n + offset + 1023) / 1024;
            int elemBytes = ptype.byteSize();

            MemorySegment innerSeg = ctx.decodeChildSegment(0, ctx.dtype(), n);
            MemorySegment laneOffsetsSeg = ctx.decodeChildSegment(1,
                    new DType.Primitive(PType.U32, false), nChunks * nLanes + 1);
            MemorySegment patchIndicesSeg = ctx.decodeChildSegment(2,
                    new DType.Primitive(PType.U16, false), nPatches);
            MemorySegment patchValuesSeg = ctx.decodeChildSegment(3, ctx.dtype(), nPatches);

            MemorySegment out = ctx.arena().allocate(n * elemBytes);
            SegmentBroadcast.broadcastCopy(innerSeg, out, n, elemBytes);

            if (nPatches > 0) {
                applyPatches(out, n, nChunks, nLanes, offset, elemBytes,
                        laneOffsetsSeg, patchIndicesSeg, patchValuesSeg);
            }

            return switch (ptype) {
                case I8, U8 -> new ByteArray(ctx.dtype(), n, out);
                case I16, U16 -> new ShortArray(ctx.dtype(), n, out);
                case I32, U32 -> new IntArray(ctx.dtype(), n, out);
                case I64, U64 -> new LongArray(ctx.dtype(), n, out);
                case F32 -> new FloatArray(ctx.dtype(), n, out);
                case F64 -> new DoubleArray(ctx.dtype(), n, out);
                default -> throw new VortexException(EncodingId.VORTEX_PATCHED,
                        "unsupported ptype: " + ptype);
            };
        }

        private static void applyPatches(
                MemorySegment out, long n, long nChunks, long nLanes, long offset, int elemBytes,
                MemorySegment laneOffsets, MemorySegment patchIndices, MemorySegment patchValues
        ) {
            long laneCap = SegmentBroadcast.capacity(laneOffsets, 4);
            long idxCap = SegmentBroadcast.capacity(patchIndices, 2);
            long valCap = SegmentBroadcast.capacity(patchValues, elemBytes);
            for (long chunk = 0; chunk < nChunks; chunk++) {
                long start = Integer.toUnsignedLong(
                        laneOffsets.getAtIndex(PTypeIO.LE_INT, (chunk * nLanes) % laneCap));
                long stop = Integer.toUnsignedLong(
                        laneOffsets.getAtIndex(PTypeIO.LE_INT, (chunk * nLanes + nLanes) % laneCap));

                for (long i = start; i < stop; i++) {
                    long physicalIdx = chunk * 1024
                            + Short.toUnsignedLong(patchIndices.getAtIndex(PTypeIO.LE_SHORT, i % idxCap));
                    if (physicalIdx < offset || physicalIdx >= offset + n) {
                        continue;
                    }
                    long outputIdx = physicalIdx - offset;
                    MemorySegment.copy(patchValues, (i % valCap) * elemBytes, out, outputIdx * elemBytes, elemBytes);
                }
            }
        }
    }
}
