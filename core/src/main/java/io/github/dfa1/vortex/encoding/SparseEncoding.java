package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.array.BoolArray;
import io.github.dfa1.vortex.core.array.ByteArray;
import io.github.dfa1.vortex.core.array.DoubleArray;
import io.github.dfa1.vortex.core.array.FloatArray;
import io.github.dfa1.vortex.core.array.IntArray;
import io.github.dfa1.vortex.core.array.LongArray;
import io.github.dfa1.vortex.core.array.ShortArray;
import io.github.dfa1.vortex.core.array.VarBinArray;
import io.github.dfa1.vortex.proto.PatchesMetadata;
import io.github.dfa1.vortex.proto.ScalarValue;
import io.github.dfa1.vortex.proto.SparseMetadata;

import java.io.IOException;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/// Decoder for {@code vortex.sparse}.
///
/// <p>Metadata: protobuf {@code SparseMetadata} wrapping a {@code PatchesMetadata}
/// (patch count, index offset, index ptype).
/// Buffer 0: fill value as raw {@code ScalarValue} proto bytes.
/// Child slot 0: patch indices (unsigned int, dtype from PatchesMetadata.indices_ptype).
/// Child slot 1: patch values (same dtype as parent).
///
/// <p>Decode: allocate output filled with fill_value, then overwrite
/// {@code output[indices[i] - offset] = values[i]} for each patch.
public final class SparseEncoding implements Encoding {

    /// Creates a new {@code SparseEncoding} instance; use via {@link Registry}.
    public SparseEncoding() {
    }

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_SPARSE;
    }

    @Override
    public boolean accepts(DType dtype) {
        return dtype instanceof DType.Primitive;
    }

    @Override
    public EncodeResult encode(DType dtype, Object data, EncodeContext ctx) {
        return Encoder.encode(dtype, data, ctx);
    }

    @Override
    public Array decode(DecodeContext ctx) {
        return Decoder.decode(ctx);
    }

    private static final class Encoder {

        private static EncodeResult encode(DType dtype, Object data, EncodeContext ctx) {
            if (!(dtype instanceof DType.Primitive p)) {
                throw new VortexException(EncodingId.VORTEX_SPARSE,
                        "encode only supports Primitive dtype, got " + dtype);
            }
            PType ptype = p.ptype();
            int n = arrayLength(data, ptype);

            List<Integer> patchIdx = new ArrayList<>();
            List<Long> patchBits = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                long bits = readBits(data, ptype, i);
                if (bits != 0L) {
                    patchIdx.add(i);
                    patchBits.add(bits);
                }
            }

            int numPatches = patchIdx.size();
            PType idxPtype = chooseIdxPtype(n);

            ScalarValue fillScalar = zeroScalar(ptype);
            byte[] fillBytes = fillScalar.encode();
            MemorySegment fillBuf = ctx.arena().allocate(fillBytes.length);
            MemorySegment.copy(MemorySegment.ofArray(fillBytes), 0, fillBuf, 0, fillBytes.length);

            MemorySegment idxBuf = buildIdxBuf(patchIdx, idxPtype, numPatches, ctx);
            MemorySegment valBuf = buildValBuf(patchBits, ptype, numPatches, ctx);

            PatchesMetadata patchesMeta = new PatchesMetadata(
                    numPatches,
                    0L,
                    io.github.dfa1.vortex.proto.PType.fromValue(idxPtype.ordinal()),
                    null,
                    null,
                    null
            );
            byte[] metaBytes = new SparseMetadata(patchesMeta).encode();

            EncodeNode idxNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, 1);
            EncodeNode valNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, 2);
            EncodeNode root = new EncodeNode(EncodingId.VORTEX_SPARSE, ByteBuffer.wrap(metaBytes),
                    new EncodeNode[]{idxNode, valNode}, new int[]{0});
            return new EncodeResult(root, List.of(fillBuf, idxBuf, valBuf), null, null);
        }

        private static int arrayLength(Object data, PType ptype) {
            return switch (ptype) {
                case I8, U8 -> ((byte[]) data).length;
                case I16, U16 -> ((short[]) data).length;
                case I32, U32 -> ((int[]) data).length;
                case I64, U64 -> ((long[]) data).length;
                case F32 -> ((float[]) data).length;
                case F64 -> ((double[]) data).length;
                default -> throw new VortexException(EncodingId.VORTEX_SPARSE, "unsupported ptype: " + ptype);
            };
        }

        private static long readBits(Object data, PType ptype, int i) {
            return switch (ptype) {
                case I8 -> ((byte[]) data)[i];
                case U8 -> Byte.toUnsignedLong(((byte[]) data)[i]);
                case I16 -> ((short[]) data)[i];
                case U16 -> Short.toUnsignedLong(((short[]) data)[i]);
                case I32 -> ((int[]) data)[i];
                case U32 -> Integer.toUnsignedLong(((int[]) data)[i]);
                case I64, U64 -> ((long[]) data)[i];
                case F32 -> Float.floatToRawIntBits(((float[]) data)[i]);
                case F64 -> Double.doubleToRawLongBits(((double[]) data)[i]);
                default -> throw new VortexException(EncodingId.VORTEX_SPARSE, "unsupported ptype: " + ptype);
            };
        }

        private static PType chooseIdxPtype(int n) {
            if (n <= 0xFF) {
                return PType.U8;
            } else if (n <= 0xFFFF) {
                return PType.U16;
            } else {
                return PType.U32;
            }
        }

        private static ScalarValue zeroScalar(PType ptype) {
            return switch (ptype) {
                case I8, I16, I32, I64 -> ScalarValue.ofInt64Value(0L);
                case U8, U16, U32, U64 -> ScalarValue.ofUint64Value(0L);
                case F32 -> ScalarValue.ofF32Value(0.0f);
                case F64 -> ScalarValue.ofF64Value(0.0);
                default -> throw new VortexException(EncodingId.VORTEX_SPARSE, "unsupported ptype: " + ptype);
            };
        }

        private static MemorySegment buildIdxBuf(List<Integer> patchIdx, PType idxPtype, int numPatches, EncodeContext ctx) {
            int elemBytes = idxPtype.byteSize();
            MemorySegment seg = ctx.arena().allocate(Math.max(1L, (long) numPatches * elemBytes), elemBytes);
            for (int i = 0; i < numPatches; i++) {
                PTypeIO.set(seg, (long) i * elemBytes, idxPtype, patchIdx.get(i));
            }
            return seg;
        }

        private static MemorySegment buildValBuf(List<Long> patchBits, PType ptype, int numPatches, EncodeContext ctx) {
            int elemBytes = ptype.byteSize();
            MemorySegment seg = ctx.arena().allocate(Math.max(1L, (long) numPatches * elemBytes), elemBytes);
            for (int i = 0; i < numPatches; i++) {
                PTypeIO.set(seg, (long) i * elemBytes, ptype, patchBits.get(i));
            }
            return seg;
        }
    }

    private static final class Decoder {

        private static Array decode(DecodeContext ctx) {
            ByteBuffer rawMeta = ctx.metadata();
            if (rawMeta == null || !rawMeta.hasRemaining()) {
                throw new VortexException(EncodingId.VORTEX_SPARSE, "missing metadata");
            }
            SparseMetadata sparseMeta;
            try {
                MemorySegment metaSeg = MemorySegment.ofBuffer(rawMeta.duplicate());
                sparseMeta = SparseMetadata.decode(metaSeg, 0, metaSeg.byteSize());
            } catch (IOException e) {
                throw new VortexException(EncodingId.VORTEX_SPARSE, "invalid metadata", e);
            }

            PatchesMetadata patches = sparseMeta.patches();
            long numPatches = patches.len();
            long offset = patches.offset();
            PType indicesPtype = ptypeFromProto(patches.indices_ptype());

            long n = ctx.rowCount();

            if (ctx.dtype() instanceof DType.Utf8 || ctx.dtype() instanceof DType.Binary) {
                return decodeVarBin(ctx, n, numPatches, offset, indicesPtype);
            }

            if (ctx.dtype() instanceof DType.Bool) {
                return decodeBool(ctx, n, numPatches, offset, indicesPtype);
            }

            if (!(ctx.dtype() instanceof DType.Primitive)) {
                throw new VortexException(EncodingId.VORTEX_SPARSE, "expected primitive dtype, got " + ctx.dtype());
            }
            PType valuePtype = ((DType.Primitive) ctx.dtype()).ptype();

            MemorySegment fillBuf = ctx.buffer(0).unwrapForSubParser("sparse encoding");
            ScalarValue fillScalar;
            try {
                fillScalar = ScalarValue.decode(fillBuf, 0, fillBuf.byteSize());
            } catch (IOException e) {
                throw new VortexException(EncodingId.VORTEX_SPARSE, "invalid fill value", e);
            }

            int elemBytes = valuePtype.byteSize();
            MemorySegment out = ctx.arena().allocate(n * elemBytes);
            fillSegment(out, n, valuePtype, fillScalar);

            if (numPatches > 0) {
                DType indicesDtype = new DType.Primitive(indicesPtype, false);
                applyPatches(out, n, valuePtype,
                        ctx.decodeChildSegment(0, indicesDtype, numPatches),
                        ctx.decodeChildSegment(1, ctx.dtype(), numPatches),
                        indicesPtype, numPatches, offset);
            }

            return switch (valuePtype) {
                case I64, U64 -> new LongArray(ctx.dtype(), n, out);
                case I32, U32 -> new IntArray(ctx.dtype(), n, out);
                case F64 -> new DoubleArray(ctx.dtype(), n, out);
                case F32 -> new FloatArray(ctx.dtype(), n, out);
                case I16, U16 -> new ShortArray(ctx.dtype(), n, out);
                case I8, U8 -> new ByteArray(ctx.dtype(), n, out);
                default -> throw new VortexException(EncodingId.VORTEX_SPARSE, "unsupported ptype " + valuePtype);
            };
        }

        private static Array decodeBool(
                DecodeContext ctx, long n, long numPatches, long offset, PType indicesPtype
        ) {
            long numBytes = (n + 7) >>> 3;
            MemorySegment out = ctx.arena().allocate(numBytes);
            if (numPatches > 0) {
                DType indicesDtype = new DType.Primitive(indicesPtype, false);
                MemorySegment idxSeg = ctx.decodeChildSegment(0, indicesDtype, numPatches);
                BoolArray bools = (BoolArray) ctx.decodeChild(1, ctx.dtype(), numPatches);
                int idxBytes = indicesPtype.byteSize();
                for (long i = 0; i < numPatches; i++) {
                    if (bools.getBoolean(i)) {
                        long pos = readUnsignedIdx(idxSeg, SegmentBroadcast.elementOffset(idxSeg, i, idxBytes), indicesPtype) - offset;
                        long byteIdx = pos >>> 3;
                        byte cur = out.get(ValueLayout.JAVA_BYTE, byteIdx);
                        out.set(ValueLayout.JAVA_BYTE, byteIdx, (byte) (cur | (1 << (pos & 7))));
                    }
                }
            }
            return new BoolArray(ctx.dtype(), n, out);
        }

        private static Array decodeVarBin(
                DecodeContext ctx, long n, long numPatches, long offset, PType indicesPtype
        ) {
            MemorySegment outOffsets = ctx.arena().allocate((n + 1) * 4L, 4);
            if (numPatches == 0) {
                MemorySegment outBytes = ctx.arena().allocate(1);
                return new VarBinArray(ctx.dtype(), n, outBytes, outOffsets, PType.I32);
            }

            DType indicesDtype = new DType.Primitive(indicesPtype, false);
            MemorySegment idxSeg = ctx.decodeChildSegment(0, indicesDtype, numPatches);
            VarBinArray varBin = (VarBinArray) ctx.decodeChild(1, ctx.dtype(), numPatches);
            MemorySegment valBytes = varBin.bytesSegment();
            MemorySegment valOffsets = varBin.offsetsSegment();
            PType valOffPtype = varBin.offsetsPtype();

            int idxBytes = indicesPtype.byteSize();
            long totalBytes = 0;
            for (long i = 0; i < numPatches; i++) {
                totalBytes += readVarBinOffset(valOffsets, i + 1, valOffPtype)
                                      - readVarBinOffset(valOffsets, i, valOffPtype);
            }

            MemorySegment outBytes = ctx.arena().allocate(Math.max(1, totalBytes));
            long patchCursor = 0;
            long bytePos = 0;
            for (long pos = 0; pos < n; pos++) {
                if (patchCursor < numPatches) {
                    long patchPos = readUnsignedIdx(idxSeg, SegmentBroadcast.elementOffset(idxSeg, patchCursor, idxBytes), indicesPtype) - offset;
                    if (patchPos == pos) {
                        long strStart = readVarBinOffset(valOffsets, patchCursor, valOffPtype);
                        long strEnd = readVarBinOffset(valOffsets, patchCursor + 1, valOffPtype);
                        long strLen = strEnd - strStart;
                        if (strLen > 0) {
                            MemorySegment.copy(valBytes, strStart, outBytes, bytePos, strLen);
                            bytePos += strLen;
                        }
                        patchCursor++;
                    }
                }
                outOffsets.setAtIndex(PTypeIO.LE_INT, pos + 1, (int) bytePos);
            }

            return new VarBinArray(ctx.dtype(), n, outBytes, outOffsets, PType.I32);
        }

        private static long readVarBinOffset(MemorySegment seg, long i, PType ptype) {
            return switch (ptype) {
                case I32, U32 -> Integer.toUnsignedLong(seg.getAtIndex(PTypeIO.LE_INT, i));
                case I64, U64 -> seg.getAtIndex(PTypeIO.LE_LONG, i);
                default -> throw new VortexException(EncodingId.VORTEX_SPARSE, "unsupported offset ptype " + ptype);
            };
        }

        private static void fillSegment(MemorySegment out, long n, PType ptype, ScalarValue scalar) {
            long fillLong = scalarToLong(scalar);
            ByteBuffer bb = out.asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
            for (long i = 0; i < n; i++) {
                writeElem(bb, ptype, fillLong);
            }
        }

        private static void applyPatches(
                MemorySegment out, long n, PType valuePtype,
                MemorySegment idxSeg, MemorySegment valSeg,
                PType idxPtype, long numPatches, long offset
        ) {
            int elemBytes = valuePtype.byteSize();
            int idxBytes = idxPtype.byteSize();
            ByteBuffer outBuf = out.asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
            for (long i = 0; i < numPatches; i++) {
                long idx = readUnsignedIdx(idxSeg, SegmentBroadcast.elementOffset(idxSeg, i, idxBytes), idxPtype) - offset;
                if (idx < 0 || idx >= n) {
                    throw new VortexException(EncodingId.VORTEX_SPARSE,
                            "patch index " + idx + " out of range [0," + n + ")");
                }
                long val = readElem(valSeg, SegmentBroadcast.elementOffset(valSeg, i, elemBytes), valuePtype);
                outBuf.position((int) (idx * elemBytes));
                writeElem(outBuf, valuePtype, val);
            }
        }

        private static long readUnsignedIdx(MemorySegment seg, long off, PType ptype) {
            return switch (ptype) {
                case U8 -> Byte.toUnsignedLong(seg.get(ValueLayout.JAVA_BYTE, off));
                case U16 -> Short.toUnsignedLong(seg.get(PTypeIO.LE_SHORT, off));
                case U32 -> Integer.toUnsignedLong(seg.get(PTypeIO.LE_INT, off));
                case U64 -> seg.get(PTypeIO.LE_LONG, off);
                default -> throw new VortexException(EncodingId.VORTEX_SPARSE, "non-unsigned index ptype " + ptype);
            };
        }

        private static long readElem(MemorySegment seg, long off, PType ptype) {
            return switch (ptype) {
                case I8, U8 -> Byte.toUnsignedLong(seg.get(ValueLayout.JAVA_BYTE, off));
                case I16, U16 -> Short.toUnsignedLong(seg.get(PTypeIO.LE_SHORT, off));
                case I32, U32 -> Integer.toUnsignedLong(seg.get(PTypeIO.LE_INT, off));
                case I64, U64, F32, F64 -> seg.get(PTypeIO.LE_LONG, off);
                default -> throw new UnsupportedOperationException("vortex.sparse: unsupported ptype " + ptype);
            };
        }

        private static void writeElem(ByteBuffer bb, PType ptype, long bits) {
            switch (ptype) {
                case I8, U8 -> bb.put((byte) bits);
                case I16, U16 -> bb.putShort((short) bits);
                case I32, U32 -> bb.putInt((int) bits);
                case I64, U64, F32, F64 -> bb.putLong(bits);
                default -> throw new UnsupportedOperationException("vortex.sparse: unsupported ptype " + ptype);
            }
        }

        private static long scalarToLong(ScalarValue scalar) {
            if (scalar.int64_value() != null) {
                return scalar.int64_value();
            }
            if (scalar.uint64_value() != null) {
                return scalar.uint64_value();
            }
            if (scalar.f32_value() != null) {
                return Float.floatToRawIntBits(scalar.f32_value());
            }
            if (scalar.f64_value() != null) {
                return Double.doubleToRawLongBits(scalar.f64_value());
            }
            return 0L;
        }

        // PType proto enum ordinals match Java PType ordinals (U8=0..F64=10)
        private static PType ptypeFromProto(io.github.dfa1.vortex.proto.PType proto) {
            return PType.fromOrdinal(proto.value());
        }
    }
}
