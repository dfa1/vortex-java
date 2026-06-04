package io.github.dfa1.vortex.encoding;

import com.google.protobuf.InvalidProtocolBufferException;
import io.github.dfa1.vortex.proto.EncodingProtos;
import io.github.dfa1.vortex.proto.ScalarProtos;
import io.github.dfa1.vortex.core.ArrayStats;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.array.ByteArray;
import io.github.dfa1.vortex.core.array.IntArray;
import io.github.dfa1.vortex.core.array.LongArray;
import io.github.dfa1.vortex.core.array.ShortArray;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.util.List;

/// Encoding for {@code fastlanes.delta} — FastLanes delta encoding for integer columns.
///
/// Wire format matches the Rust reference implementation:
/// <ul>
///   <li>0 buffers, 2 child slots: {@code bases} (child 0) and {@code deltas} (child 1).</li>
///   <li>Metadata: protobuf {@code DeltaMetadata{deltas_len, offset}}.</li>
///   <li>{@code bases} holds {@code (paddedLen / 1024) * LANES} elements of the column dtype.</li>
///   <li>{@code deltas} holds {@code paddedLen} elements (padded to a multiple of 1024).</li>
/// </ul>
///
/// Algorithm: FastLanes transpose → per-lane delta with wrapping arithmetic.
/// {@code LANES = 1024 / typeBits}, where {@code typeBits = byteSize * 8}.
public final class DeltaEncoding implements Encoding {

    static final int FL_CHUNK_SIZE = 1024;
    static final int[] FL_ORDER = {0, 4, 2, 6, 1, 5, 3, 7};

    @Override
    public EncodingId encodingId() {
        return EncodingId.FASTLANES_DELTA;
    }

    @Override
    public boolean accepts(DType dtype) {
        if (!(dtype instanceof DType.Primitive p)) {
            return false;
        }
        return switch (p.ptype()) {
            case I8, I16, I32, I64, U8, U16, U32, U64 -> true;
            default -> false;
        };
    }

    @Override
    public EncodeResult encode(DType dtype, Object data) {
        return Encoder.encode(dtype, data);
    }

    @Override
    public Array decode(DecodeContext ctx) {
        return Decoder.decode(ctx);
    }

    // ── Shared helpers (FastLanes index + type math + buffer I/O) ────────────

    static int transposeIndex(int idx) {
        int lane = idx % 16;
        int order = (idx / 16) % 8;
        int row = idx / 128;
        return lane * 64 + FL_ORDER[order] * 8 + row;
    }

    static int iterateIndex(int row, int lane) {
        int o = row / 8;
        int s = row % 8;
        return FL_ORDER[o] * 16 + s * 128 + lane;
    }

    static int lanes(PType ptype) {
        return FL_CHUNK_SIZE / (ptype.byteSize() * 8);
    }

    static int typeBits(PType ptype) {
        return ptype.byteSize() * 8;
    }

    static long typeMask(PType ptype) {
        int bits = ptype.byteSize() * 8;
        return bits == 64 ? -1L : (1L << bits) - 1;
    }

    static MemorySegment fromLongs(long[] longs, PType ptype, SegmentAllocator arena) {
        if (ptype == PType.I64 || ptype == PType.U64) {
            MemorySegment dst = arena.allocate((long) longs.length * 8);
            MemorySegment.copy(MemorySegment.ofArray(longs), ValueLayout.JAVA_LONG, 0L,
                    dst, PTypeIO.LE_LONG, 0L, longs.length);
            return dst;
        }
        int n = longs.length;
        long elemSize = ptype.byteSize();
        MemorySegment seg = arena.allocate(n * elemSize);
        for (int i = 0; i < n; i++) {
            PTypeIO.set(seg, i * elemSize, ptype, longs[i]);
        }
        return seg;
    }

    private static final class Encoder {

        static EncodeResult encode(DType dtype, Object data) {
            PType ptype = ((DType.Primitive) dtype).ptype();
            long[] longs = toLongs(data, ptype);
            int n = longs.length;
            int typeBits = typeBits(ptype);
            int lanes = lanes(ptype);
            long mask = typeMask(ptype);
            boolean unsign = isUnsigned(ptype);

            long minVal = 0L, maxVal = 0L;
            if (n > 0) {
                minVal = longs[0];
                maxVal = longs[0];
                for (int i = 1; i < n; i++) {
                    long v = longs[i];
                    if (unsign ? Long.compareUnsigned(v, minVal) < 0 : v < minVal) {
                        minVal = v;
                    }
                    if (unsign ? Long.compareUnsigned(v, maxVal) > 0 : v > maxVal) {
                        maxVal = v;
                    }
                }
            }

            int numChunks = n == 0 ? 0 : (n + FL_CHUNK_SIZE - 1) / FL_CHUNK_SIZE;
            long paddedLen = (long) numChunks * FL_CHUNK_SIZE;
            int basesLen = numChunks * lanes;

            long[] basesAll = new long[basesLen];
            long[] deltasAll = new long[(int) paddedLen];
            long[] chunkBuf = new long[FL_CHUNK_SIZE];
            long[] transposed = new long[FL_CHUNK_SIZE];
            long[] chunkBases = new long[lanes];
            long[] chunkDelta = new long[FL_CHUNK_SIZE];

            for (int chunk = 0; chunk < numChunks; chunk++) {
                int start = chunk * FL_CHUNK_SIZE;
                int end = Math.min(start + FL_CHUNK_SIZE, n);
                for (int i = start; i < end; i++) {
                    chunkBuf[i - start] = longs[i] & mask;
                }
                for (int i = end - start; i < FL_CHUNK_SIZE; i++) {
                    chunkBuf[i] = 0L;
                }
                for (int i = 0; i < FL_CHUNK_SIZE; i++) {
                    transposed[i] = chunkBuf[transposeIndex(i)];
                }
                int basesOff = chunk * lanes;
                System.arraycopy(transposed, 0, basesAll, basesOff, lanes);
                System.arraycopy(basesAll, basesOff, chunkBases, 0, lanes);
                deltaChunk(transposed, chunkBases, lanes, typeBits, mask, chunkDelta);
                System.arraycopy(chunkDelta, 0, deltasAll, chunk * FL_CHUNK_SIZE, FL_CHUNK_SIZE);
            }

            MemorySegment basesSeg = fromLongs(basesAll, ptype, Arena.ofAuto());
            MemorySegment deltasSeg = fromLongs(deltasAll, ptype, Arena.ofAuto());

            byte[] metaBytes = EncodingProtos.DeltaMetadata.newBuilder()
                    .setDeltasLen(paddedLen)
                    .setOffset(0)
                    .build()
                    .toByteArray();

            byte[] statsMin = n > 0 ? statsBytes(ptype, minVal) : null;
            byte[] statsMax = n > 0 ? statsBytes(ptype, maxVal) : null;

            EncodeNode basesNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, 0);
            EncodeNode deltasNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, 1);
            EncodeNode root = new EncodeNode(EncodingId.FASTLANES_DELTA, ByteBuffer.wrap(metaBytes),
                    new EncodeNode[]{basesNode, deltasNode}, new int[0]);
            return new EncodeResult(root, List.of(basesSeg, deltasSeg), statsMin, statsMax);
        }

        private static void deltaChunk(long[] transposed, long[] bases, int lanes, int typeBits, long mask, long[] out) {
            for (int lane = 0; lane < lanes; lane++) {
                long prev = bases[lane] & mask;
                for (int row = 0; row < typeBits; row++) {
                    int idx = iterateIndex(row, lane);
                    long next = transposed[idx] & mask;
                    out[idx] = (next - prev) & mask;
                    prev = next;
                }
            }
        }

        private static long[] toLongs(Object data, PType ptype) {
            return switch (ptype) {
                case I8 -> {
                    byte[] arr = (byte[]) data;
                    long[] r = new long[arr.length];
                    for (int i = 0; i < arr.length; i++) {
                        r[i] = arr[i];
                    }
                    yield r;
                }
                case U8 -> {
                    byte[] arr = (byte[]) data;
                    long[] r = new long[arr.length];
                    for (int i = 0; i < arr.length; i++) {
                        r[i] = Byte.toUnsignedLong(arr[i]);
                    }
                    yield r;
                }
                case I16 -> {
                    short[] arr = (short[]) data;
                    long[] r = new long[arr.length];
                    for (int i = 0; i < arr.length; i++) {
                        r[i] = arr[i];
                    }
                    yield r;
                }
                case U16 -> {
                    short[] arr = (short[]) data;
                    long[] r = new long[arr.length];
                    for (int i = 0; i < arr.length; i++) {
                        r[i] = Short.toUnsignedLong(arr[i]);
                    }
                    yield r;
                }
                case I32 -> {
                    int[] arr = (int[]) data;
                    long[] r = new long[arr.length];
                    for (int i = 0; i < arr.length; i++) {
                        r[i] = arr[i];
                    }
                    yield r;
                }
                case U32 -> {
                    int[] arr = (int[]) data;
                    long[] r = new long[arr.length];
                    for (int i = 0; i < arr.length; i++) {
                        r[i] = Integer.toUnsignedLong(arr[i]);
                    }
                    yield r;
                }
                case I64, U64 -> (long[]) data;
                default -> throw new VortexException(EncodingId.FASTLANES_DELTA, "unsupported ptype: " + ptype);
            };
        }

        private static boolean isUnsigned(PType ptype) {
            return switch (ptype) {
                case U8, U16, U32, U64 -> true;
                default -> false;
            };
        }

        private static byte[] statsBytes(PType ptype, long value) {
            if (isUnsigned(ptype)) {
                return ScalarProtos.ScalarValue.newBuilder().setUint64Value(value).build().toByteArray();
            }
            return ScalarProtos.ScalarValue.newBuilder().setInt64Value(value).build().toByteArray();
        }
    }

    private static final class Decoder {

        static Array decode(DecodeContext ctx) {
            ByteBuffer rawMeta = ctx.metadata();
            EncodingProtos.DeltaMetadata meta;
            if (rawMeta == null || !rawMeta.hasRemaining()) {
                meta = EncodingProtos.DeltaMetadata.getDefaultInstance();
            } else {
                try {
                    meta = EncodingProtos.DeltaMetadata.parseFrom(rawMeta.duplicate());
                } catch (InvalidProtocolBufferException e) {
                    throw new VortexException(EncodingId.FASTLANES_DELTA, "invalid metadata", e);
                }
            }

            PType ptype = ((DType.Primitive) ctx.dtype()).ptype();
            long rowCount = ctx.rowCount();
            int typeBits = typeBits(ptype);
            int lanes = lanes(ptype);
            long mask = typeMask(ptype);

            long deltasLen = meta.getDeltasLen();
            int offset = (int) meta.getOffset();

            if (deltasLen == 0L) {
                MemorySegment empty = ctx.arena().allocate(0);
                return switch (ptype) {
                    case I64, U64 -> new LongArray(ctx.dtype(), 0L, empty, ArrayStats.empty());
                    case I32, U32 -> new IntArray(ctx.dtype(), 0L, empty, ArrayStats.empty());
                    case I16, U16 -> new ShortArray(ctx.dtype(), 0L, empty, ArrayStats.empty());
                    case I8, U8   -> new ByteArray(ctx.dtype(), 0L, empty, ArrayStats.empty());
                    default -> throw new VortexException(EncodingId.FASTLANES_DELTA, "unsupported ptype: " + ptype);
                };
            }

            long basesLen = (deltasLen / FL_CHUNK_SIZE) * lanes;
            DType dtype = ctx.dtype();

            Array basesArr = decodeChildAs(ctx, 0, dtype, basesLen);
            Array deltasArr = decodeChildAs(ctx, 1, dtype, deltasLen);

            long[] basesAll = readLongs(basesArr.buffer(0), (int) basesLen, ptype);
            long[] deltasAll = readLongs(deltasArr.buffer(0), (int) deltasLen, ptype);

            int numChunks = (int) (deltasLen / FL_CHUNK_SIZE);
            long[] decoded = new long[(int) deltasLen];
            long[] untransposedChunk = new long[FL_CHUNK_SIZE];
            long[] chunkBases = new long[lanes];
            long[] chunkDeltas = new long[FL_CHUNK_SIZE];
            long[] chunkUndelta = new long[FL_CHUNK_SIZE];

            for (int chunk = 0; chunk < numChunks; chunk++) {
                int basesOff = chunk * lanes;
                int deltaOff = chunk * FL_CHUNK_SIZE;

                System.arraycopy(basesAll, basesOff, chunkBases, 0, lanes);
                System.arraycopy(deltasAll, deltaOff, chunkDeltas, 0, FL_CHUNK_SIZE);

                undeltaChunk(chunkDeltas, chunkBases, lanes, typeBits, mask, chunkUndelta);

                for (int i = 0; i < FL_CHUNK_SIZE; i++) {
                    untransposedChunk[transposeIndex(i)] = chunkUndelta[i];
                }
                System.arraycopy(untransposedChunk, 0, decoded, deltaOff, FL_CHUNK_SIZE);
            }

            long[] result = new long[(int) rowCount];
            System.arraycopy(decoded, offset, result, 0, (int) rowCount);

            MemorySegment seg = fromLongs(result, ptype, ctx.arena());
            return switch (ptype) {
                case I64, U64 -> new LongArray(ctx.dtype(), rowCount, seg, ArrayStats.empty());
                case I32, U32 -> new IntArray(ctx.dtype(), rowCount, seg, ArrayStats.empty());
                case I16, U16 -> new ShortArray(ctx.dtype(), rowCount, seg, ArrayStats.empty());
                case I8, U8   -> new ByteArray(ctx.dtype(), rowCount, seg, ArrayStats.empty());
                default -> throw new VortexException(EncodingId.FASTLANES_DELTA, "unsupported ptype: " + ptype);
            };
        }

        private static void undeltaChunk(long[] deltas, long[] bases, int lanes, int typeBits, long mask, long[] out) {
            for (int lane = 0; lane < lanes; lane++) {
                long prev = bases[lane] & mask;
                for (int row = 0; row < typeBits; row++) {
                    int idx = iterateIndex(row, lane);
                    long next = ((deltas[idx] & mask) + prev) & mask;
                    out[idx] = next;
                    prev = next;
                }
            }
        }

        private static long[] readLongs(MemorySegment buf, int count, PType ptype) {
            long[] out = new long[count];
            int elemSize = ptype.byteSize();
            for (int i = 0; i < count; i++) {
                long off = (long) i * elemSize;
                out[i] = switch (ptype) {
                    case I8  -> buf.get(ValueLayout.JAVA_BYTE, off);
                    case U8  -> Byte.toUnsignedLong(buf.get(ValueLayout.JAVA_BYTE, off));
                    case I16 -> buf.get(PTypeIO.LE_SHORT, off);
                    case U16 -> Short.toUnsignedLong(buf.get(PTypeIO.LE_SHORT, off));
                    case I32 -> buf.get(PTypeIO.LE_INT, off);
                    case U32 -> Integer.toUnsignedLong(buf.get(PTypeIO.LE_INT, off));
                    case I64, U64 -> buf.get(PTypeIO.LE_LONG, off);
                    default -> throw new VortexException(EncodingId.FASTLANES_DELTA, "unsupported ptype: " + ptype);
                };
            }
            return out;
        }

        private static Array decodeChildAs(DecodeContext parent, int childIdx, DType dtype, long rowCount) {
            ArrayNode childNode = parent.node().children()[childIdx];
            DecodeContext childCtx = new DecodeContext(
                    childNode, dtype, rowCount, parent.segmentBuffers(), parent.registry(), parent.arena());
            return parent.registry().decode(childCtx);
        }
    }
}
