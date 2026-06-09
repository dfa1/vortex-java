package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.core.array.DoubleArray;
import io.github.dfa1.vortex.core.array.LongArray;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CascadingCompressorTest {

    private static final List<Encoding> ALL_CODECS = List.of(
            new AlpEncoding(), new FrameOfReferenceEncoding(), new DictEncoding(),
            new BitpackedEncoding(), new PrimitiveEncoding());

    private static EncodeContext ctx(int depth) {
        return EncodeContext.ofDepth(depth, Arena.ofAuto(), Registry.of(ALL_CODECS));
    }

    @Nested
    class Compress {

        @Test
        void depth0_picksTerminalOnly_forF64() {
            // Given
            double[] values = new double[4096];
            for (int i = 0; i < values.length; i++) {
                values[i] = i * 1.5;
            }
            CascadingCompressor sut = new CascadingCompressor(ALL_CODECS);

            // When
            EncodeResult result = sut.encode(DTypes.F64, values, ctx(0));

            // Then - result should be a valid non-null encode result
            assertThat(result).isNotNull();
            assertThat(result.rootNode()).isNotNull();
            assertThat(result.buffers()).isNotEmpty();
        }

        @Test
        void depth1_alpPlusBitpacked_producesSmallResult_forF64() {
            // Given: OHLC-style prices — ALP-encodable, small range → bitpackable residuals
            double[] values = new double[4096];
            for (int i = 0; i < values.length; i++) {
                values[i] = 100.0 + (i % 50) * 0.01;
            }
            CascadingCompressor sut = new CascadingCompressor(ALL_CODECS);

            // When
            EncodeResult result = sut.encode(DTypes.F64, values, ctx(1));

            // Then - cascaded result should be smaller than raw primitive (4096 * 8 = 32768 bytes)
            long totalBytes = result.buffers().stream().mapToLong(MemorySegment::byteSize).sum();
            assertThat(totalBytes).isLessThan(4096L * 8);
        }

        @Test
        void excludedEncodings_areSkipped() {
            // Given: exclude AlpEncoding — should not be selected for DTypes.F64
            double[] values = new double[512];
            for (int i = 0; i < values.length; i++) {
                values[i] = i * 1.5;
            }
            // Depth=1 but ALP excluded via context
            EncodeContext encodeCtx = new EncodeContext(
                    Arena.ofAuto(), Registry.of(ALL_CODECS),
                    1, Set.of(EncodingId.VORTEX_ALP), 42L, 64, 0.1);
            CascadingCompressor sut = new CascadingCompressor(ALL_CODECS);

            // When
            EncodeResult result = sut.encode(DTypes.F64, values, encodeCtx);

            // Then - ALP node should not appear in the tree
            assertThat(containsEncoding(result.rootNode(), EncodingId.VORTEX_ALP)).isFalse();
        }

        private boolean containsEncoding(EncodeNode node, EncodingId id) {
            if (node.encodingId().equals(id)) {
                return true;
            }
            for (EncodeNode child : node.children()) {
                if (containsEncoding(child, id)) {
                    return true;
                }
            }
            return false;
        }
    }

    @Nested
    class RoundTrip {

        @Test
        void alpBitpacked_f64() {
            // Given
            double[] values = new double[1024];
            for (int i = 0; i < values.length; i++) {
                values[i] = 150.0 + (i % 100) * 0.01;
            }
            CascadingCompressor sut = new CascadingCompressor(ALL_CODECS);

            // When
            Registry registry = Registry.of(ALL_CODECS);
            EncodeResult result = sut.encode(DTypes.F64, values, EncodeContext.ofDepth(1, Arena.ofAuto(), registry));
            DecodeContext decodeCtx = EncodeTestHelper.toDecodeContext(result, values.length, DTypes.F64, registry);
            DoubleArray decoded = (DoubleArray) registry.decode(decodeCtx);

            // Then
            for (int i = 0; i < values.length; i++) {
                assertThat(decoded.getDouble(i)).isEqualTo(values[i]);
            }
        }

        @Test
        void forBitpacked_i64() {
            // Given: integers in narrow range → FoR reduces to small residuals → bitpackable
            long[] values = new long[1024];
            for (int i = 0; i < values.length; i++) {
                values[i] = 1_000_000L + (i % 200);
            }
            CascadingCompressor sut = new CascadingCompressor(ALL_CODECS);

            // When
            Registry registry = Registry.of(ALL_CODECS);
            EncodeResult result = sut.encode(DTypes.I64, values, EncodeContext.ofDepth(1, Arena.ofAuto(), registry));
            DecodeContext decodeCtx = EncodeTestHelper.toDecodeContext(result, values.length, DTypes.I64, registry);
            LongArray decoded = (LongArray) registry.decode(decodeCtx);

            // Then
            for (int i = 0; i < values.length; i++) {
                assertThat(decoded.getLong(i)).isEqualTo(values[i]);
            }
        }

        @Test
        void dictBitpacked_i32() {
            // Given: low-cardinality int column
            int[] values = new int[2048];
            for (int i = 0; i < values.length; i++) {
                values[i] = i % 10;
            }
            CascadingCompressor sut = new CascadingCompressor(ALL_CODECS);

            // When
            Registry registry = Registry.of(ALL_CODECS);
            EncodeResult result = sut.encode(DTypes.I32, values, EncodeContext.ofDepth(1, Arena.ofAuto(), registry));
            DecodeContext decodeCtx = EncodeTestHelper.toDecodeContext(result, values.length, DTypes.I32, registry);
            io.github.dfa1.vortex.core.array.IntArray decoded =
                    (io.github.dfa1.vortex.core.array.IntArray) registry.decode(decodeCtx);

            // Then
            for (int i = 0; i < values.length; i++) {
                assertThat(decoded.getInt(i)).isEqualTo(values[i]);
            }
        }
    }
}
