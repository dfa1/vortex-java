package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.encoding.TestSegments;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.ByteArray;
import io.github.dfa1.vortex.reader.array.IntArray;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.array.ShortArray;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ZigZagEncodingDecoderTest {

    private static final ZigZagEncodingDecoder SUT = new ZigZagEncodingDecoder();
    private static final ReadRegistry REGISTRY = TestRegistry.ofDecoders(SUT, new PrimitiveEncodingDecoder());

    // --- zigzag encode helpers (mirror of the decoder's (u >>> 1) ^ -(u & 1)) ---

    private static MemorySegment encodedBytes(byte... signed) {
        MemorySegment seg = Arena.ofAuto().allocate(signed.length);
        for (int i = 0; i < signed.length; i++) {
            seg.set(ValueLayout.JAVA_BYTE, i, (byte) ((signed[i] << 1) ^ (signed[i] >> 7)));
        }
        return seg;
    }

    private static MemorySegment encodedShorts(short... signed) {
        short[] u = new short[signed.length];
        for (int i = 0; i < signed.length; i++) {
            u[i] = (short) ((signed[i] << 1) ^ (signed[i] >> 15));
        }
        return TestSegments.leShorts(u);
    }

    private static MemorySegment encodedInts(int... signed) {
        int[] u = new int[signed.length];
        for (int i = 0; i < signed.length; i++) {
            u[i] = (signed[i] << 1) ^ (signed[i] >> 31);
        }
        return TestSegments.leInts(u);
    }

    private static MemorySegment encodedLongs(long... signed) {
        long[] u = new long[signed.length];
        for (int i = 0; i < signed.length; i++) {
            u[i] = (signed[i] << 1) ^ (signed[i] >> 63);
        }
        return TestSegments.leLongs(u);
    }

    private static Array decode(PType ptype, long n, MemorySegment encoded) {
        DType dtype = new DType.Primitive(ptype, false);
        ArrayNode child = new ArrayNode(EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[0], new int[]{0});
        ArrayNode node = new ArrayNode(EncodingId.VORTEX_ZIGZAG, null, new ArrayNode[]{child}, new int[]{});
        DecodeContext ctx = new DecodeContext(node, dtype, n, new MemorySegment[]{encoded}, REGISTRY, Arena.ofAuto());
        return SUT.decode(ctx);
    }

    @Test
    void encodingId_isZigzag() {
        // Given / When / Then
        assertThat(SUT.encodingId()).isEqualTo(EncodingId.VORTEX_ZIGZAG);
    }

    @Test
    void decode_i8_roundTrip() {
        // Given
        byte[] signed = {0, -1, 1, Byte.MIN_VALUE, Byte.MAX_VALUE, -42};

        // When
        Array result = decode(PType.I8, signed.length, encodedBytes(signed));

        // Then
        assertThat(result).isInstanceOf(ByteArray.class);
        ByteArray bytes = (ByteArray) result;
        for (int i = 0; i < signed.length; i++) {
            assertThat(bytes.getByte(i)).as("index %d", i).isEqualTo(signed[i]);
        }
    }

    @Test
    void decode_i16_roundTrip() {
        // Given
        short[] signed = {0, -1, 1, Short.MIN_VALUE, Short.MAX_VALUE, -1000};

        // When
        Array result = decode(PType.I16, signed.length, encodedShorts(signed));

        // Then
        assertThat(result).isInstanceOf(ShortArray.class);
        ShortArray shorts = (ShortArray) result;
        for (int i = 0; i < signed.length; i++) {
            assertThat(shorts.getShort(i)).as("index %d", i).isEqualTo(signed[i]);
        }
    }

    @Test
    void decode_i32_roundTrip() {
        // Given
        int[] signed = {0, -1, 1, Integer.MIN_VALUE, Integer.MAX_VALUE, -123456};

        // When
        Array result = decode(PType.I32, signed.length, encodedInts(signed));

        // Then
        assertThat(result).isInstanceOf(IntArray.class);
        IntArray ints = (IntArray) result;
        for (int i = 0; i < signed.length; i++) {
            assertThat(ints.getInt(i)).as("index %d", i).isEqualTo(signed[i]);
        }
    }

    @Test
    void decode_i64_roundTrip() {
        // Given
        long[] signed = {0, -1, 1, Long.MIN_VALUE, Long.MAX_VALUE, -9_000_000_000L};

        // When
        Array result = decode(PType.I64, signed.length, encodedLongs(signed));

        // Then
        assertThat(result).isInstanceOf(LongArray.class);
        LongArray longs = (LongArray) result;
        for (int i = 0; i < signed.length; i++) {
            assertThat(longs.getLong(i)).as("index %d", i).isEqualTo(signed[i]);
        }
    }

    // --- broadcast path: child holds a single encoded value, rowCount > 1 ---

    @Test
    void decode_i8_broadcastsSingleValue() {
        // Given a one-element child segment but four logical rows
        long n = 4;

        // When
        Array result = decode(PType.I8, n, encodedBytes((byte) -42));

        // Then every row decodes to the lone value (zip-bomb-safe constant)
        ByteArray bytes = (ByteArray) result;
        for (long i = 0; i < n; i++) {
            assertThat(bytes.getByte(i)).as("index %d", i).isEqualTo((byte) -42);
        }
    }

    @Test
    void decode_i16_broadcastsSingleValue() {
        // Given
        long n = 3;

        // When
        Array result = decode(PType.I16, n, encodedShorts((short) -1000));

        // Then
        ShortArray shorts = (ShortArray) result;
        for (long i = 0; i < n; i++) {
            assertThat(shorts.getShort(i)).as("index %d", i).isEqualTo((short) -1000);
        }
    }

    @Test
    void decode_i32_broadcastsSingleValue() {
        // Given
        long n = 3;

        // When
        Array result = decode(PType.I32, n, encodedInts(-123456));

        // Then
        IntArray ints = (IntArray) result;
        for (long i = 0; i < n; i++) {
            assertThat(ints.getInt(i)).as("index %d", i).isEqualTo(-123456);
        }
    }

    @Test
    void decode_i64_broadcastsSingleValue() {
        // Given
        long n = 3;

        // When
        Array result = decode(PType.I64, n, encodedLongs(-9_000_000_000L));

        // Then
        LongArray longs = (LongArray) result;
        for (long i = 0; i < n; i++) {
            assertThat(longs.getLong(i)).as("index %d", i).isEqualTo(-9_000_000_000L);
        }
    }

    @Test
    void decode_nonPrimitiveDtype_throws() {
        // Given a non-primitive logical type on the context
        ArrayNode node = new ArrayNode(EncodingId.VORTEX_ZIGZAG, null, new ArrayNode[0], new int[]{});
        DecodeContext ctx = new DecodeContext(node, DType.BOOL, 1,
                new MemorySegment[0], REGISTRY, Arena.ofAuto());

        // When / Then
        assertThatThrownBy(() -> SUT.decode(ctx))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("expected primitive dtype");
    }
}
