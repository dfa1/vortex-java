package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.proto.ProtoALPRDMetadata;
import io.github.dfa1.vortex.core.proto.ProtoPType;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.DoubleArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import io.github.dfa1.vortex.core.error.VortexException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class AlpRdEncodingDecoderTest {

    private static final AlpRdEncodingDecoder SUT = new AlpRdEncodingDecoder();
    private static final ReadRegistry REGISTRY = TestRegistry.ofDecoders(
            SUT, new PrimitiveEncodingDecoder(), new BoolEncodingDecoder());

    // ALP-RD reconstruction is `longBitsToDouble((dict[left] << rightBitWidth) | right)`.
    // With rightBitWidth=48, the dict codes are the top 16 bits of an f64 bit pattern:
    // 1.0 = 0x3FF0_0000_0000_0000 -> 0x3FF0; 2.0 = 0x4000_0000_0000_0000 -> 0x4000.
    private static final int RIGHT_BIT_WIDTH = 48;
    private static final int DICT_ONE = 0x3FF0;
    private static final int DICT_TWO = 0x4000;

    @Test
    void decode_nullLeftParts_yieldsNullRows() {
        // Given — left codes [0,1,0] with row 1 null via the left_parts validity bitmap;
        // the decoder must carry that mask onto the reconstructed doubles (#234).
        DecodeContext ctx = maskedContext(new short[]{0, 1, 0}, new long[]{0, 0, 0},
                boolBitmap(true, false, true));

        // When
        Array result = SUT.decode(ctx);

        // Then — the null row survives and valid rows decode to their dictionary doubles
        MaskedArray masked = (MaskedArray) result;
        assertThat(masked.isValid(0)).isTrue();
        assertThat(masked.isValid(1)).isFalse();
        assertThat(masked.isValid(2)).isTrue();
        DoubleArray inner = (DoubleArray) masked.inner();
        assertThat(inner.getDouble(0)).isCloseTo(1.0, within(1e-12));
        assertThat(inner.getDouble(2)).isCloseTo(1.0, within(1e-12));
    }

    @Test
    void decode_unsupportedLeftPartsPtype_throws() {
        // Given — metadata carrying left_parts_ptype=U8; the decoder must reject it loudly
        // rather than silently mis-stride over a 1-byte buffer with a 2-byte stride (#249).
        byte[] meta = new ProtoALPRDMetadata(RIGHT_BIT_WIDTH, 2,
                List.of(DICT_ONE, DICT_TWO), ProtoPType.U8, null).encode();
        ArrayNode leftNode = new ArrayNode(EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[0], new int[]{0});
        ArrayNode rightNode = new ArrayNode(EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[0], new int[]{1});
        ArrayNode node = new ArrayNode(EncodingId.VORTEX_ALPRD, MemorySegment.ofArray(meta),
                new ArrayNode[]{leftNode, rightNode}, new int[0]);
        MemorySegment[] segs = {leShorts(new short[]{0}), leLongs(new long[]{0})};
        DecodeContext ctx = new DecodeContext(node, DType.F64, 1, segs, REGISTRY, Arena.ofAuto());

        // When / Then
        assertThatThrownBy(() -> SUT.decode(ctx))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("left_parts_ptype");
    }

    @Test
    void decode_allValidLeftParts_yieldsPlainArray() {
        // Given — a non-nullable left_parts child (no validity): no-regression path must NOT mask
        DecodeContext ctx = plainContext(new short[]{0, 1, 0}, new long[]{0, 0, 0});

        // When
        Array result = SUT.decode(ctx);

        // Then — a bare DoubleArray, and every row decodes through the dictionary
        assertThat(result).isInstanceOf(DoubleArray.class).isNotInstanceOf(MaskedArray.class);
        DoubleArray inner = (DoubleArray) result;
        assertThat(inner.getDouble(0)).isCloseTo(1.0, within(1e-12));
        assertThat(inner.getDouble(1)).isCloseTo(2.0, within(1e-12));
        assertThat(inner.getDouble(2)).isCloseTo(1.0, within(1e-12));
    }

    private static DecodeContext maskedContext(short[] leftCodes, long[] rightBits, MemorySegment validity) {
        // buffers: 0 = left u16 codes, 1 = right u64 payloads, 2 = validity bitmap
        ArrayNode validityNode = new ArrayNode(EncodingId.VORTEX_BOOL, null, new ArrayNode[0], new int[]{2});
        ArrayNode leftNode = new ArrayNode(EncodingId.VORTEX_PRIMITIVE, null,
                new ArrayNode[]{validityNode}, new int[]{0});
        ArrayNode rightNode = new ArrayNode(EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[0], new int[]{1});
        ArrayNode node = new ArrayNode(EncodingId.VORTEX_ALPRD, metaSegment(),
                new ArrayNode[]{leftNode, rightNode}, new int[0]);
        MemorySegment[] segs = {leShorts(leftCodes), leLongs(rightBits), validity};
        return new DecodeContext(node, DType.F64, leftCodes.length, segs, REGISTRY, Arena.ofAuto());
    }

    private static DecodeContext plainContext(short[] leftCodes, long[] rightBits) {
        // buffers: 0 = left u16 codes, 1 = right u64 payloads (no validity child)
        ArrayNode leftNode = new ArrayNode(EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[0], new int[]{0});
        ArrayNode rightNode = new ArrayNode(EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[0], new int[]{1});
        ArrayNode node = new ArrayNode(EncodingId.VORTEX_ALPRD, metaSegment(),
                new ArrayNode[]{leftNode, rightNode}, new int[0]);
        MemorySegment[] segs = {leShorts(leftCodes), leLongs(rightBits)};
        return new DecodeContext(node, DType.F64, leftCodes.length, segs, REGISTRY, Arena.ofAuto());
    }

    private static MemorySegment metaSegment() {
        byte[] meta = new ProtoALPRDMetadata(RIGHT_BIT_WIDTH, 2,
                List.of(DICT_ONE, DICT_TWO), ProtoPType.U16, null).encode();
        return MemorySegment.ofArray(meta);
    }

    private static MemorySegment leShorts(short... vs) {
        byte[] b = new byte[vs.length * 2];
        ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        for (short v : vs) {
            bb.putShort(v);
        }
        return MemorySegment.ofArray(b);
    }

    private static MemorySegment leLongs(long... vs) {
        byte[] b = new byte[vs.length * 8];
        ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        for (long v : vs) {
            bb.putLong(v);
        }
        return MemorySegment.ofArray(b);
    }

    private static MemorySegment boolBitmap(boolean... valid) {
        byte[] bytes = new byte[(valid.length + 7) / 8];
        for (int i = 0; i < valid.length; i++) {
            if (valid[i]) {
                bytes[i >>> 3] |= (byte) (1 << (i & 7));
            }
        }
        return MemorySegment.ofArray(bytes);
    }
}
