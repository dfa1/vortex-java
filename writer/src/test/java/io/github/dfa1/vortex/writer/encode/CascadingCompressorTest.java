package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.array.DoubleArray;
import io.github.dfa1.vortex.core.array.LongArray;
import io.github.dfa1.vortex.reader.decode.DecodeContext;
import io.github.dfa1.vortex.encoding.DTypes;
import io.github.dfa1.vortex.encoding.EncodingId;
import io.github.dfa1.vortex.reader.ReadRegistry;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.github.dfa1.vortex.writer.WriteRegistry;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CascadingCompressorTest {

    private static final List<EncodingEncoder> ALL_CODECS = List.of(
            new AlpEncodingEncoder(), new FrameOfReferenceEncodingEncoder(), new DictEncodingEncoder(),
            new BitpackedEncodingEncoder(), new PrimitiveEncodingEncoder());

    private static WriteRegistry toRegistry(List<EncodingEncoder> codecs) {
        WriteRegistry.Builder b = WriteRegistry.builder();
        for (EncodingEncoder enc : codecs) {
            b.register(enc);
        }
        return b.build();
    }

    private static EncodeContext ctx(int depth) {
        return EncodeContext.ofDepth(depth, Arena.ofAuto(), toRegistry(ALL_CODECS));
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

            // Then
            assertThat(result).isNotNull();
            assertThat(result.rootNode()).isNotNull();
            assertThat(result.buffers()).isNotEmpty();
        }

        @Test
        void depth1_alpPlusBitpacked_producesSmallResult_forF64() {
            // Given
            double[] values = new double[4096];
            for (int i = 0; i < values.length; i++) {
                values[i] = 100.0 + (i % 50) * 0.01;
            }
            CascadingCompressor sut = new CascadingCompressor(ALL_CODECS);

            // When
            EncodeResult result = sut.encode(DTypes.F64, values, ctx(1));

            // Then
            long totalBytes = result.buffers().stream().mapToLong(MemorySegment::byteSize).sum();
            assertThat(totalBytes).isLessThan(4096L * 8);
        }

        @Test
        void excludedEncodings_areSkipped() {
            // Given
            double[] values = new double[512];
            for (int i = 0; i < values.length; i++) {
                values[i] = i * 1.5;
            }
            EncodeContext encodeCtx = new EncodeContext(
                    Arena.ofAuto(), toRegistry(ALL_CODECS),
                    1, Set.of(EncodingId.VORTEX_ALP), 42L, 64, 0.1);
            CascadingCompressor sut = new CascadingCompressor(ALL_CODECS);

            // When
            EncodeResult result = sut.encode(DTypes.F64, values, encodeCtx);

            // Then
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
            ReadRegistry registry = ReadRegistry.loadAll();
            EncodeResult result = sut.encode(DTypes.F64, values, EncodeContext.ofDepth(1, Arena.ofAuto(), toRegistry(ALL_CODECS)));
            DecodeContext decodeCtx = DecodeTestHelper.toDecodeContext(result, values.length, DTypes.F64, registry);
            DoubleArray decoded = (DoubleArray) registry.decode(decodeCtx);

            // Then
            for (int i = 0; i < values.length; i++) {
                assertThat(decoded.getDouble(i)).isEqualTo(values[i]);
            }
        }

        @Test
        void forBitpacked_i64() {
            // Given
            long[] values = new long[1024];
            for (int i = 0; i < values.length; i++) {
                values[i] = 1_000_000L + (i % 200);
            }
            CascadingCompressor sut = new CascadingCompressor(ALL_CODECS);

            // When
            ReadRegistry registry = ReadRegistry.loadAll();
            EncodeResult result = sut.encode(DTypes.I64, values, EncodeContext.ofDepth(1, Arena.ofAuto(), toRegistry(ALL_CODECS)));
            DecodeContext decodeCtx = DecodeTestHelper.toDecodeContext(result, values.length, DTypes.I64, registry);
            LongArray decoded = (LongArray) registry.decode(decodeCtx);

            // Then
            for (int i = 0; i < values.length; i++) {
                assertThat(decoded.getLong(i)).isEqualTo(values[i]);
            }
        }

        @Test
        void dictBitpacked_i32() {
            // Given
            int[] values = new int[2048];
            for (int i = 0; i < values.length; i++) {
                values[i] = i % 10;
            }
            CascadingCompressor sut = new CascadingCompressor(ALL_CODECS);

            // When
            ReadRegistry registry = ReadRegistry.loadAll();
            EncodeResult result = sut.encode(DTypes.I32, values, EncodeContext.ofDepth(1, Arena.ofAuto(), toRegistry(ALL_CODECS)));
            DecodeContext decodeCtx = DecodeTestHelper.toDecodeContext(result, values.length, DTypes.I32, registry);
            io.github.dfa1.vortex.core.array.IntArray decoded =
                    (io.github.dfa1.vortex.core.array.IntArray) registry.decode(decodeCtx);

            // Then
            for (int i = 0; i < values.length; i++) {
                assertThat(decoded.getInt(i)).isEqualTo(values[i]);
            }
        }
    }
}
