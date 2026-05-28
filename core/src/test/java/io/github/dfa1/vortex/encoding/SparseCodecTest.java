package io.github.dfa1.vortex.encoding;

import dev.vortex.proto.DTypeProtos;
import dev.vortex.proto.EncodingProtos;
import dev.vortex.proto.ScalarProtos;
import io.github.dfa1.vortex.core.Array;
import io.github.dfa1.vortex.core.ArrayStats;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.assertj.core.api.Assertions.assertThat;

class SparseCodecTest {

    private static final DType I64_DTYPE = new DType.Primitive(PType.I64, false);
    private static final DType F64_DTYPE = new DType.Primitive(PType.F64, false);

    @Test
    void decode_noPatches_returnsFillValue() {
        // Given — 5 elements, fill=99, no patches
        long fill = 99L;
        DecodeContext ctx = buildSparseCtx(I64_DTYPE, 5, fill, PType.U32, new long[0], new long[0]);
        SparseCodec sut = new SparseCodec();

        // When
        Array result = sut.decode(ctx);

        // Then
        assertThat(result.length()).isEqualTo(5L);
        var layout = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 5; i++) {
            assertThat(result.buffer(0).get(layout, (long) i * 8))
                .as("index %d", i).isEqualTo(fill);
        }
    }

    @Test
    void decode_withPatches_overwritesAtIndices() {
        // Given — 8 elements, fill=0, patches at indices [1, 5] with values [10, 50]
        long fill = 0L;
        long[] patchIndices = {1L, 5L};
        long[] patchValues  = {10L, 50L};
        DecodeContext ctx = buildSparseCtx(I64_DTYPE, 8, fill, PType.U32, patchIndices, patchValues);
        SparseCodec sut = new SparseCodec();

        // When
        Array result = sut.decode(ctx);

        // Then
        var layout = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
        long[] expected = {0, 10, 0, 0, 0, 50, 0, 0};
        for (int i = 0; i < expected.length; i++) {
            assertThat(result.buffer(0).get(layout, (long) i * 8))
                .as("index %d", i).isEqualTo(expected[i]);
        }
    }

    @Test
    void decode_f64_fillAndPatches() {
        // Given — 4 F64 elements, fill=NaN bits, patch at index 2 with value 3.14
        double fillVal = Double.NaN;
        double patchVal = 3.14;
        DecodeContext ctx = buildSparseCtxF64(F64_DTYPE, 4, fillVal, new long[]{2L}, new double[]{patchVal});
        SparseCodec sut = new SparseCodec();

        // When
        Array result = sut.decode(ctx);

        // Then
        var layout = ValueLayout.JAVA_DOUBLE_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
        assertThat(result.buffer(0).get(layout, 0L)).isNaN();
        assertThat(result.buffer(0).get(layout, 8L)).isNaN();
        assertThat(result.buffer(0).get(layout, 16L)).isEqualTo(3.14);
        assertThat(result.buffer(0).get(layout, 24L)).isNaN();
    }

    @Test
    void decode_offsetSubtracted() {
        // Given — offset=10, patch index=12 → absolute position = 12 - 10 = 2
        long[] patchIndices = {12L};
        long[] patchValues  = {777L};
        DecodeContext ctx = buildSparseCtxWithOffset(I64_DTYPE, 5, 0L, PType.U32, patchIndices, patchValues, 10L);
        SparseCodec sut = new SparseCodec();

        // When
        Array result = sut.decode(ctx);

        // Then
        var layout = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
        assertThat(result.buffer(0).get(layout, 16L)).isEqualTo(777L);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static DecodeContext buildSparseCtx(
        DType dtype, long rowCount, long fillLong, PType idxPtype,
        long[] patchIndices, long[] patchValues
    ) {
        return buildSparseCtxWithOffset(dtype, rowCount, fillLong, idxPtype, patchIndices, patchValues, 0L);
    }

    private static DecodeContext buildSparseCtxWithOffset(
        DType dtype, long rowCount, long fillLong, PType idxPtype,
        long[] patchIndices, long[] patchValues, long offset
    ) {
        byte[] fillBytes = ScalarProtos.ScalarValue.newBuilder()
            .setInt64Value(fillLong).build().toByteArray();

        byte[] metaBytes = buildSparseMetaBytes(patchIndices.length, offset, idxPtype);

        byte[] idxBuf = toLEBytes(patchIndices, idxPtype);
        byte[] valBuf = toLEBytes(patchValues, PType.I64);

        return buildCtx(dtype, rowCount, fillBytes, metaBytes, idxBuf, valBuf,
            new DType.Primitive(idxPtype, false));
    }

    private static DecodeContext buildSparseCtxF64(
        DType dtype, long rowCount, double fillDouble,
        long[] patchIndices, double[] patchValues
    ) {
        byte[] fillBytes = ScalarProtos.ScalarValue.newBuilder()
            .setF64Value(fillDouble).build().toByteArray();
        byte[] metaBytes = buildSparseMetaBytes(patchIndices.length, 0L, PType.U32);

        byte[] idxBuf = toLEBytes(patchIndices, PType.U32);
        byte[] valBuf = f64LEBytes(patchValues);

        return buildCtx(dtype, rowCount, fillBytes, metaBytes, idxBuf, valBuf,
            new DType.Primitive(PType.U32, false));
    }

    private static DecodeContext buildCtx(
        DType dtype, long rowCount,
        byte[] fillBytes, byte[] metaBytes,
        byte[] idxBuf, byte[] valBuf,
        DType idxDtype
    ) {
        ArrayNode idxNode = new ArrayNode("vortex.primitive", null,
            new ArrayNode[0], new int[]{1}, ArrayStats.empty());
        ArrayNode valNode = new ArrayNode("vortex.primitive", null,
            new ArrayNode[0], new int[]{2}, ArrayStats.empty());
        ArrayNode sparseNode = new ArrayNode("vortex.sparse",
            ByteBuffer.wrap(metaBytes),
            new ArrayNode[]{idxNode, valNode},
            new int[]{0},
            ArrayStats.empty());

        MemorySegment[] segments = {
            MemorySegment.ofArray(fillBytes),
            MemorySegment.ofArray(idxBuf),
            MemorySegment.ofArray(valBuf)
        };

        DecoderRegistry registry = DecoderRegistry.empty();
        registry.register(new SparseCodec());
        registry.register(new PrimitiveCodec());

        return new DecodeContext(sparseNode, dtype, rowCount, segments, registry, java.lang.foreign.Arena.global());
    }

    private static byte[] buildSparseMetaBytes(long numPatches, long offset, PType idxPtype) {
        EncodingProtos.PatchesMetadata patchesMeta = EncodingProtos.PatchesMetadata.newBuilder()
            .setLen(numPatches)
            .setOffset(offset)
            .setIndicesPtype(DTypeProtos.PType.forNumber(idxPtype.ordinal()))
            .build();
        return EncodingProtos.SparseMetadata.newBuilder()
            .setPatches(patchesMeta)
            .build()
            .toByteArray();
    }

    private static byte[] toLEBytes(long[] values, PType ptype) {
        int elemBytes = ptype.byteSize();
        byte[] buf = new byte[values.length * elemBytes];
        ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);
        for (long v : values) {
            switch (ptype) {
                case U8,  I8  -> bb.put((byte) v);
                case U16, I16 -> bb.putShort((short) v);
                case U32, I32 -> bb.putInt((int) v);
                case U64, I64 -> bb.putLong(v);
                default -> throw new UnsupportedOperationException(ptype.name());
            }
        }
        return buf;
    }

    private static byte[] f64LEBytes(double[] values) {
        byte[] buf = new byte[values.length * 8];
        ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);
        for (double v : values) {
            bb.putDouble(v);
        }
        return buf;
    }
}
