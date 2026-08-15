package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.DoubleArray;
import io.github.dfa1.vortex.reader.array.FloatArray;
import io.github.dfa1.vortex.reader.array.IntArray;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.StructArray;
import io.github.dfa1.vortex.reader.array.VarBinArray;
import io.github.dfa1.vortex.reader.decode.DecodeContext;
import io.github.dfa1.vortex.core.testing.DTypes;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.reader.ReadRegistry;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import io.github.dfa1.vortex.writer.WriteRegistry;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Stream;

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
            io.github.dfa1.vortex.reader.array.IntArray decoded =
                    (io.github.dfa1.vortex.reader.array.IntArray) registry.decode(decodeCtx);

            // Then
            for (int i = 0; i < values.length; i++) {
                assertThat(decoded.getInt(i)).isEqualTo(values[i]);
            }
        }
    }

    /// Property-based `decode(cascadeEncode(x)) == x` over the full writer codec set.
    ///
    /// Unlike the fixed-array cases above, these run the real encoder-selection +
    /// nesting machinery against seeded-random arrays at every depth 0..3, so a
    /// regression in any codec's cascade interaction (FoR→Bitpacked, Dict→Bitpacked,
    /// ALP→FoR→Bitpacked, RLE/Sparse fallbacks) surfaces as a lossy round-trip.
    /// Floats use ±0-collapse canonicalization to tolerate the ALP family, matching
    /// the convention in `RoundTripPropertyTest`.
    @Nested
    class RoundTripProperty {

        /// Realistic cascade: terminal `Primitive` plus every lossless transform a
        /// primitive column can pick. Order is irrelevant — the compressor competes them.
        private final List<EncodingEncoder> codecs = List.of(
                new AlpEncodingEncoder(), new FrameOfReferenceEncodingEncoder(),
                new DeltaEncodingEncoder(), new ZigZagEncodingEncoder(),
                new DictEncodingEncoder(), new BitpackedEncodingEncoder(),
                new ConstantEncodingEncoder(), new RleEncodingEncoder(),
                new RunEndEncodingEncoder(), new SparseEncodingEncoder(),
                new PcoEncodingEncoder(), new PrimitiveEncodingEncoder());

        private final ReadRegistry readRegistry = ReadRegistry.loadAll();

        private Array roundTrip(DType dtype, Object values, int n, int depth) {
            CascadingCompressor sut = new CascadingCompressor(codecs);
            EncodeContext encodeCtx = EncodeContext.ofDepth(depth, Arena.ofAuto(), toRegistry(codecs));
            EncodeResult result = sut.encode(dtype, values, encodeCtx);
            DecodeContext decodeCtx = DecodeTestHelper.toDecodeContext(result, n, dtype, readRegistry);
            return readRegistry.decode(decodeCtx);
        }

        @ParameterizedTest
        @MethodSource("intArrays")
        void int32_lossless_atEveryDepth(int[] data) {
            for (int depth = 0; depth <= 3; depth++) {
                IntArray decoded = (IntArray) roundTrip(DTypes.I32, data, data.length, depth);
                for (int i = 0; i < data.length; i++) {
                    assertThat(decoded.getInt(i)).as("depth %d index %d", depth, i).isEqualTo(data[i]);
                }
            }
        }

        @ParameterizedTest
        @MethodSource("longArrays")
        void int64_lossless_atEveryDepth(long[] data) {
            for (int depth = 0; depth <= 3; depth++) {
                LongArray decoded = (LongArray) roundTrip(DTypes.I64, data, data.length, depth);
                for (int i = 0; i < data.length; i++) {
                    assertThat(decoded.getLong(i)).as("depth %d index %d", depth, i).isEqualTo(data[i]);
                }
            }
        }

        @ParameterizedTest
        @MethodSource("doubleArrays")
        void f64_bitExact_atEveryDepth(double[] data) {
            for (int depth = 0; depth <= 3; depth++) {
                DoubleArray decoded = (DoubleArray) roundTrip(DTypes.F64, data, data.length, depth);
                for (int i = 0; i < data.length; i++) {
                    assertThat(canon(decoded.getDouble(i))).as("depth %d index %d", depth, i).isEqualTo(canon(data[i]));
                }
            }
        }

        @ParameterizedTest
        @MethodSource("floatArrays")
        void f32_bitExact_atEveryDepth(float[] data) {
            for (int depth = 0; depth <= 3; depth++) {
                FloatArray decoded = (FloatArray) roundTrip(DTypes.F32, data, data.length, depth);
                for (int i = 0; i < data.length; i++) {
                    assertThat(canon(decoded.getFloat(i))).as("depth %d index %d", depth, i).isEqualTo(canon(data[i]));
                }
            }
        }

        // ── ±0-collapse canonicalization (matches the ALP family) ───────────────

        private static long canon(double d) {
            return d == 0.0 ? 0L : Double.doubleToRawLongBits(d);
        }

        private static int canon(float f) {
            return f == 0.0f ? 0 : Float.floatToRawIntBits(f);
        }

        // ── generators (seeded) ─────────────────────────────────────────────────
        //
        // Distributions chosen so the cascade reaches each codec: constant runs
        // (Constant/RLE), low cardinality (Dict), tight clusters (FoR/Bitpacked),
        // monotone (Delta), and full-range (forces Primitive/Pco fallback).

        static Stream<int[]> intArrays() {
            Random rng = new Random(0xCA5CADEL);
            Stream.Builder<int[]> b = Stream.builder();
            b.add(new int[]{0});
            b.add(new int[]{Integer.MIN_VALUE, Integer.MAX_VALUE, 0, -1, 1});
            for (int n = 0; n < 40; n++) {
                int len = 1 + rng.nextInt(300);
                int[] a = new int[len];
                int mode = rng.nextInt(5);
                int constant = rng.nextInt();
                for (int i = 0; i < len; i++) {
                    a[i] = switch (mode) {
                        case 0 -> constant;                       // all-equal: Constant
                        case 1 -> rng.nextInt(8);                 // low cardinality: Dict
                        case 2 -> 1_000_000 + rng.nextInt(256);   // tight cluster: FoR + Bitpacked
                        case 3 -> i;                              // monotone: Delta
                        default -> rng.nextInt();                 // full-range: fallback
                    };
                }
                b.add(a);
            }
            return b.build();
        }

        static Stream<long[]> longArrays() {
            Random rng = new Random(0xCA5CADFL);
            Stream.Builder<long[]> b = Stream.builder();
            b.add(new long[]{0L});
            b.add(new long[]{Long.MIN_VALUE, Long.MAX_VALUE, 0L, -1L, 1L});
            for (int n = 0; n < 40; n++) {
                int len = 1 + rng.nextInt(300);
                long[] a = new long[len];
                int mode = rng.nextInt(5);
                long constant = rng.nextLong();
                for (int i = 0; i < len; i++) {
                    a[i] = switch (mode) {
                        case 0 -> constant;
                        case 1 -> rng.nextInt(8);
                        case 2 -> 1_000_000_000_000L + rng.nextInt(256);
                        case 3 -> i;
                        default -> rng.nextLong();
                    };
                }
                b.add(a);
            }
            return b.build();
        }

        static Stream<double[]> doubleArrays() {
            Random rng = new Random(0xCA5CD64L);
            Stream.Builder<double[]> b = Stream.builder();
            b.add(new double[]{0.0});
            b.add(new double[]{0.0, -0.0, 1.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY,
                    Double.NEGATIVE_INFINITY, Double.MIN_VALUE, Double.MAX_VALUE});
            for (int n = 0; n < 40; n++) {
                int len = 1 + rng.nextInt(300);
                double[] a = new double[len];
                int mode = rng.nextInt(4);
                double constant = rng.nextGaussian();
                for (int i = 0; i < len; i++) {
                    a[i] = switch (mode) {
                        case 0 -> constant;                        // Constant/RLE
                        case 1 -> 100.0 + (i % 50) * 0.01;         // ALP-friendly decimals
                        case 2 -> Double.longBitsToDouble(rng.nextLong()); // arbitrary bits: fallback
                        default -> rng.nextGaussian() * Math.pow(10, rng.nextInt(20) - 10);
                    };
                }
                b.add(a);
            }
            return b.build();
        }

        static Stream<float[]> floatArrays() {
            Random rng = new Random(0xCA5CF32L);
            Stream.Builder<float[]> b = Stream.builder();
            b.add(new float[]{0.0f});
            b.add(new float[]{0.0f, -0.0f, 1.0f, -1.0f, Float.NaN, Float.POSITIVE_INFINITY,
                    Float.NEGATIVE_INFINITY, Float.MIN_VALUE, Float.MAX_VALUE});
            for (int n = 0; n < 40; n++) {
                int len = 1 + rng.nextInt(300);
                float[] a = new float[len];
                int mode = rng.nextInt(4);
                float constant = (float) rng.nextGaussian();
                for (int i = 0; i < len; i++) {
                    a[i] = switch (mode) {
                        case 0 -> constant;
                        case 1 -> 100.0f + (i % 50) * 0.01f;
                        case 2 -> Float.intBitsToFloat(rng.nextInt());
                        default -> (float) (rng.nextGaussian() * Math.pow(10, rng.nextInt(12) - 6));
                    };
                }
                b.add(a);
            }
            return b.build();
        }
    }

    @Nested
    class NullableStructField {

        @Test
        void nullableUtf8Field_routesThroughMaskedEncoding_notCastException() {
            // Given — a struct field carrying NullableData (nullable Utf8), matching Raincloud
            // ultrachat-200k's "messages: LIST<STRUCT{content: string?, role: string?}>" once the
            // outer nullable-list-element fix (ListEncodingEncoder) reaches this struct's own
            // field encoding under cascading. Before this fix, encodeStruct called encodeWithCtx
            // directly on the field's NullableData; for a Utf8 field that lands in
            // VarBinEncodingEncoder, which casts data straight to String[] — ClassCastException.
            // Two fields (not one): StructEncodingDecoder unwraps a single-field struct dtype
            // straight to the field's own array, which would sidestep the StructArray assertion
            // below without exercising anything different from the Leaf-level tests.
            DType.Struct dtype = new DType.Struct(
                    List.of(ColumnName.of("content"), ColumnName.of("role")),
                    List.of(new DType.Utf8(true), new DType.Utf8(false)),
                    false);
            StructData data = new StructData(List.of(
                    new NullableData(new String[]{"a", null, "c"}, new boolean[]{true, false, true}),
                    new String[]{"user", "assistant", "user"}));
            List<EncodingEncoder> codecs = List.of(new VarBinEncodingEncoder());
            CascadingCompressor sut = new CascadingCompressor(codecs);
            EncodeContext encodeCtx = EncodeContext.ofDepth(1, Arena.ofAuto(), toRegistry(codecs));
            ReadRegistry readRegistry = ReadRegistry.loadAll();

            // When
            EncodeResult result = sut.encode(dtype, data, encodeCtx);
            DecodeContext decodeCtx = DecodeTestHelper.toDecodeContext(result, 3, dtype, readRegistry);
            StructArray decoded = (StructArray) readRegistry.decode(decodeCtx);

            // Then
            MaskedArray field = (MaskedArray) decoded.field(0);
            VarBinArray values = (VarBinArray) field.inner();
            assertThat(values.getString(0)).isEqualTo("a");
            assertThat(field.isValid(1)).isFalse();
            assertThat(values.getString(2)).isEqualTo("c");
        }
    }
}
