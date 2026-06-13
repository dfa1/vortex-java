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
/// range bin.  Chooses between NoOp delta (deltaVariant=0) and Consecutive delta
/// (deltaVariant=1, order=1) by comparing estimated bit cost; the cheaper path wins.
/// The result round-trips correctly with {@code PcoEncodingDecoder} for all supported ptypes.
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

            long noOpMin = unsignedMin(latents);
            long noOpMax = unsignedMax(latents);
            int noOpOffsetBits = offsetBits(noOpMin, noOpMax);
            long noOpCost = (long) n * noOpOffsetBits;

            // Consecutive delta: only cheaper when n is large enough to amortise the moment
            boolean useDelta = false;
            long[] deltas = null;
            long deltaMin = 0, deltaMax = 0;
            int deltaOffsetBits = 0;

            if (n > 1) {
                deltas = consecutiveDeltas(latents, dtypeSize);
                deltaMin = unsignedMin(deltas);
                deltaMax = unsignedMax(deltas);
                deltaOffsetBits = offsetBits(deltaMin, deltaMax);
                long deltaCost = dtypeSize + (long) (n - 1) * deltaOffsetBits;
                useDelta = deltaCost < noOpCost;
            }

            MemorySegment chunkMetaSeg;
            MemorySegment pageSeg;

            if (useDelta) {
                chunkMetaSeg = buildChunkMeta(dtypeSize, deltaMin, deltaOffsetBits, 1, 1, ctx.arena());
                pageSeg = buildPageDelta(latents[0], deltas, deltaMin, deltaOffsetBits, dtypeSize, ctx.arena());
            } else {
                chunkMetaSeg = buildChunkMeta(dtypeSize, noOpMin, noOpOffsetBits, 0, 0, ctx.arena());
                pageSeg = buildPageNoOp(latents, n, noOpMin, noOpOffsetBits, ctx.arena());
            }

            ByteBuffer metaBuf = buildMetadata(n);
            EncodeNode node = new EncodeNode(EncodingId.VORTEX_PCO, metaBuf, new EncodeNode[0], new int[]{0, 1});
            return new EncodeResult(node, List.of(chunkMetaSeg, pageSeg), null, null);
        }

        // ── chunk meta ────────────────────────────────────────────────────────

        private static MemorySegment buildChunkMeta(
                int dtypeSize, long binLower, int binOffsetBits,
                int deltaVariant, int deltaOrder, Arena arena) {
            LeBitWriter w = new LeBitWriter(32);
            w.writeBits(0, 4);            // mode = Classic (0)
            w.writeBits(deltaVariant, 4); // deltaVariant: 0=NoOp, 1=Consecutive
            if (deltaVariant == 1) {
                w.writeBits(deltaOrder, 3); // deltaOrder (3 bits)
                w.writeBits(0, 1);          // secondaryUsesDelta = false
            }
            // no deltaBins section (only present for deltaVariant=2 Lookback)
            w.writeBits(0, 4);             // ansSizeLog = 0 → 1-state table
            w.writeBits(1, 15);            // nBins = 1
            // bin[0]: weight field = readBits(ansSizeLog=0) → 0 bits written
            w.writeBits(binLower, dtypeSize);
            w.writeBits(binOffsetBits, bitsToEncodeOffsetBits(dtypeSize));
            // no secondary bins (only present for modes 1/2/3)
            w.alignToByte();
            return w.toMemorySegment(arena);
        }

        // ── page: NoOp delta ──────────────────────────────────────────────────

        private static MemorySegment buildPageNoOp(
                long[] latents, int n, long minLatent, int offsetBits, Arena arena) {
            // Header: 0 moment bits (deltaOrder=0), 4×0 ANS state bits (ansSizeLog=0),
            // alignToByte noop. Body: n×offsetBits offset values.
            long pageSizeBytes = ((long) n * offsetBits + 7) / 8 + 4;
            LeBitWriter w = new LeBitWriter((int) Math.min(pageSizeBytes, Integer.MAX_VALUE));
            for (int i = 0; i < n; i++) {
                w.writeBits(latents[i] - minLatent, offsetBits);
            }
            w.alignToByte();
            return w.toMemorySegment(arena);
        }

        // ── page: Consecutive delta ───────────────────────────────────────────

        private static MemorySegment buildPageDelta(
                long moment, long[] deltas, long minDelta, int deltaOffsetBits,
                int dtypeSize, Arena arena) {
            // Header: moment[0] = first latent (dtypeSize bits), 4×0 ANS state bits,
            // alignToByte (noop — dtypeSize is always a multiple of 8).
            // Body: (n-1)×deltaOffsetBits offset values for the centered deltas.
            long pageSizeBytes = ((long) dtypeSize + (long) deltas.length * deltaOffsetBits + 7) / 8 + 4;
            LeBitWriter w = new LeBitWriter((int) Math.min(pageSizeBytes, Integer.MAX_VALUE));
            w.writeBits(moment, dtypeSize);
            w.alignToByte();
            for (long delta : deltas) {
                w.writeBits(delta - minDelta, deltaOffsetBits);
            }
            w.alignToByte();
            return w.toMemorySegment(arena);
        }

        // ── delta computation ─────────────────────────────────────────────────

        // Compute centered deltas: centeredDelta[i] = ((L[i+1] - L[i]) & mask) ^ mid.
        // This is what the decoder's applyConsecutiveDelta expects in the ANS stream.
        private static long[] consecutiveDeltas(long[] latents, int dtypeSize) {
            long mid = typeMid(dtypeSize);
            long mask = typeMask(dtypeSize);
            long[] deltas = new long[latents.length - 1];
            for (int i = 0; i < deltas.length; i++) {
                deltas[i] = ((latents[i + 1] - latents[i]) & mask) ^ mid;
            }
            return deltas;
        }

        // ── metadata ──────────────────────────────────────────────────────────

        private static EncodeResult encodeEmpty(Arena arena) {
            byte[] header = {PCO_FORMAT_MAJOR, PCO_FORMAT_MINOR};
            PcoMetadata meta = new PcoMetadata(header, List.of());
            ByteBuffer metaBuf = ByteBuffer.wrap(meta.encode());
            EncodeNode node = new EncodeNode(EncodingId.VORTEX_PCO, metaBuf, new EncodeNode[0], new int[0]);
            return new EncodeResult(node, List.of(), null, null);
        }

        private static ByteBuffer buildMetadata(int n) {
            byte[] header = {PCO_FORMAT_MAJOR, PCO_FORMAT_MINOR};
            PcoChunkInfo chunkInfo = new PcoChunkInfo(List.of(new PcoPageInfo(n)));
            PcoMetadata meta = new PcoMetadata(header, List.of(chunkInfo));
            return ByteBuffer.wrap(meta.encode());
        }

        // ── latent conversion ─────────────────────────────────────────────────

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

        // ── bit-width helpers ─────────────────────────────────────────────────

        private static long unsignedMin(long[] values) {
            long min = values[0];
            for (long v : values) {
                if (Long.compareUnsigned(v, min) < 0) {
                    min = v;
                }
            }
            return min;
        }

        private static long unsignedMax(long[] values) {
            long max = values[0];
            for (long v : values) {
                if (Long.compareUnsigned(v, max) > 0) {
                    max = v;
                }
            }
            return max;
        }

        private static int offsetBits(long min, long max) {
            long range = max - min;
            return range == 0 ? 0 : 64 - Long.numberOfLeadingZeros(range);
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

        private static long typeMid(int dtypeSize) {
            return switch (dtypeSize) {
                case 64 -> Long.MIN_VALUE;
                case 32 -> 0x80000000L;
                case 16 -> 0x8000L;
                default -> throw new VortexException(EncodingId.VORTEX_PCO, "invalid dtypeSize: " + dtypeSize);
            };
        }

        private static long typeMask(int dtypeSize) {
            return switch (dtypeSize) {
                case 64 -> -1L;
                case 32 -> 0xFFFFFFFFL;
                case 16 -> 0xFFFFL;
                default -> throw new VortexException(EncodingId.VORTEX_PCO, "invalid dtypeSize: " + dtypeSize);
            };
        }
    }
}
