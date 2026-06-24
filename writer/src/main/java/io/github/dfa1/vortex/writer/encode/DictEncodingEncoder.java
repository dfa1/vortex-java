package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.io.PTypeIO;
import io.github.dfa1.vortex.core.proto.ProtoDictMetadata;
import io.github.dfa1.vortex.core.proto.ProtoScalarValue;
import io.github.dfa1.vortex.core.proto.ProtoVarBinMetadata;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;

/// Write-only encoder for `vortex.dict`.
public final class DictEncodingEncoder implements EncodingEncoder {

    /// Public no-arg constructor required by [java.util.ServiceLoader].
    public DictEncodingEncoder() {
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
    public StatsOptions statsOptions() {
        return new StatsOptions(true, false);
    }

    @Override
    public Estimate expectedRatio(DType dtype, Object data, ArrayStats stats) {
        // Stats path only covers Primitive (Utf8 still uses sample-encoded selection).
        if (!(dtype instanceof DType.Primitive) || !stats.hasDistinctCount()) {
            return Estimate.COMPLETE;
        }
        long n = stats.valueCount();
        long distinct = stats.distinctCount();
        if (n == 0) {
            return Estimate.SKIP;
        }
        // Rust FloatDictScheme / IntDictScheme skip rule. Skip-only: the raw dict cost
        // ignores cascade bitpacking on the codes child, so a raw ratio over-estimates
        // dict's effectiveness vs encoders like ALP whose sample measure includes cascade.
        // Defer to the sample-encoded path for the actual win.
        if (distinct * 2 > n) {
            return Estimate.SKIP;
        }
        return Estimate.COMPLETE;
    }

    @Override
    public EncodeResult encode(DType dtype, Object data, EncodeContext ctx) {
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

        MemorySegment meta = MemorySegment.ofArray(new byte[]{(byte) codePType.ordinal()});
        EncodeNode valuesNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, 0);
        EncodeNode codesNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, 1);
        EncodeNode rootNode = new EncodeNode(
                EncodingId.VORTEX_DICT, meta,
                new EncodeNode[]{valuesNode, codesNode},
                new int[0]);

        return new EncodeResult(rootNode, List.of(d.valuesBuf(), codesBuf), null, null);
    }

    @Override
    public CascadeStep encodeCascade(DType dtype, Object data, EncodeContext ctx) {
        if (dtype instanceof DType.Utf8) {
            return CascadeStep.terminal(encodeUtf8((String[]) data, ctx));
        }
        DictData d = buildDictData(dtype, data, ctx);
        PType codePType = d.codePType();

        MemorySegment meta = MemorySegment.ofArray(new byte[]{(byte) codePType.ordinal()});
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
            dictOffsetsBuf.setAtIndex(PTypeIO.LE_LONG, (long) i + 1, pos);
        }

        MemorySegment codesBuf = arena.allocate((long) n * codeBytes);
        for (int i = 0; i < n; i++) {
            writeCodeToSeg(codesBuf, codePType, i, valueMap.get(strings[i]));
        }

        byte[] metaBytes = new ProtoDictMetadata(
                dictSize,
                io.github.dfa1.vortex.core.proto.ProtoPType.fromValue(codePType.ordinal()),
                null,
                null
        ).encode();

        byte[] varBinMetaBytes = new ProtoVarBinMetadata(
                io.github.dfa1.vortex.core.proto.ProtoPType.fromValue(PType.I64.ordinal())
        ).encode();

        EncodeNode offsetsNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, 1);
        EncodeNode valuesNode = new EncodeNode(EncodingId.VORTEX_VARBIN,
                MemorySegment.ofArray(varBinMetaBytes),
                new EncodeNode[]{offsetsNode},
                new int[]{0});
        EncodeNode codesNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, 2);
        EncodeNode root = new EncodeNode(
                EncodingId.VORTEX_DICT, MemorySegment.ofArray(metaBytes),
                new EncodeNode[]{codesNode, valuesNode},
                new int[0]);

        String minStr = valueMap.keySet().stream().min(String::compareTo).orElse(null);
        String maxStr = valueMap.keySet().stream().max(String::compareTo).orElse(null);
        byte[] statsMin = minStr != null ? ProtoScalarValue.ofStringValue(minStr).encode() : null;
        byte[] statsMax = maxStr != null ? ProtoScalarValue.ofStringValue(maxStr).encode() : null;
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
