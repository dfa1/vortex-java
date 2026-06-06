package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.core.array.VarBinArray;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// Property: decode reconstructs every string value exactly,
/// regardless of inlined vs reference layout.
class VarBinViewEncodingTest {

    @Nested
    class Encode {

        @Test
        void accepts_utf8_true() {
            // Given
            var sut = new VarBinViewEncoding();

            // When / Then
            assertThat(sut.accepts(DTypes.UTF8)).isTrue();
        }

        @Test
        void accepts_binary_true() {
            // Given
            var sut = new VarBinViewEncoding();

            // When / Then
            assertThat(sut.accepts(DTypes.BINARY)).isTrue();
        }

        @Test
        void accepts_primitive_false() {
            // Given
            var sut = new VarBinViewEncoding();

            // When / Then
            assertThat(sut.accepts(DTypes.I32)).isFalse();
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("io.github.dfa1.vortex.encoding.VarBinViewEncodingTest$Decode#stringArrays")
        void encode_thenDecode_roundtripsAllStrings(String name, String[] values) {
            // Given
            var sut = new VarBinViewEncoding();
            Arena arena = Arena.ofAuto();

            // When
            EncodeResult result = sut.encode(DTypes.UTF8, values, EncodeTestHelper.testCtx());
            MemorySegment[] bufs = result.buffers().toArray(MemorySegment[]::new);
            ArrayNode node = ArrayNode.of(
                    EncodingId.VORTEX_VARBINVIEW, null, new ArrayNode[0],
                    result.rootNode().bufferIndices(), null);
            EncodingRegistry registry = TestRegistry.of(sut);
            DecodeContext ctx = new DecodeContext(node, DTypes.UTF8, values.length, bufs, registry, arena);
            var decoded = (VarBinArray) sut.decode(ctx);

            // Then
            assertThat(decoded.length()).isEqualTo(values.length);
            for (int i = 0; i < values.length; i++) {
                assertThat(decoded.getString(i)).as("index %d", i).isEqualTo(values[i]);
            }
        }
    }

    @Nested
    class Decode {

        static Stream<Arguments> stringArrays() {
            return Stream.of(
                    Arguments.of("empty-array", new String[0]),
                    Arguments.of("single-empty-string", new String[]{""}),
                    Arguments.of("short-strings", new String[]{"hi", "ok", "no"}),
                    Arguments.of("exactly-12-bytes", new String[]{"123456789012"}),       // max inlined
                    Arguments.of("just-over-12-bytes", new String[]{"1234567890123"}),      // min reference
                    Arguments.of("long-strings", new String[]{"the quick brown fox jumps over the lazy dog"}),
                    Arguments.of("mixed-lengths", new String[]{"a", "hello", "this is a longer string than twelve"}),
                    Arguments.of("repeated-short", repeat("ab", 50)),
                    Arguments.of("repeated-long", repeat("this exceeds twelve bytes easily", 10)),
                    Arguments.of("all-empty", new String[]{"", "", "", ""}),
                    Arguments.of("unicode", new String[]{"héllo", "wörld", "こんにちは"})
            );
        }

        private static String[] repeat(String s, int n) {
            String[] arr = new String[n];
            java.util.Arrays.fill(arr, s);
            return arr;
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("io.github.dfa1.vortex.encoding.VarBinViewEncodingTest$Decode#stringArrays")
        void decode_roundtrip_returnsAllStrings(String name, String[] values) {
            // Given
            Arena arena = Arena.ofAuto();
            long n = values.length;

            // Encode all long strings into one data buffer
            byte[][] bytesArr = new byte[values.length][];
            int dataBufLen = 0;
            for (int i = 0; i < values.length; i++) {
                bytesArr[i] = values[i].getBytes(StandardCharsets.UTF_8);
                if (bytesArr[i].length > 12) {
                    dataBufLen += bytesArr[i].length;
                }
            }

            MemorySegment dataBuf = arena.allocate(dataBufLen > 0 ? dataBufLen : 1);
            MemorySegment views = arena.allocate(n > 0 ? n * 16 : 1);

            int dataOffset = 0;
            for (int i = 0; i < values.length; i++) {
                byte[] b = bytesArr[i];
                long viewOff = (long) i * 16;
                views.set(PTypeIO.LE_INT, viewOff, b.length);
                if (b.length <= 12) {
                    // inlined: data at viewOff+4
                    MemorySegment.copy(MemorySegment.ofArray(b), 0, views, viewOff + 4, b.length);
                } else {
                    // reference: buffer_index=0, offset=dataOffset
                    views.set(PTypeIO.LE_INT, viewOff + 8, 0);           // buffer_index
                    views.set(PTypeIO.LE_INT, viewOff + 12, dataOffset);  // offset
                    MemorySegment.copy(MemorySegment.ofArray(b), 0, dataBuf, dataOffset, b.length);
                    dataOffset += b.length;
                }
            }

            // bufferIndices: [0=dataBuf, 1=views] when data buffer needed, else just [0=views]
            int[] bufIndices;
            MemorySegment[] segBufs;
            if (dataBufLen > 0) {
                bufIndices = new int[]{0, 1};
                segBufs = new MemorySegment[]{dataBuf, views};
            } else {
                bufIndices = new int[]{0};
                segBufs = new MemorySegment[]{views};
            }

            ArrayNode node = ArrayNode.of(EncodingId.VORTEX_VARBINVIEW, null,
                    new ArrayNode[0], bufIndices, null);

            EncodingRegistry registry = TestRegistry.of(new VarBinViewEncoding());

            DecodeContext ctx = new DecodeContext(node, DTypes.UTF8, n, segBufs, registry, arena);
            var sut = new VarBinViewEncoding();

            // When
            var result = sut.decode(ctx);

            // Then
            assertThat(result).isInstanceOf(VarBinArray.class);
            assertThat(result.length()).isEqualTo(n);
            for (int i = 0; i < values.length; i++) {
                VarBinArray varBinArray = (VarBinArray) result;
                String decoded = varBinArray.getString(i);
                assertThat(decoded).as("index %d", i).isEqualTo(values[i]);
            }
        }
    }

}
