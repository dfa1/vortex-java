package io.github.dfa1.vortex.encoding;

import com.google.protobuf.InvalidProtocolBufferException;
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
import io.github.dfa1.vortex.core.array.VarBinArray;
import io.github.dfa1.vortex.proto.EncodingProtos;
import io.github.dfa1.vortex.proto.ScalarProtos;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;

/// Encoding for `vortex.dict` — dictionary encoding for low-cardinality columns.
///
/// Primitive: `values_buffer(unique_values)` + `codes_buffer`. Metadata (1 byte): code PType ordinal.
/// Node tree: DictNode{ children=[ValuesNode{buf=0},CodesNode{buf=1}] }.
///
/// Utf8: buf 0 = dict bytes, buf 1 = dict offsets (I64 LE, n_unique+1), buf 2 = codes.
/// Metadata (2 bytes): codePType ordinal, I64 ordinal. Flat node (no children), bufferIndices=[0,1,2].
/// Decoded as a {@link io.github.dfa1.vortex.core.array.VarBinArray} in dict mode — no string materialization at decode time.
public final class DictEncoding implements Encoding {

    /// Creates a new {@code DictEncoding} instance.
    public DictEncoding() {
    }

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_DICT;
    }

    @Override
    public boolean accepts(DType dtype) {
        return dtype instanceof DType.Primitive || dtype instanceof DType.Utf8;
    }

    @Override
    public EncodeResult encode(DType dtype, Object data, EncodeContext ctx) {
        return Encoder.encode(dtype, data, ctx);
    }

    @Override
    public CascadeStep encodeCascade(DType dtype, Object data, EncodeContext encodeCtx) {
        return Encoder.encodeCascade(dtype, data, encodeCtx);
    }

    @Override
    public Array decode(DecodeContext ctx) {
        return Decoder.decode(ctx);
    }

    private static final class Encoder {

        private static EncodeResult encode(DType dtype, Object data, EncodeContext ctx) {
            if (dtype instanceof DType.Utf8) {
                return encodeUtf8((String[]) data, ctx);
            }
            DictData d = buildDictData(dtype, data, ctx);
            PType codePType = d.codePType();
            int codeBytes = codePType.byteSize();

            MemorySegment codesBuf = ctx.arena().allocate((long) d.len() * codeBytes);
            for (int i = 0; i < d.len(); i++) {
                writeCodeToSeg(codesBuf, codePType, i, readCodeFromArr(d.codesArr(), codePType, i));
            }

            ByteBuffer meta = ByteBuffer.allocate(1).put(0, (byte) codePType.ordinal());
            EncodeNode valuesNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, 0);
            EncodeNode codesNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, 1);
            EncodeNode rootNode = new EncodeNode(
                    EncodingId.VORTEX_DICT, meta,
                    new EncodeNode[]{valuesNode, codesNode},
                    new int[0]);

            return new EncodeResult(rootNode, List.of(d.valuesBuf(), codesBuf), null, null);
        }

        private static CascadeStep encodeCascade(DType dtype, Object data, EncodeContext ctx) {
            if (dtype instanceof DType.Utf8) {
                return CascadeStep.terminal(encodeUtf8((String[]) data, ctx));
            }
            DictData d = buildDictData(dtype, data, ctx);
            PType codePType = d.codePType();

            ByteBuffer meta = ByteBuffer.allocate(1).put(0, (byte) codePType.ordinal());
            EncodeNode valuesNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, 0);
            EncodeNode partialRoot = new EncodeNode(
                    EncodingId.VORTEX_DICT, meta,
                    new EncodeNode[]{valuesNode, null},
                    new int[0]);

            DType codesDtype = new DType.Primitive(codePType, false);
            ChildSlot slot = new ChildSlot(codesDtype, d.codesArr(), 1);
            return new CascadeStep(partialRoot, List.of(d.valuesBuf()), List.of(slot), null, null, true);
        }

        private static EncodeResult encodeUtf8(String[] strings, EncodeContext ctx) {
            int n = strings.length;

            var valueMap = new LinkedHashMap<String, Integer>();
            for (String s : strings) {
                valueMap.computeIfAbsent(s, _ -> valueMap.size());
            }

            int dictSize = valueMap.size();
            PType codePType = codePType(dictSize);
            int codeBytes = codePType.byteSize();

            byte[][] dictByteArrays = new byte[dictSize][];
            int j = 0;
            long totalDictBytes = 0;
            for (String s : valueMap.keySet()) {
                dictByteArrays[j] = s.getBytes(StandardCharsets.UTF_8);
                totalDictBytes += dictByteArrays[j].length;
                j++;
            }

            Arena arena = ctx.arena();
            MemorySegment dictBytesBuf = arena.allocate(totalDictBytes > 0 ? totalDictBytes : 1);
            MemorySegment dictOffsetsBuf = arena.allocate((long) (dictSize + 1) * Long.BYTES, Long.BYTES);

            long pos = 0;
            dictOffsetsBuf.setAtIndex(PTypeIO.LE_LONG, 0, 0L);
            for (int i = 0; i < dictSize; i++) {
                MemorySegment.copy(MemorySegment.ofArray(dictByteArrays[i]), 0, dictBytesBuf, pos, dictByteArrays[i].length);
                pos += dictByteArrays[i].length;
                dictOffsetsBuf.setAtIndex(PTypeIO.LE_LONG, i + 1, pos);
            }

            MemorySegment codesBuf = arena.allocate((long) n * codeBytes);
            for (int i = 0; i < n; i++) {
                writeCodeToSeg(codesBuf, codePType, i, valueMap.get(strings[i]));
            }

            byte[] metaBytes = EncodingProtos.DictMetadata.newBuilder()
                                       .setValuesLen(dictSize)
                                       .setCodesPtype(io.github.dfa1.vortex.proto.DTypeProtos.PType.forNumber(codePType.ordinal()))
                                       .build()
                                       .toByteArray();

            byte[] varBinMetaBytes = EncodingProtos.VarBinMetadata.newBuilder()
                                             .setOffsetsPtype(io.github.dfa1.vortex.proto.DTypeProtos.PType.forNumber(PType.I64.ordinal()))
                                             .build()
                                             .toByteArray();

            // Rust layout: children[0]=codes, children[1]=values(VarBin)
            EncodeNode offsetsNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, 1);
            EncodeNode valuesNode = new EncodeNode(EncodingId.VORTEX_VARBIN,
                    ByteBuffer.wrap(varBinMetaBytes),
                    new EncodeNode[]{offsetsNode},
                    new int[]{0});
            EncodeNode codesNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, 2);
            EncodeNode root = new EncodeNode(
                    EncodingId.VORTEX_DICT, ByteBuffer.wrap(metaBytes),
                    new EncodeNode[]{codesNode, valuesNode},
                    new int[0]);

            String minStr = valueMap.keySet().stream().min(String::compareTo).orElse(null);
            String maxStr = valueMap.keySet().stream().max(String::compareTo).orElse(null);
            byte[] statsMin = minStr != null
                                      ? ScalarProtos.ScalarValue.newBuilder().setStringValue(minStr).build().toByteArray() : null;
            byte[] statsMax = maxStr != null
                                      ? ScalarProtos.ScalarValue.newBuilder().setStringValue(maxStr).build().toByteArray() : null;
            return new EncodeResult(root, List.of(dictBytesBuf, dictOffsetsBuf, codesBuf), statsMin, statsMax);
        }

        private static DictData buildDictData(DType dtype, Object data, EncodeContext ctx) {
            PType ptype = ((DType.Primitive) dtype).ptype();
            var valueMap = new LinkedHashMap<Object, Integer>();
            int len = arrayLength(data, ptype);
            for (int i = 0; i < len; i++) {
                Object v = readElement(data, ptype, i);
                valueMap.computeIfAbsent(v, _ -> valueMap.size());
            }

            int dictSize = valueMap.size();
            PType codePType = codePType(dictSize);
            int codeBytes = codePType.byteSize();

            Object uniqueArray = buildUniqueArray(ptype, valueMap.keySet(), dictSize);
            MemorySegment valuesBuf = PTypeIO.copyArray(ptype, uniqueArray, dictSize);

            MemorySegment codesBuf = ctx.arena().allocate((long) len * codeBytes);
            for (int i = 0; i < len; i++) {
                Object v = readElement(data, ptype, i);
                int code = valueMap.get(v);
                writeCodeToSeg(codesBuf, codePType, i, code);
            }

            // Native array form needed for cascading (downstream encoder receives typed array)
            Object codesArr = switch (codePType) {
                case U8 -> {
                    byte[] a = new byte[len];
                    for (int i = 0; i < len; i++) {
                        a[i] = codesBuf.get(ValueLayout.JAVA_BYTE, i);
                    }
                    yield a;
                }
                case U16 -> {
                    short[] a = new short[len];
                    for (int i = 0; i < len; i++) {
                        a[i] = codesBuf.get(PTypeIO.LE_SHORT, (long) i * 2);
                    }
                    yield a;
                }
                default -> {
                    int[] a = new int[len];
                    for (int i = 0; i < len; i++) {
                        a[i] = codesBuf.get(PTypeIO.LE_INT, (long) i * 4);
                    }
                    yield a;
                }
            };
            return new DictData(valuesBuf, codesArr, codePType, len);
        }

        private static PType codePType(int dictSize) {
            if (dictSize <= 256) {
                return PType.U8;
            }
            if (dictSize <= 65536) {
                return PType.U16;
            }
            return PType.U32;
        }

        private static int arrayLength(Object data, PType ptype) {
            return switch (ptype) {
                case I8, U8 -> ((byte[]) data).length;
                case I16, U16 -> ((short[]) data).length;
                case I32, U32 -> ((int[]) data).length;
                case I64, U64 -> ((long[]) data).length;
                case F32 -> ((float[]) data).length;
                case F64 -> ((double[]) data).length;
                case F16 -> ((short[]) data).length;
            };
        }

        private static Object readElement(Object data, PType ptype, int i) {
            return switch (ptype) {
                case I8, U8 -> ((byte[]) data)[i];
                case I16, U16, F16 -> ((short[]) data)[i];
                case I32, U32 -> ((int[]) data)[i];
                case I64, U64 -> ((long[]) data)[i];
                case F32 -> ((float[]) data)[i];
                case F64 -> ((double[]) data)[i];
            };
        }

        private static Object buildUniqueArray(PType ptype, Iterable<Object> uniques, int dictSize) {
            return switch (ptype) {
                case I8, U8 -> {
                    byte[] a = new byte[dictSize];
                    int i = 0;
                    for (Object v : uniques) {
                        a[i++] = (Byte) v;
                    }
                    yield a;
                }
                case I16, U16 -> {
                    short[] a = new short[dictSize];
                    int i = 0;
                    for (Object v : uniques) {
                        a[i++] = (Short) v;
                    }
                    yield a;
                }
                case I32, U32 -> {
                    int[] a = new int[dictSize];
                    int i = 0;
                    for (Object v : uniques) {
                        a[i++] = (Integer) v;
                    }
                    yield a;
                }
                case I64, U64 -> {
                    long[] a = new long[dictSize];
                    int i = 0;
                    for (Object v : uniques) {
                        a[i++] = (Long) v;
                    }
                    yield a;
                }
                case F32 -> {
                    float[] a = new float[dictSize];
                    int i = 0;
                    for (Object v : uniques) {
                        a[i++] = (Float) v;
                    }
                    yield a;
                }
                case F64 -> {
                    double[] a = new double[dictSize];
                    int i = 0;
                    for (Object v : uniques) {
                        a[i++] = (Double) v;
                    }
                    yield a;
                }
                case F16 -> {
                    short[] a = new short[dictSize];
                    int i = 0;
                    for (Object v : uniques) {
                        a[i++] = (Short) v;
                    }
                    yield a;
                }
            };
        }

        private static void writeCodeToSeg(MemorySegment seg, PType codePType, int idx, int code) {
            switch (codePType) {
                case U8 -> seg.set(ValueLayout.JAVA_BYTE, idx, (byte) code);
                case U16 -> seg.set(PTypeIO.LE_SHORT, (long) idx * 2, (short) code);
                case U32 -> seg.set(PTypeIO.LE_INT, (long) idx * 4, code);
                default -> throw new VortexException(EncodingId.VORTEX_DICT, "unexpected code type: " + codePType);
            }
        }

        private static int readCodeFromArr(Object arr, PType codePType, int i) {
            return switch (codePType) {
                case U8 -> Byte.toUnsignedInt(((byte[]) arr)[i]);
                case U16 -> Short.toUnsignedInt(((short[]) arr)[i]);
                default -> ((int[]) arr)[i];
            };
        }

        private record DictData(MemorySegment valuesBuf, Object codesArr, PType codePType, int len) {
        }
    }

    private static final class Decoder {

        private static Array decode(DecodeContext ctx) {
            ByteBuffer meta = ctx.metadata();

            if (ctx.dtype() instanceof DType.Utf8) {
                if (ctx.node().children().length == 0) {
                    if (meta == null || !meta.hasRemaining()) {
                        throw new VortexException(EncodingId.VORTEX_DICT, "missing metadata for legacy utf8 dict");
                    }
                    return decodeUtf8DictLegacy(ctx, meta);
                }
                if (meta == null || !meta.hasRemaining()) {
                    throw new VortexException(EncodingId.VORTEX_DICT, "missing metadata for utf8 dict");
                }
                return decodeUtf8DictProto(ctx, meta.duplicate());
            }

            if (meta == null || !meta.hasRemaining()) {
                throw new VortexException(EncodingId.VORTEX_DICT, "missing metadata");
            }

            // 1-byte = legacy Java format; multi-byte = Rust proto format
            if (meta.remaining() == 1) {
                return decodeLegacyJava(ctx, meta.get(0));
            }
            return decodeRustProto(ctx, meta.duplicate());
        }

        private static Array decodeLegacyJava(DecodeContext ctx, byte codeTypeByte) {
            PType codePType = PType.values()[Byte.toUnsignedInt(codeTypeByte)];
            PType valPType = ((DType.Primitive) ctx.dtype()).ptype();
            int elemSize = valPType.byteSize();
            long rowCount = ctx.rowCount();

            // Values: always VORTEX_PRIMITIVE leaf, read direct
            MemorySegment valuesBuf = ctx.segmentBuffers()[ctx.node().children()[0].bufferIndices()[0]];

            // Codes: decode through registry — supports both raw (VORTEX_PRIMITIVE) and cascade (FASTLANES_BITPACKED) children
            DType codesDtype = new DType.Primitive(codePType, false);
            Array codesArr = ctx.decodeChild(1, codesDtype, rowCount);
            MemorySegment codesBuf = codesArr.segment();

            MemorySegment out = ctx.arena().allocate(rowCount * (long) elemSize);
            switch (codePType) {
                case U8 -> expandU8(codesBuf, valuesBuf, out, rowCount, elemSize);
                case U16 -> expandU16(codesBuf, valuesBuf, out, rowCount, elemSize);
                case U32 -> expandU32(codesBuf, valuesBuf, out, rowCount, elemSize);
                default -> {
                    for (long i = 0; i < rowCount; i++) {
                        long code = readCode(codesBuf, codePType, i);
                        MemorySegment.copy(valuesBuf, code * elemSize, out, i * elemSize, elemSize);
                    }
                }
            }
            return typedArray(ctx.dtype(), valPType, rowCount, out.asReadOnly());
        }

        private static Array decodeRustProto(DecodeContext ctx, ByteBuffer metaBuf) {
            EncodingProtos.DictMetadata meta;
            try {
                meta = EncodingProtos.DictMetadata.parseFrom(metaBuf);
            } catch (InvalidProtocolBufferException e) {
                throw new VortexException(EncodingId.VORTEX_DICT, "invalid proto metadata", e);
            }

            PType codePType = PType.values()[meta.getCodesPtype().getNumber()];
            long valuesLen = meta.getValuesLen();
            long rowCount = ctx.rowCount();
            PType valPType = ((DType.Primitive) ctx.dtype()).ptype();
            int elemSize = valPType.byteSize();

            // Rust layout: children[0]=codes, children[1]=values
            DType codesDtype = new DType.Primitive(codePType, false);
            Array codesArr = ctx.decodeChild(0, codesDtype, rowCount);
            Array valuesArr = ctx.decodeChild(1, ctx.dtype(), valuesLen);

            MemorySegment codesBuf = codesArr.segment();
            MemorySegment valuesBuf = valuesArr.segment();

            MemorySegment out = ctx.arena().allocate(rowCount * (long) elemSize);
            // Loop-unswitch: pull the codePType switch outside the hot loop so the JIT
            // sees a tight, type-specific loop with predictable branches.
            switch (codePType) {
                case U8 -> expandU8(codesBuf, valuesBuf, out, rowCount, elemSize);
                case U16 -> expandU16(codesBuf, valuesBuf, out, rowCount, elemSize);
                case U32 -> expandU32(codesBuf, valuesBuf, out, rowCount, elemSize);
                default -> throw new VortexException(EncodingId.VORTEX_DICT, "unexpected code type: " + codePType);
            }
            return typedArray(ctx.dtype(), valPType, rowCount, out.asReadOnly());
        }

        private static Array decodeUtf8DictLegacy(DecodeContext ctx, ByteBuffer meta) {
            PType codePType = PType.values()[Byte.toUnsignedInt(meta.get(0))];
            long n = ctx.rowCount();

            MemorySegment dictBytes = ctx.buffer(0);
            MemorySegment dictOffsets = ctx.buffer(1);
            MemorySegment codes = ctx.buffer(2);

            return VarBinArray.ofDict(ctx.dtype(), n,
                    dictBytes, dictOffsets, PType.I64,
                    codes, codePType);
        }

        private static Array decodeUtf8DictProto(DecodeContext ctx, ByteBuffer metaBuf) {
            // DictMetadata proto (field 1=values_len, field 2=codes_ptype) — same message Rust uses.
            EncodingProtos.DictMetadata meta;
            try {
                meta = EncodingProtos.DictMetadata.parseFrom(metaBuf);
            } catch (com.google.protobuf.InvalidProtocolBufferException e) {
                throw new VortexException(EncodingId.VORTEX_DICT, "invalid utf8 dict proto metadata", e);
            }
            PType codePType = PType.values()[meta.getCodesPtype().getNumber()];
            long dictSize = meta.getValuesLen();
            long n = ctx.rowCount();

            // Rust layout: children[0]=codes, children[1]=values(VarBin)
            DType codesDtype = new DType.Primitive(codePType, false);
            Array codesArr = ctx.decodeChild(0, codesDtype, n);
            MemorySegment codesBuf = codesArr.segment();

            Array valuesArr = ctx.decodeChild(1, ctx.dtype(), dictSize);
            VarBinArray varBinValues = (VarBinArray) valuesArr;
            MemorySegment dictBytes = varBinValues.bytesSegment();
            MemorySegment dictOffsets = varBinValues.offsetsSegment();

            return VarBinArray.ofDict(ctx.dtype(), n,
                    dictBytes, dictOffsets, PType.I64,
                    codesBuf, codePType);
        }

        private static long readCode(MemorySegment buf, PType codePType, long i) {
            return switch (codePType) {
                case U8 -> Byte.toUnsignedLong(buf.get(ValueLayout.JAVA_BYTE, i));
                case U16 -> Short.toUnsignedLong(buf.get(PTypeIO.LE_SHORT, i * 2));
                case U32 -> Integer.toUnsignedLong(buf.get(PTypeIO.LE_INT, i * 4));
                default -> throw new VortexException(EncodingId.VORTEX_DICT, "unexpected code type: " + codePType);
            };
        }

        private static void expandU8(
                MemorySegment codes, MemorySegment values, MemorySegment out,
                long rowCount, int elemSize
        ) {
            switch (elemSize) {
                case 8 -> {
                    for (long i = 0; i < rowCount; i++) {
                        long code = Byte.toUnsignedLong(codes.get(ValueLayout.JAVA_BYTE, i));
                        out.setAtIndex(PTypeIO.LE_LONG, i, values.getAtIndex(PTypeIO.LE_LONG, code));
                    }
                }
                case 4 -> {
                    for (long i = 0; i < rowCount; i++) {
                        long code = Byte.toUnsignedLong(codes.get(ValueLayout.JAVA_BYTE, i));
                        out.setAtIndex(PTypeIO.LE_INT, i, values.getAtIndex(PTypeIO.LE_INT, code));
                    }
                }
                case 2 -> {
                    for (long i = 0; i < rowCount; i++) {
                        long code = Byte.toUnsignedLong(codes.get(ValueLayout.JAVA_BYTE, i));
                        out.setAtIndex(PTypeIO.LE_SHORT, i, values.getAtIndex(PTypeIO.LE_SHORT, code));
                    }
                }
                case 1 -> {
                    for (long i = 0; i < rowCount; i++) {
                        long code = Byte.toUnsignedLong(codes.get(ValueLayout.JAVA_BYTE, i));
                        out.set(ValueLayout.JAVA_BYTE, i, values.get(ValueLayout.JAVA_BYTE, code));
                    }
                }
                default -> {
                    for (long i = 0, outOff = 0; i < rowCount; i++, outOff += elemSize) {
                        long code = Byte.toUnsignedLong(codes.get(ValueLayout.JAVA_BYTE, i));
                        MemorySegment.copy(values, code * elemSize, out, outOff, elemSize);
                    }
                }
            }
        }

        private static void expandU16(
                MemorySegment codes, MemorySegment values, MemorySegment out,
                long rowCount, int elemSize
        ) {
            switch (elemSize) {
                case 8 -> {
                    for (long i = 0; i < rowCount; i++) {
                        long code = Short.toUnsignedLong(codes.get(PTypeIO.LE_SHORT, i * 2));
                        out.setAtIndex(PTypeIO.LE_LONG, i, values.getAtIndex(PTypeIO.LE_LONG, code));
                    }
                }
                case 4 -> {
                    for (long i = 0; i < rowCount; i++) {
                        long code = Short.toUnsignedLong(codes.get(PTypeIO.LE_SHORT, i * 2));
                        out.setAtIndex(PTypeIO.LE_INT, i, values.getAtIndex(PTypeIO.LE_INT, code));
                    }
                }
                case 2 -> {
                    for (long i = 0; i < rowCount; i++) {
                        long code = Short.toUnsignedLong(codes.get(PTypeIO.LE_SHORT, i * 2));
                        out.setAtIndex(PTypeIO.LE_SHORT, i, values.getAtIndex(PTypeIO.LE_SHORT, code));
                    }
                }
                case 1 -> {
                    for (long i = 0; i < rowCount; i++) {
                        long code = Short.toUnsignedLong(codes.get(PTypeIO.LE_SHORT, i * 2));
                        out.set(ValueLayout.JAVA_BYTE, i, values.get(ValueLayout.JAVA_BYTE, code));
                    }
                }
                default -> {
                    for (long i = 0, outOff = 0; i < rowCount; i++, outOff += elemSize) {
                        long code = Short.toUnsignedLong(codes.get(PTypeIO.LE_SHORT, i * 2));
                        MemorySegment.copy(values, code * elemSize, out, outOff, elemSize);
                    }
                }
            }
        }

        private static void expandU32(
                MemorySegment codes, MemorySegment values, MemorySegment out,
                long rowCount, int elemSize
        ) {
            switch (elemSize) {
                case 8 -> {
                    for (long i = 0; i < rowCount; i++) {
                        long code = Integer.toUnsignedLong(codes.get(PTypeIO.LE_INT, i * 4));
                        out.setAtIndex(PTypeIO.LE_LONG, i, values.getAtIndex(PTypeIO.LE_LONG, code));
                    }
                }
                case 4 -> {
                    for (long i = 0; i < rowCount; i++) {
                        long code = Integer.toUnsignedLong(codes.get(PTypeIO.LE_INT, i * 4));
                        out.setAtIndex(PTypeIO.LE_INT, i, values.getAtIndex(PTypeIO.LE_INT, code));
                    }
                }
                case 2 -> {
                    for (long i = 0; i < rowCount; i++) {
                        long code = Integer.toUnsignedLong(codes.get(PTypeIO.LE_INT, i * 4));
                        out.setAtIndex(PTypeIO.LE_SHORT, i, values.getAtIndex(PTypeIO.LE_SHORT, code));
                    }
                }
                case 1 -> {
                    for (long i = 0; i < rowCount; i++) {
                        long code = Integer.toUnsignedLong(codes.get(PTypeIO.LE_INT, i * 4));
                        out.set(ValueLayout.JAVA_BYTE, i, values.get(ValueLayout.JAVA_BYTE, code));
                    }
                }
                default -> {
                    for (long i = 0, outOff = 0; i < rowCount; i++, outOff += elemSize) {
                        long code = Integer.toUnsignedLong(codes.get(PTypeIO.LE_INT, i * 4));
                        MemorySegment.copy(values, code * elemSize, out, outOff, elemSize);
                    }
                }
            }
        }

        private static Array typedArray(DType dtype, PType ptype, long n, MemorySegment seg) {
            return switch (ptype) {
                case I64, U64 -> new LongArray(dtype, n, seg);
                case I32, U32 -> new IntArray(dtype, n, seg);
                case F64 -> new DoubleArray(dtype, n, seg);
                case F32 -> new FloatArray(dtype, n, seg);
                case I16, U16 -> new ShortArray(dtype, n, seg);
                case I8, U8 -> new ByteArray(dtype, n, seg);
                default -> throw new VortexException(EncodingId.VORTEX_DICT, "unsupported ptype " + ptype);
            };
        }
    }
}
