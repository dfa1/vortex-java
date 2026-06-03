package io.github.dfa1.vortex.encoding;

import com.google.protobuf.InvalidProtocolBufferException;
import io.github.dfa1.vortex.core.ArrayStats;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.array.BoolArray;
import io.github.dfa1.vortex.core.array.ByteArray;
import io.github.dfa1.vortex.core.array.DoubleArray;
import io.github.dfa1.vortex.core.array.Float16Array;
import io.github.dfa1.vortex.core.array.FloatArray;
import io.github.dfa1.vortex.core.array.IntArray;
import io.github.dfa1.vortex.core.array.LongArray;
import io.github.dfa1.vortex.core.array.MaskedArray;
import io.github.dfa1.vortex.core.array.ShortArray;
import io.github.dfa1.vortex.proto.DTypeProtos;
import io.github.dfa1.vortex.proto.EncodingProtos;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.util.List;

/// Encoding for {@code fastlanes.rle} — FastLanes chunk-based run-length encoding.
///
/// <p>Wire format (0 buffers, 3 child slots):
/// <ul>
///   <li>child 0: {@code values} — non-nullable primitive, same ptype as output; global dictionary
///       across all chunks (concatenation of per-chunk dictionaries).</li>
///   <li>child 1: {@code indices} — u8 or u16, same nullability as output; chunk-local dictionary
///       indices, padded to a multiple of 1024.</li>
///   <li>child 2: {@code values_idx_offsets} — non-nullable unsigned int (typically u64); one
///       entry per chunk recording the absolute start position in the global {@code values}
///       array.</li>
/// </ul>
///
/// <p>Metadata: protobuf {@code RLEMetadata}:
/// {@code values_len u64} (tag 1), {@code indices_len u64} (tag 2),
/// {@code indices_ptype PType} (tag 3), {@code values_idx_offsets_len u64} (tag 4),
/// {@code values_idx_offsets_ptype PType} (tag 5), {@code offset u64} (tag 6, always &lt; 1024).
///
/// <p>Encode: split input into 1024-element chunks; within each chunk build a run-length
/// dictionary (new entry per run boundary, NOT per unique value) and a per-element index array.
///
/// <p>Decode: for each needed chunk, look up {@code values[values_idx_offsets[chunk] + indices[i]]}
/// to reconstruct each element, then slice the padded result to {@code [offset, offset+len)}.
public final class RleEncoding implements Encoding {

    static final int FL_CHUNK_SIZE = 1024;

    @Override
    public EncodingId encodingId() {
        return EncodingId.FASTLANES_RLE;
    }

    @Override
    public boolean accepts(DType dtype) {
        return dtype instanceof DType.Primitive;
    }

    @Override
    public EncodeResult encode(DType dtype, Object data) {
        return Encoder.encode(dtype, data);
    }

    @Override
    public Array decode(DecodeContext ctx) {
        return Decoder.decode(ctx);
    }

    private static final class Encoder {

        static EncodeResult encode(DType dtype, Object data) {
            if (!(dtype instanceof DType.Primitive p)) {
                throw new VortexException(EncodingId.FASTLANES_RLE, "encode only supports Primitive dtype, got " + dtype);
            }
            PType ptype = p.ptype();
            long[] longs = toLongs(data, ptype);
            int n = longs.length;

            if (n == 0) {
                return encodeEmpty(ptype, dtype);
            }

            int numChunks = (n + FL_CHUNK_SIZE - 1) / FL_CHUNK_SIZE;
            int paddedLen = numChunks * FL_CHUNK_SIZE;

            long[] globalValues = new long[paddedLen];
            short[] globalIndices = new short[paddedLen];
            long[] valuesIdxOffsets = new long[numChunks];

            long[] chunkInput = new long[FL_CHUNK_SIZE];
            long[] chunkValues = new long[FL_CHUNK_SIZE];
            short[] chunkIndices = new short[FL_CHUNK_SIZE];

            int globalValuesCount = 0;

            for (int chunk = 0; chunk < numChunks; chunk++) {
                int chunkStart = chunk * FL_CHUNK_SIZE;
                int chunkEnd = Math.min(chunkStart + FL_CHUNK_SIZE, n);
                int chunkLen = chunkEnd - chunkStart;

                System.arraycopy(longs, chunkStart, chunkInput, 0, chunkLen);
                // Pad remainder with last value to avoid spurious run boundaries.
                long lastVal = longs[chunkEnd - 1];
                for (int i = chunkLen; i < FL_CHUNK_SIZE; i++) {
                    chunkInput[i] = lastVal;
                }

                int numChunkValues = rleEncode(chunkInput, chunkValues, chunkIndices);

                valuesIdxOffsets[chunk] = globalValuesCount;
                System.arraycopy(chunkValues, 0, globalValues, globalValuesCount, numChunkValues);
                globalValuesCount += numChunkValues;

                System.arraycopy(chunkIndices, 0, globalIndices, chunkStart, FL_CHUNK_SIZE);
            }

            MemorySegment valuesSeg = fromLongs(globalValues, 0, globalValuesCount, ptype, Arena.ofAuto());
            MemorySegment indicesSeg = toIndicesSeg(globalIndices, paddedLen, Arena.ofAuto());
            MemorySegment offsetsSeg = fromLongsU64(valuesIdxOffsets, numChunks, Arena.ofAuto());

            PType indicesPtype = PType.U16;
            PType offsetsPtype = PType.U64;

            byte[] metaBytes = EncodingProtos.RLEMetadata.newBuilder()
                    .setValuesLen(globalValuesCount)
                    .setIndicesLen(paddedLen)
                    .setIndicesPtype(DTypeProtos.PType.forNumber(indicesPtype.ordinal()))
                    .setValuesIdxOffsetsLen(numChunks)
                    .setValuesIdxOffsetsPtype(DTypeProtos.PType.forNumber(offsetsPtype.ordinal()))
                    .setOffset(0)
                    .build()
                    .toByteArray();

            EncodeNode valuesNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, 0);
            EncodeNode indicesNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, 1);
            EncodeNode offsetsNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, 2);
            EncodeNode root = new EncodeNode(
                    EncodingId.FASTLANES_RLE,
                    ByteBuffer.wrap(metaBytes),
                    new EncodeNode[]{valuesNode, indicesNode, offsetsNode},
                    new int[0]);
            return new EncodeResult(root, List.of(valuesSeg, indicesSeg, offsetsSeg), null, null);
        }

        // FastLanes RLE encode for one 1024-element chunk.
        // Returns the number of unique run entries written to chunkValues.
        private static int rleEncode(long[] input, long[] chunkValues, short[] chunkIndices) {
            short posVal = 0;
            int valIdx = 1;
            long prev = input[0];
            chunkValues[0] = prev;
            chunkIndices[0] = 0;

            for (int i = 1; i < FL_CHUNK_SIZE; i++) {
                long cur = input[i];
                if (cur != prev) {
                    chunkValues[valIdx] = cur;
                    valIdx++;
                    posVal++;
                    prev = cur;
                }
                chunkIndices[i] = posVal;
            }
            return valIdx;
        }

        private static EncodeResult encodeEmpty(PType ptype, DType dtype) {
            MemorySegment empty = Arena.ofAuto().allocate(0);
            PType indicesPtype = PType.U16;
            PType offsetsPtype = PType.U64;
            byte[] metaBytes = EncodingProtos.RLEMetadata.newBuilder()
                    .setValuesLen(0)
                    .setIndicesLen(0)
                    .setIndicesPtype(DTypeProtos.PType.forNumber(indicesPtype.ordinal()))
                    .setValuesIdxOffsetsLen(0)
                    .setValuesIdxOffsetsPtype(DTypeProtos.PType.forNumber(offsetsPtype.ordinal()))
                    .setOffset(0)
                    .build()
                    .toByteArray();
            EncodeNode valuesNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, 0);
            EncodeNode indicesNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, 1);
            EncodeNode offsetsNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, 2);
            EncodeNode root = new EncodeNode(
                    EncodingId.FASTLANES_RLE,
                    ByteBuffer.wrap(metaBytes),
                    new EncodeNode[]{valuesNode, indicesNode, offsetsNode},
                    new int[0]);
            return new EncodeResult(root, List.of(empty, empty, empty), null, null);
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
                case F32 -> {
                    float[] arr = (float[]) data;
                    long[] r = new long[arr.length];
                    for (int i = 0; i < arr.length; i++) {
                        r[i] = Float.floatToRawIntBits(arr[i]);
                    }
                    yield r;
                }
                case F64 -> {
                    double[] arr = (double[]) data;
                    long[] r = new long[arr.length];
                    for (int i = 0; i < arr.length; i++) {
                        r[i] = Double.doubleToRawLongBits(arr[i]);
                    }
                    yield r;
                }
                case F16 -> {
                    short[] arr = (short[]) data;
                    long[] r = new long[arr.length];
                    for (int i = 0; i < arr.length; i++) {
                        r[i] = Short.toUnsignedLong(arr[i]);
                    }
                    yield r;
                }
            };
        }

        private static MemorySegment fromLongs(long[] values, int start, int count, PType ptype, SegmentAllocator arena) {
            int elemSize = ptype.byteSize();
            MemorySegment seg = arena.allocate((long) count * elemSize);
            for (int i = 0; i < count; i++) {
                PTypeIO.set(seg, (long) i * elemSize, ptype, values[start + i]);
            }
            return seg;
        }

        private static MemorySegment fromLongsU64(long[] values, int count, SegmentAllocator arena) {
            MemorySegment seg = arena.allocate((long) count * 8);
            for (int i = 0; i < count; i++) {
                seg.setAtIndex(PTypeIO.LE_LONG, i, values[i]);
            }
            return seg;
        }

        private static MemorySegment toIndicesSeg(short[] indices, int count, SegmentAllocator arena) {
            MemorySegment seg = arena.allocate((long) count * 2);
            for (int i = 0; i < count; i++) {
                seg.setAtIndex(PTypeIO.LE_SHORT, i, indices[i]);
            }
            return seg;
        }
    }

    private static final class Decoder {

        static Array decode(DecodeContext ctx) {
            ByteBuffer rawMeta = ctx.metadata();
            EncodingProtos.RLEMetadata meta;
            try {
                meta = EncodingProtos.RLEMetadata.parseFrom(rawMeta.duplicate());
            } catch (InvalidProtocolBufferException e) {
                throw new VortexException(EncodingId.FASTLANES_RLE, "invalid metadata", e);
            }

            long valuesLen = meta.getValuesLen();
            long indicesLen = meta.getIndicesLen();
            PType indicesPtype = ptypeFromProto(meta.getIndicesPtype());
            long offsetsLen = meta.getValuesIdxOffsetsLen();
            PType offsetsPtype = ptypeFromProto(meta.getValuesIdxOffsetsPtype());
            int offset = (int) meta.getOffset();

            long rowCount = ctx.rowCount();
            if (rowCount == 0 || indicesLen == 0) {
                return emptyArray(ctx);
            }

            if (!(ctx.dtype() instanceof DType.Primitive p)) {
                throw new VortexException(EncodingId.FASTLANES_RLE, "expected Primitive dtype, got " + ctx.dtype());
            }
            PType ptype = p.ptype();

            DType valuesDtype = new DType.Primitive(ptype, false);
            DType indicesDtype = new DType.Primitive(indicesPtype, false);
            DType offsetsDtype = new DType.Primitive(offsetsPtype, false);

            Array valuesArr = decodeChildAs(ctx, 0, valuesDtype, valuesLen);
            Array indicesRaw = decodeChildAs(ctx, 1, indicesDtype, indicesLen);
            Array offsetsArr = decodeChildAs(ctx, 2, offsetsDtype, offsetsLen);

            // Indices may carry a validity bitmap when the output column is nullable.
            BoolArray indicesValidity = null;
            Array indicesArr = indicesRaw;
            if (indicesRaw instanceof MaskedArray masked) {
                indicesArr = masked.child(0);
                indicesValidity = (BoolArray) masked.child(1);
            }

            long[] values = readLongs(valuesArr.buffer(0), (int) valuesLen, ptype);
            int[] indices = readIndices(indicesArr.buffer(0), (int) indicesLen, indicesPtype);
            long[] valuesIdxOffsets = readUnsignedLongs(offsetsArr.buffer(0), (int) offsetsLen, offsetsPtype);

            // offset < FL_CHUNK_SIZE so chunk_start = 0 always.
            int numChunks = (int) (indicesLen / FL_CHUNK_SIZE);
            int chunkEnd = (int) ((offset + rowCount + FL_CHUNK_SIZE - 1) / FL_CHUNK_SIZE);
            chunkEnd = Math.min(chunkEnd, numChunks);

            long[] decoded = new long[chunkEnd * FL_CHUNK_SIZE];
            long firstOffset = valuesLen > 0 ? valuesIdxOffsets[0] : 0L;

            for (int chunkIdx = 0; chunkIdx < chunkEnd; chunkIdx++) {
                long valueIdxOffset = valuesIdxOffsets[chunkIdx] - firstOffset;
                long nextValueIdxOffset = (chunkIdx + 1 < numChunks)
                        ? (valuesIdxOffsets[chunkIdx + 1] - firstOffset)
                        : valuesLen;
                int numChunkValues = (int) (nextValueIdxOffset - valueIdxOffset);

                int chunkBase = chunkIdx * FL_CHUNK_SIZE;
                if (numChunkValues <= 1) {
                    long fillVal = numChunkValues == 1 ? values[(int) valueIdxOffset] : 0L;
                    for (int i = 0; i < FL_CHUNK_SIZE; i++) {
                        decoded[chunkBase + i] = fillVal;
                    }
                } else {
                    for (int i = 0; i < FL_CHUNK_SIZE; i++) {
                        int idx = indices[chunkBase + i];
                        // Clamp index to handle garbage values at null positions.
                        if (idx >= numChunkValues) {
                            idx = numChunkValues - 1;
                        }
                        decoded[chunkBase + i] = values[(int) valueIdxOffset + idx];
                    }
                }
            }

            MemorySegment seg = fromLongs(decoded, offset, (int) rowCount, ptype, ctx.arena());
            Array result = toArray(ctx.dtype(), rowCount, seg, ptype);
            if (indicesValidity == null) {
                return result;
            }
            // Propagate indices validity to output: output[j] is null iff indices[offset+j] is null.
            int validityBytes = (int) ((rowCount + 7) / 8);
            MemorySegment validityBuf = ctx.arena().allocate(validityBytes);
            for (long j = 0; j < rowCount; j++) {
                if (indicesValidity.getBoolean(offset + j)) {
                    int byteIdx = (int) (j >>> 3);
                    byte current = validityBuf.get(ValueLayout.JAVA_BYTE, (long) byteIdx);
                    validityBuf.set(ValueLayout.JAVA_BYTE, (long) byteIdx, (byte) (current | (1 << (j & 7))));
                }
            }
            BoolArray outputValidity = new BoolArray(new DType.Bool(false), rowCount, validityBuf, ArrayStats.empty());
            return new MaskedArray(result, outputValidity);
        }

        private static Array emptyArray(DecodeContext ctx) {
            MemorySegment empty = ctx.arena().allocate(0);
            DType dt = ctx.dtype();
            PType ptype = ((DType.Primitive) dt).ptype();
            return toArray(dt, 0L, empty, ptype);
        }

        private static Array toArray(DType dtype, long n, MemorySegment seg, PType ptype) {
            return switch (ptype) {
                case I64, U64 -> new LongArray(dtype, n, seg, ArrayStats.empty());
                case I32, U32 -> new IntArray(dtype, n, seg, ArrayStats.empty());
                case I16, U16 -> new ShortArray(dtype, n, seg, ArrayStats.empty());
                case I8, U8   -> new ByteArray(dtype, n, seg, ArrayStats.empty());
                case F64 -> new DoubleArray(dtype, n, seg, ArrayStats.empty());
                case F32 -> new FloatArray(dtype, n, seg, ArrayStats.empty());
                case F16 -> new Float16Array(dtype, n, seg, ArrayStats.empty());
            };
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
                    case U16, F16 -> Short.toUnsignedLong(buf.get(PTypeIO.LE_SHORT, off));
                    case I32 -> buf.get(PTypeIO.LE_INT, off);
                    case U32 -> Integer.toUnsignedLong(buf.get(PTypeIO.LE_INT, off));
                    case I64, U64 -> buf.get(PTypeIO.LE_LONG, off);
                    case F32 -> Integer.toUnsignedLong(buf.get(PTypeIO.LE_INT, off));
                    case F64 -> buf.get(PTypeIO.LE_LONG, off);
                };
            }
            return out;
        }

        private static int[] readIndices(MemorySegment buf, int count, PType indicesPtype) {
            int[] out = new int[count];
            switch (indicesPtype) {
                case U8 -> {
                    for (int i = 0; i < count; i++) {
                        out[i] = Byte.toUnsignedInt(buf.get(ValueLayout.JAVA_BYTE, i));
                    }
                }
                case U16 -> {
                    for (int i = 0; i < count; i++) {
                        out[i] = Short.toUnsignedInt(buf.get(PTypeIO.LE_SHORT, (long) i * 2));
                    }
                }
                default -> throw new VortexException(EncodingId.FASTLANES_RLE, "unsupported indices ptype: " + indicesPtype);
            }
            return out;
        }

        private static long[] readUnsignedLongs(MemorySegment buf, int count, PType ptype) {
            long[] out = new long[count];
            int elemSize = ptype.byteSize();
            for (int i = 0; i < count; i++) {
                long off = (long) i * elemSize;
                out[i] = switch (ptype) {
                    case U8  -> Byte.toUnsignedLong(buf.get(ValueLayout.JAVA_BYTE, off));
                    case U16 -> Short.toUnsignedLong(buf.get(PTypeIO.LE_SHORT, off));
                    case U32 -> Integer.toUnsignedLong(buf.get(PTypeIO.LE_INT, off));
                    case U64 -> buf.get(PTypeIO.LE_LONG, off);
                    default -> throw new VortexException(EncodingId.FASTLANES_RLE, "unsupported offsets ptype: " + ptype);
                };
            }
            return out;
        }

        private static MemorySegment fromLongs(long[] decoded, int offset, int count, PType ptype, SegmentAllocator arena) {
            int elemSize = ptype.byteSize();
            MemorySegment seg = arena.allocate((long) count * elemSize);
            for (int i = 0; i < count; i++) {
                PTypeIO.set(seg, (long) i * elemSize, ptype, decoded[offset + i]);
            }
            return seg;
        }

        private static Array decodeChildAs(DecodeContext parent, int childIdx, DType dtype, long rowCount) {
            ArrayNode childNode = parent.node().children()[childIdx];
            DecodeContext childCtx = new DecodeContext(
                    childNode, dtype, rowCount, parent.segmentBuffers(), parent.registry(), parent.arena());
            return parent.registry().decode(childCtx);
        }

        private static PType ptypeFromProto(DTypeProtos.PType proto) {
            return PType.values()[proto.getNumber()];
        }
    }
}
