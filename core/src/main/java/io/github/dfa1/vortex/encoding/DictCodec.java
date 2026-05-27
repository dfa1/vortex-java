package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedHashMap;
import java.util.List;

/// Codec for `vortex.dict` — dictionary encoding for low-cardinality columns.
///
/// Segment layout: [values buffer (unique values, primitive)] [codes buffer (per-row indices)].
/// Metadata (1 byte): code PType ordinal (0=U8, 1=U16, 2=U32).
/// Node tree: DictNode{ children=[ValuesNode{buf=0}, CodesNode{buf=1}] }.
public final class DictCodec implements Codec {

    @Override
    public String encodingId() {
        return "vortex.dict";
    }

    @Override
    public boolean accepts(DType dtype) {
        return dtype instanceof DType.Primitive;
    }

    // ── Encode ────────────────────────────────────────────────────────────────

    @Override
    public EncodeResult encode(DType dtype, Object data) {
        PType ptype = ((DType.Primitive) dtype).ptype();

        // Build value→code map preserving insertion order
        var valueMap = new LinkedHashMap<Object, Integer>();
        int len = arrayLength(data, ptype);
        for (int i = 0; i < len; i++) {
            Object v = readElement(data, ptype, i);
            valueMap.computeIfAbsent(v, k -> valueMap.size());
        }

        int dictSize = valueMap.size();
        PType codePType = codePType(dictSize);
        int   codeBytes = codePType.byteSize();

        // Values buffer: unique values in insertion order
        ByteBuffer valuesBuf = ByteBuffer.allocate(dictSize * ptype.byteSize())
            .order(ByteOrder.LITTLE_ENDIAN);
        for (Object v : valueMap.keySet()) {
            writeElement(valuesBuf, ptype, v);
        }
        valuesBuf.flip();

        // Codes buffer: per-row index into values
        ByteBuffer codesBuf = ByteBuffer.allocate(len * codeBytes)
            .order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < len; i++) {
            Object v    = readElement(data, ptype, i);
            int    code = valueMap.get(v);
            writeCode(codesBuf, codePType, code);
        }
        codesBuf.flip();

        // Metadata: code PType ordinal
        ByteBuffer meta = ByteBuffer.allocate(1).put(0, (byte) codePType.ordinal());

        EncodeNode valuesNode = EncodeNode.leaf("vortex.primitive", 0);
        EncodeNode codesNode  = EncodeNode.leaf("vortex.primitive", 1);
        EncodeNode rootNode   = new EncodeNode(
            "vortex.dict", meta,
            new EncodeNode[]{valuesNode, codesNode},
            new int[0]);

        return new EncodeResult(rootNode, List.of(valuesBuf, codesBuf), null, null);
    }

    // ── Decode ────────────────────────────────────────────────────────────────

    @Override
    public Array decode(DecodeContext ctx) {
        if (ctx.metadata() == null || !ctx.metadata().hasRemaining()) {
            throw new IllegalStateException("vortex.dict: missing code type metadata");
        }
        PType codePType = PType.values()[Byte.toUnsignedInt(ctx.metadata().get(0))];
        PType valPType  = ((DType.Primitive) ctx.dtype()).ptype();
        int   elemSize  = valPType.byteSize();
        long  rowCount  = ctx.rowCount();

        // Buffer layout: [0]=values, [1]=codes
        MemorySegment valuesBuf = ctx.segmentBuffers()[ctx.node().children()[0].bufferIndices()[0]];
        MemorySegment codesBuf  = ctx.segmentBuffers()[ctx.node().children()[1].bufferIndices()[0]];

        // Expand: copy value[code[i]] into output for each row i
        byte[] expanded = new byte[(int) (rowCount * elemSize)];
        MemorySegment out = MemorySegment.ofArray(expanded);
        for (long i = 0; i < rowCount; i++) {
            long code = readCode(codesBuf, codePType, i);
            MemorySegment.copy(valuesBuf, code * elemSize, out, i * elemSize, elemSize);
        }

        return new Array(ctx.dtype(), rowCount,
            new MemorySegment[]{out}, new Array[0], ArrayStats.empty());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static PType codePType(int dictSize) {
        if (dictSize <= 256)   { return PType.U8;  }
        if (dictSize <= 65536) { return PType.U16; }
        return PType.U32;
    }

    private static int arrayLength(Object data, PType ptype) {
        return switch (ptype) {
            case I8,  U8  -> ((byte[])   data).length;
            case I16, U16 -> ((short[])  data).length;
            case I32, U32 -> ((int[])    data).length;
            case I64, U64 -> ((long[])   data).length;
            case F32      -> ((float[])  data).length;
            case F64      -> ((double[]) data).length;
            case F16 -> throw new UnsupportedOperationException("F16 not supported");
        };
    }

    private static Object readElement(Object data, PType ptype, int i) {
        return switch (ptype) {
            case I8,  U8  -> ((byte[])   data)[i];
            case I16, U16 -> ((short[])  data)[i];
            case I32, U32 -> ((int[])    data)[i];
            case I64, U64 -> ((long[])   data)[i];
            case F32      -> ((float[])  data)[i];
            case F64      -> ((double[]) data)[i];
            case F16 -> throw new UnsupportedOperationException("F16 not supported");
        };
    }

    private static void writeElement(ByteBuffer buf, PType ptype, Object v) {
        switch (ptype) {
            case I8,  U8  -> buf.put((Byte)    v);
            case I16, U16 -> buf.putShort((Short)  v);
            case I32, U32 -> buf.putInt((Integer) v);
            case I64, U64 -> buf.putLong((Long)   v);
            case F32      -> buf.putFloat((Float)  v);
            case F64      -> buf.putDouble((Double) v);
            case F16 -> throw new UnsupportedOperationException("F16 not supported");
        }
    }

    private static void writeCode(ByteBuffer buf, PType codePType, int code) {
        switch (codePType) {
            case U8  -> buf.put((byte) code);
            case U16 -> buf.putShort((short) code);
            case U32 -> buf.putInt(code);
            default  -> throw new IllegalStateException("unexpected code type: " + codePType);
        }
    }

    private static long readCode(MemorySegment buf, PType codePType, long i) {
        return switch (codePType) {
            case U8  -> Byte.toUnsignedLong(buf.get(ValueLayout.JAVA_BYTE, i));
            case U16 -> Short.toUnsignedLong(
                buf.get(ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), i * 2));
            case U32 -> Integer.toUnsignedLong(
                buf.get(ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), i * 4));
            default  -> throw new IllegalStateException("unexpected code type: " + codePType);
        };
    }
}
