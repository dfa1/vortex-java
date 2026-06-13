package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.encoding.EncodingId;
import io.github.dfa1.vortex.proto.PcoChunkInfo;
import io.github.dfa1.vortex.proto.PcoMetadata;
import io.github.dfa1.vortex.proto.PcoPageInfo;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.util.List;

/// Write-only encoder for {@code vortex.pco}.
///
/// <p>Encodes integer and floating-point primitives using Classic mode with a single
/// range bin and no delta encoding.  The result round-trips correctly with
/// {@code PcoEncodingDecoder} for all supported ptypes.
public final class PcoEncodingEncoder implements EncodingEncoder {

    private static final byte PCO_FORMAT_MAJOR = 0x04;
    private static final byte PCO_FORMAT_MINOR = 0x01;

    /// Public no-arg constructor required by {@link java.util.ServiceLoader}.
    public PcoEncodingEncoder() {
    }

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_PCO;
    }

    @Override
    public boolean accepts(DType dtype) {
        if (!(dtype instanceof DType.Primitive p)) {
            return false;
        }
        return switch (p.ptype()) {
            case I16, U16, I32, U32, F32, I64, U64, F64 -> true;
            default -> false;
        };
    }

    @Override
    public EncodeResult encode(DType dtype, Object data, EncodeContext ctx) {
        return Encoder.encode(dtype, data, ctx);
    }

    private static final class Encoder {

        static EncodeResult encode(DType dtype, Object data, EncodeContext ctx) {
            PType ptype = ((DType.Primitive) dtype).ptype();
            int dtypeSize = dtypeSize(ptype);
            long[] latents = toLatents(ptype, data);
            int n = latents.length;

            if (n == 0) {
                return encodeEmpty(ctx.arena());
            }

            long minLatent = latents[0];
            long maxLatent = latents[0];
            for (long l : latents) {
                if (Long.compareUnsigned(l, minLatent) < 0) {
                    minLatent = l;
                }
                if (Long.compareUnsigned(l, maxLatent) > 0) {
                    maxLatent = l;
                }
            }

            long range = maxLatent - minLatent;
            int offsetBits = range == 0 ? 0 : 64 - Long.numberOfLeadingZeros(range);

            MemorySegment chunkMetaSeg = buildChunkMeta(dtypeSize, minLatent, offsetBits, ctx.arena());
            MemorySegment pageSeg = buildPage(latents, n, minLatent, offsetBits, ctx.arena());
            ByteBuffer metaBuf = buildMetadata(n);
            EncodeNode node = new EncodeNode(EncodingId.VORTEX_PCO, metaBuf, new EncodeNode[0], new int[]{0, 1});
            return new EncodeResult(node, List.of(chunkMetaSeg, pageSeg), null, null);
        }

        private static EncodeResult encodeEmpty(Arena arena) {
            byte[] header = {PCO_FORMAT_MAJOR, PCO_FORMAT_MINOR};
            PcoMetadata meta = new PcoMetadata(header, List.of());
            ByteBuffer metaBuf = ByteBuffer.wrap(meta.encode());
            EncodeNode node = new EncodeNode(EncodingId.VORTEX_PCO, metaBuf, new EncodeNode[0], new int[0]);
            return new EncodeResult(node, List.of(), null, null);
        }

        private static ByteBuffer buildMetadata(int n) {
            byte[] header = {PCO_FORMAT_MAJOR, PCO_FORMAT_MINOR};
            PcoPageInfo pageInfo = new PcoPageInfo(n);
            PcoChunkInfo chunkInfo = new PcoChunkInfo(List.of(pageInfo));
            PcoMetadata meta = new PcoMetadata(header, List.of(chunkInfo));
            return ByteBuffer.wrap(meta.encode());
        }

        private static MemorySegment buildChunkMeta(int dtypeSize, long minLatent, int offsetBits, Arena arena) {
            LeBitWriter w = new LeBitWriter(32);
            w.writeBits(0, 4);  // mode = Classic
            w.writeBits(0, 4);  // deltaVariant = NoOp
            w.writeBits(0, 4);  // ansSizeLog = 0 (1-state table)
            w.writeBits(1, 15); // nBins = 1
            // bin[0]: weight-1 = readBits(ansSizeLog=0) → 0 bits; lower; offsetBits
            w.writeBits(minLatent, dtypeSize);
            w.writeBits(offsetBits, bitsToEncodeOffsetBits(dtypeSize));
            w.alignToByte();
            return w.toMemorySegment(arena);
        }

        private static MemorySegment buildPage(long[] latents, int n, long minLatent, int offsetBits, Arena arena) {
            // Header: 0 moment bits (deltaOrder=0), 4×0 initial ANS state bits (ansSizeLog=0), alignToByte noop.
            // Body: for each batch of 256: phase-1 emits 0 ANS bits; phase-2 emits offsetBits per element.
            long pageSizeBytes = ((long) n * offsetBits + 7) / 8 + 4;
            LeBitWriter w = new LeBitWriter((int) Math.min(pageSizeBytes, Integer.MAX_VALUE));
            for (int i = 0; i < n; i++) {
                w.writeBits(latents[i] - minLatent, offsetBits);
            }
            w.alignToByte();
            return w.toMemorySegment(arena);
        }

        private static long[] toLatents(PType ptype, Object data) {
            return switch (ptype) {
                case I16 -> {
                    short[] arr = (short[]) data;
                    long[] l = new long[arr.length];
                    for (int i = 0; i < arr.length; i++) {
                        l[i] = (arr[i] & 0xFFFFL) ^ 0x8000L;
                    }
                    yield l;
                }
                case U16 -> {
                    short[] arr = (short[]) data;
                    long[] l = new long[arr.length];
                    for (int i = 0; i < arr.length; i++) {
                        l[i] = arr[i] & 0xFFFFL;
                    }
                    yield l;
                }
                case I32 -> {
                    int[] arr = (int[]) data;
                    long[] l = new long[arr.length];
                    for (int i = 0; i < arr.length; i++) {
                        l[i] = (arr[i] & 0xFFFFFFFFL) ^ 0x80000000L;
                    }
                    yield l;
                }
                case U32 -> {
                    int[] arr = (int[]) data;
                    long[] l = new long[arr.length];
                    for (int i = 0; i < arr.length; i++) {
                        l[i] = arr[i] & 0xFFFFFFFFL;
                    }
                    yield l;
                }
                case I64 -> {
                    long[] arr = (long[]) data;
                    long[] l = new long[arr.length];
                    for (int i = 0; i < arr.length; i++) {
                        l[i] = arr[i] ^ Long.MIN_VALUE;
                    }
                    yield l;
                }
                case U64 -> {
                    long[] arr = (long[]) data;
                    long[] l = new long[arr.length];
                    System.arraycopy(arr, 0, l, 0, arr.length);
                    yield l;
                }
                case F32 -> {
                    float[] arr = (float[]) data;
                    long[] l = new long[arr.length];
                    for (int i = 0; i < arr.length; i++) {
                        int bits = Float.floatToRawIntBits(arr[i]);
                        l[i] = (bits & 0x80000000) != 0
                                ? (~bits) & 0xFFFFFFFFL
                                : (bits ^ 0x80000000) & 0xFFFFFFFFL;
                    }
                    yield l;
                }
                case F64 -> {
                    double[] arr = (double[]) data;
                    long[] l = new long[arr.length];
                    for (int i = 0; i < arr.length; i++) {
                        long bits = Double.doubleToRawLongBits(arr[i]);
                        l[i] = (bits & Long.MIN_VALUE) != 0 ? ~bits : bits ^ Long.MIN_VALUE;
                    }
                    yield l;
                }
                default -> throw new VortexException(EncodingId.VORTEX_PCO, "unsupported ptype: " + ptype);
            };
        }

        private static int dtypeSize(PType ptype) {
            return switch (ptype) {
                case I16, U16 -> 16;
                case I32, U32, F32 -> 32;
                case I64, U64, F64 -> 64;
                default -> throw new VortexException(EncodingId.VORTEX_PCO, "unsupported ptype: " + ptype);
            };
        }

        private static int bitsToEncodeOffsetBits(int dtypeSize) {
            return switch (dtypeSize) {
                case 64 -> 7;
                case 32 -> 6;
                case 16 -> 5;
                default -> throw new VortexException(EncodingId.VORTEX_PCO, "invalid dtypeSize: " + dtypeSize);
            };
        }
    }
}
