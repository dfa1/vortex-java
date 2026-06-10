package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.proto.PcoChunkInfo;
import io.github.dfa1.vortex.proto.PcoMetadata;
import io.github.dfa1.vortex.proto.PcoPageInfo;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.core.array.LongArray;
import io.github.dfa1.vortex.core.array.MaskedArray;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.util.Random;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PcoEncodingTest {

    private static ByteBuffer validMetaBuffer() {
        PcoMetadata meta = new PcoMetadata(new byte[]{PcoEncoding.PCO_FORMAT_MAJOR, PcoEncoding.PCO_FORMAT_MINOR}, java.util.List.of());
        return ByteBuffer.wrap(meta.encode());
    }

    private static DecodeContext ctxWith(ByteBuffer meta, DType dtype, long rowCount, MemorySegment[] buffers) {
        ArrayNode node = ArrayNode.of(EncodingId.VORTEX_PCO, meta, new ArrayNode[0],
                bufferIndices(buffers.length), null);
        return new DecodeContext(node, dtype, rowCount, buffers, Registry.empty(), Arena.ofAuto());
    }

    /// Build a nullable DecodeContext: validity buffer at index 0, pco buffers at indices 1..N.
    /// Validity is a bit-packed Bool array (LSB-first, 1=valid).
    private static DecodeContext ctxWithValidity(ByteBuffer meta, DType dtype, long rowCount,
            MemorySegment validityBuf, MemorySegment[] pcoBuffers) {
        MemorySegment[] allBuffers = new MemorySegment[1 + pcoBuffers.length];
        allBuffers[0] = validityBuf;
        System.arraycopy(pcoBuffers, 0, allBuffers, 1, pcoBuffers.length);

        ArrayNode validityNode = ArrayNode.of(EncodingId.VORTEX_BOOL, null, new ArrayNode[0],
                new int[]{0}, null);

        int[] pcoBufferIndices = new int[pcoBuffers.length];
        for (int i = 0; i < pcoBuffers.length; i++) {
            pcoBufferIndices[i] = i + 1;
        }
        ArrayNode pcoNode = ArrayNode.of(EncodingId.VORTEX_PCO, meta, new ArrayNode[]{validityNode},
                pcoBufferIndices, null);

        Registry registry = TestRegistry.of(new BoolEncoding());
        return new DecodeContext(pcoNode, dtype, rowCount, allBuffers, registry, Arena.ofAuto());
    }

    private static int[] bufferIndices(int n) {
        int[] idx = new int[n];
        for (int i = 0; i < n; i++) {
            idx[i] = i;
        }
        return idx;
    }

    private static MemorySegment segmentOf(byte... bytes) {
        MemorySegment seg = Arena.ofAuto().allocate(bytes.length);
        for (int i = 0; i < bytes.length; i++) {
            seg.set(ValueLayout.JAVA_BYTE, i, bytes[i]);
        }
        return seg;
    }

    /// Build a PcoMetadata proto with one chunk containing one page of {@code nValues} values.
    private static ByteBuffer metaWithOneChunk(int nValues) {
        PcoMetadata meta = new PcoMetadata(
                new byte[]{PcoEncoding.PCO_FORMAT_MAJOR, PcoEncoding.PCO_FORMAT_MINOR},
                java.util.List.of(new PcoChunkInfo(java.util.List.of(new PcoPageInfo(nValues)))));
        return ByteBuffer.wrap(meta.encode());
    }

    /// Chunk-meta bytes for Classic mode, Consecutive delta at {@code order}, ansSizeLog=0, nBins=0.
    ///
    /// Bit layout (LSB-first per byte):
    /// byte0: mode_nibble=0, delta_nibble=1
    /// byte1: order (3b), secondary_uses_delta=0 (1b), ansSizeLog=0 (4b)
    /// byte2–3: nBins=0 (15b), align padding
    private static MemorySegment chunkMetaConsecutive(int order) {
        return segmentOf(
                (byte) 0x10,        // mode=0, delta_variant=1
                (byte) order,       // order[2:0], secondary=0, ansSizeLog=0  (order ≤ 7)
                (byte) 0x00,        // nBins bits16-23 = 0
                (byte) 0x00         // nBins bits24-30 = 0, padding
        );
    }

    /// Page bytes: {@code order} LE-U64 moments, then 4 zero ANS-state slots (0 bits each).
    private static MemorySegment pageWithMoments(long... moments) {
        byte[] buf = new byte[moments.length * Long.BYTES];
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(buf).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        for (long m : moments) {
            bb.putLong(m);
        }
        return segmentOf(buf);
    }

    /// Build chunk meta for Classic mode + Conv1 delta by packing bits LSB-first.
    ///
    /// Layout: mode(4b)=0, delta(4b)=3, quantization(5b), bias_latent(64b),
    /// order-1(5b), weights[order*32b], ansSizeLog(4b)=0, nBins(15b)=0, align.
    /// bias_latent = bias ^ Long.MIN_VALUE; each weight_latent = weight ^ 0x80000000L.
    private static MemorySegment chunkMetaConv1(int quantization, long biasLatent,
            int order, long[] weightLatents) {
        java.util.BitSet bits = new java.util.BitSet();
        int pos = 0;
        // mode nibble = 0 (Classic)
        pos += 4;
        // delta nibble = 3 (Conv1): bits = 0b0011
        bits.set(pos);
        bits.set(pos + 1);
        pos += 4;
        // quantization (5 bits)
        for (int i = 0; i < 5; i++) {
            if (((quantization >> i) & 1) != 0) {
                bits.set(pos);
            }
            pos++;
        }
        // bias latent (64 bits, LSB first)
        for (int i = 0; i < 64; i++) {
            if (((biasLatent >> i) & 1L) != 0L) {
                bits.set(pos);
            }
            pos++;
        }
        // order-1 (5 bits)
        for (int i = 0; i < 5; i++) {
            if ((((order - 1) >> i) & 1) != 0) {
                bits.set(pos);
            }
            pos++;
        }
        // weight latents (order × 32 bits)
        for (long wl : weightLatents) {
            for (int i = 0; i < 32; i++) {
                if (((wl >> i) & 1L) != 0L) {
                    bits.set(pos);
                }
                pos++;
            }
        }
        // ansSizeLog (4 bits) = 0
        pos += 4;
        // nBins (15 bits) = 0
        pos += 15;
        int byteLen = (pos + 7) / 8;
        byte[] buf = new byte[byteLen];
        for (int i = 0; i < pos; i++) {
            if (bits.get(i)) {
                buf[i / 8] |= (byte) (1 << (i % 8));
            }
        }
        return segmentOf(buf);
    }

    /// Chunk-meta bytes for Classic mode + Lookback delta with windowNLog=1 (windowN=2), stateNLog=0 (stateN=1),
    /// deltaAnsSizeLog=0, primaryAnsSizeLog=0, no bins.
    ///
    /// Bit layout:
    /// byte0: mode=0[3:0], delta=2[7:4] → 0x20
    /// bytes 1-6: windowNLog-1(5b)=0, stateNLog(4b)=0, secondary(1b)=0,
    ///            deltaAnsSizeLog(4b)=0, nDeltaBins(15b)=0,
    ///            primaryAnsSizeLog(4b)=0, nBins(15b)=0, align → all 0x00
    private static MemorySegment chunkMetaLookback() {
        return segmentOf((byte) 0x20, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00);
    }

    /// Page bytes for Lookback with stateN=1, U64, deltaAnsSizeLog=0, primaryAnsSizeLog=0.
    /// Format: 8 bytes (one 64-bit initial state). No ANS state bits (sizeLog=0). No decoded bits.
    private static MemorySegment lookbackPage(long initialState) {
        byte[] buf = new byte[Long.BYTES];
        java.nio.ByteBuffer.wrap(buf).order(java.nio.ByteOrder.LITTLE_ENDIAN).putLong(initialState);
        return segmentOf(buf);
    }

    @Nested
    class EncodingIdTest {

        @Test
        void encodingId_isVortexPco() {
            // Given / When / Then
            assertThat(new PcoEncoding().encodingId()).isEqualTo(EncodingId.VORTEX_PCO);
        }
    }

    @Nested
    class Encode {

        @Test
        void encode_throwsVortexException() {
            // Given
            var sut = new PcoEncoding();
            DType dtype = new DType.Primitive(PType.I64, false);

            // When / Then
            assertThatThrownBy(() -> sut.encode(dtype, new long[]{1L, 2L, 3L}, EncodeTestHelper.testCtx()))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("not implemented");
        }
    }

    @Nested
    class Decode {

        @Test
        void decode_nullMetadata_throwsMissingMeta() {
            // Given
            var sut = new PcoEncoding();
            DecodeContext ctx = ctxWith(null, new DType.Primitive(PType.I64, false), 0, new MemorySegment[0]);

            // When / Then
            assertThatThrownBy(() -> sut.decode(ctx))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("missing PcoMetadata");
        }

        @Test
        void decode_invalidHeaderVersion_throwsUnsupported() {
            // Given
            var sut = new PcoEncoding();
            PcoMetadata meta = new PcoMetadata(new byte[]{0x03, 0x00}, java.util.List.of());
            DecodeContext ctx = ctxWith(ByteBuffer.wrap(meta.encode()),
                    new DType.Primitive(PType.I64, false), 0, new MemorySegment[0]);

            // When / Then
            assertThatThrownBy(() -> sut.decode(ctx))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("unsupported pco format version 03.00");
        }

        @Test
        void decode_nonPrimitiveDtype_throws() {
            // Given
            var sut = new PcoEncoding();
            DecodeContext ctx = ctxWith(validMetaBuffer(), new DType.Utf8(false), 0, new MemorySegment[0]);

            // When / Then
            assertThatThrownBy(() -> sut.decode(ctx))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("Primitive dtype");
        }

        @Test
        void decode_unsupportedPtype_throws() {
            // Given — F16 not supported by pco
            var sut = new PcoEncoding();
            DecodeContext ctx = ctxWith(validMetaBuffer(), new DType.Primitive(PType.F16, false), 0,
                    new MemorySegment[0]);

            // When / Then
            assertThatThrownBy(() -> sut.decode(ctx))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("unsupported ptype");
        }

        @ParameterizedTest
        @EnumSource(value = PType.class, names = {"I16", "U16", "I32", "U32", "F32", "I64", "U64", "F64"})
        void decode_zeroChunks_returnsEmptyArray(PType ptype) {
            // Given — valid metadata with 0 chunks, 0 rows, any supported ptype
            var sut = new PcoEncoding();
            DecodeContext ctx = ctxWith(validMetaBuffer(), new DType.Primitive(ptype, false), 0,
                    new MemorySegment[0]);

            // When
            var result = sut.decode(ctx);

            // Then
            assertThat(result.length()).isZero();
        }

        @Test
        void decode_consecutiveDelta_order1_singleValue_decodes() {
            // Given — U64 sequence [42] encoded with Classic mode, Consecutive delta order=1.
            // With pageN=1 and order=1, decodedN=0: the single output value is the moment itself.
            var sut = new PcoEncoding();
            DecodeContext ctx = ctxWith(
                    metaWithOneChunk(1),
                    new DType.Primitive(PType.U64, false),
                    1,
                    new MemorySegment[]{chunkMetaConsecutive(1), pageWithMoments(42L)});

            // When
            var result = sut.decode(ctx);

            // Then
            assertThat(result.length()).isEqualTo(1);
            assertThat(((LongArray) result).getLong(0)).isEqualTo(42L);
        }

        @Test
        void decode_consecutiveDelta_order2_twoValues_decodes() {
            // Given — U64 sequence [10, 17] encoded with Consecutive delta order=2.
            // With pageN=2, order=2: decodedN=0; moments=[m0=10, m1=delta1=7].
            // Expected reconstruction: [m0, m0+m1] = [10, 17].
            var sut = new PcoEncoding();
            DecodeContext ctx = ctxWith(
                    metaWithOneChunk(2),
                    new DType.Primitive(PType.U64, false),
                    2,
                    new MemorySegment[]{chunkMetaConsecutive(2), pageWithMoments(10L, 7L)});

            // When
            var result = sut.decode(ctx);

            // Then
            assertThat(result.length()).isEqualTo(2);
            assertThat(((LongArray) result).getLong(0)).isEqualTo(10L);
            assertThat(((LongArray) result).getLong(1)).isEqualTo(17L);
        }

        @Test
        void decode_multiPage_singleChunk_decodes() {
            // Given — 1 chunk, 2 pages each containing 1 value (Consecutive order=1).
            // buffers: [chunkMeta, page0, page1]; page0 moment=10→value 10, page1 moment=20→value 20.
            var sut = new PcoEncoding();
            PcoMetadata meta = new PcoMetadata(
                    new byte[]{PcoEncoding.PCO_FORMAT_MAJOR, PcoEncoding.PCO_FORMAT_MINOR},
                    java.util.List.of(new PcoChunkInfo(java.util.List.of(new PcoPageInfo(1), new PcoPageInfo(1)))));
            DecodeContext ctx = ctxWith(
                    ByteBuffer.wrap(meta.encode()),
                    new DType.Primitive(PType.U64, false),
                    2,
                    new MemorySegment[]{chunkMetaConsecutive(1), pageWithMoments(10L), pageWithMoments(20L)});

            // When
            var result = sut.decode(ctx);

            // Then
            assertThat(result.length()).isEqualTo(2);
            assertThat(((LongArray) result).getLong(0)).isEqualTo(10L);
            assertThat(((LongArray) result).getLong(1)).isEqualTo(20L);
        }

        @Test
        void decode_multiChunk_decodes() {
            // Given — 2 chunks each with 1 page containing 1 value (Consecutive order=1).
            // buffers: [chunkMeta0, page0, chunkMeta1, page1]; values=[100, 200].
            var sut = new PcoEncoding();
            PcoMetadata meta = new PcoMetadata(
                    new byte[]{PcoEncoding.PCO_FORMAT_MAJOR, PcoEncoding.PCO_FORMAT_MINOR},
                    java.util.List.of(
                            new PcoChunkInfo(java.util.List.of(new PcoPageInfo(1))),
                            new PcoChunkInfo(java.util.List.of(new PcoPageInfo(1)))));
            DecodeContext ctx = ctxWith(
                    ByteBuffer.wrap(meta.encode()),
                    new DType.Primitive(PType.U64, false),
                    2,
                    new MemorySegment[]{
                            chunkMetaConsecutive(1), pageWithMoments(100L),
                            chunkMetaConsecutive(1), pageWithMoments(200L)});

            // When
            var result = sut.decode(ctx);

            // Then
            assertThat(result.length()).isEqualTo(2);
            assertThat(((LongArray) result).getLong(0)).isEqualTo(100L);
            assertThat(((LongArray) result).getLong(1)).isEqualTo(200L);
        }
    }

    @Nested
    class DecodeNullable {

        @Test
        void decode_nullable_someNulls_scattersCorrectly() {
            // Given — U64 sequence: 3 total rows, validity=[true,false,true], valid values=[100,200].
            // Validity bits LSB-first: bit0=1, bit1=0, bit2=1 → byte 0x05.
            // Pco encodes only valid values: 1 chunk, 2 pages of nValues=1 (Consecutive order=1).
            var sut = new PcoEncoding();
            PcoMetadata meta = new PcoMetadata(
                    new byte[]{PcoEncoding.PCO_FORMAT_MAJOR, PcoEncoding.PCO_FORMAT_MINOR},
                    java.util.List.of(new PcoChunkInfo(java.util.List.of(new PcoPageInfo(1), new PcoPageInfo(1)))));
            MemorySegment validityBuf = segmentOf((byte) 0x05); // bits: 1,0,1
            DecodeContext ctx = ctxWithValidity(
                    ByteBuffer.wrap(meta.encode()),
                    new DType.Primitive(PType.U64, true),
                    3,
                    validityBuf,
                    new MemorySegment[]{chunkMetaConsecutive(1), pageWithMoments(100L), pageWithMoments(200L)});

            // When
            var result = sut.decode(ctx);

            // Then — MaskedArray with 3 slots; positions 0 and 2 valid, position 1 null
            assertThat(result).isInstanceOf(MaskedArray.class);
            assertThat(result.length()).isEqualTo(3);
            MaskedArray masked = (MaskedArray) result;
            assertThat(masked.isValid(0)).isTrue();
            assertThat(masked.isValid(1)).isFalse();
            assertThat(masked.isValid(2)).isTrue();
            assertThat(((LongArray) masked.inner()).getLong(0)).isEqualTo(100L);
            assertThat(((LongArray) masked.inner()).getLong(2)).isEqualTo(200L);
        }

        @Test
        void decode_nullable_allNull_returnsAllZeroed() {
            // Given — 2 total rows, validity=[false,false], validCount=0. Pco has 0 chunks.
            // Validity bits LSB-first: 0x00.
            var sut = new PcoEncoding();
            MemorySegment validityBuf = segmentOf((byte) 0x00);
            DecodeContext ctx = ctxWithValidity(
                    validMetaBuffer(),
                    new DType.Primitive(PType.U64, true),
                    2,
                    validityBuf,
                    new MemorySegment[0]);

            // When
            var result = sut.decode(ctx);

            // Then — MaskedArray, length 2, both null, values zeroed
            assertThat(result).isInstanceOf(MaskedArray.class);
            assertThat(result.length()).isEqualTo(2);
            MaskedArray masked = (MaskedArray) result;
            assertThat(masked.isValid(0)).isFalse();
            assertThat(masked.isValid(1)).isFalse();
            assertThat(((LongArray) masked.inner()).getLong(0)).isZero();
            assertThat(((LongArray) masked.inner()).getLong(1)).isZero();
        }

        @Test
        void decode_nullable_allValid_returnsMaskedWithAllValues() {
            // Given — 2 total rows, validity=[true,true], valid values=[10,20].
            // Validity bits: 0x03.
            var sut = new PcoEncoding();
            MemorySegment validityBuf = segmentOf((byte) 0x03); // bits: 1,1
            DecodeContext ctx = ctxWithValidity(
                    metaWithOneChunk(2),
                    new DType.Primitive(PType.U64, true),
                    2,
                    validityBuf,
                    new MemorySegment[]{chunkMetaConsecutive(2), pageWithMoments(10L, 10L)});

            // When
            var result = sut.decode(ctx);

            // Then — MaskedArray, all valid, values [10, 20]
            assertThat(result).isInstanceOf(MaskedArray.class);
            assertThat(result.length()).isEqualTo(2);
            MaskedArray masked = (MaskedArray) result;
            assertThat(masked.isValid(0)).isTrue();
            assertThat(masked.isValid(1)).isTrue();
            assertThat(((LongArray) masked.inner()).getLong(0)).isEqualTo(10L);
            assertThat(((LongArray) masked.inner()).getLong(1)).isEqualTo(20L);
        }
    }

    @Nested
    class DecodeConv1 {

        @Test
        void decode_conv1_order1_zeroPrediction_statePassedThrough() {
            // Given — I32, pageN=2, order=1, bias=0, weight=0 → prediction always 0.
            // State raw = value ^ 0x80000000: for value=5, state_raw=0x80000005.
            // Residual from degenerate tANS=0 → decoded = 0 ^ mid_i32(0x80000000) = 0x80000000
            //   → fromLatentOrdered(0x80000000, I32) = 0x80000000 ^ 0x80000000 = 0.
            // Expected output: [5, 0].
            var sut = new PcoEncoding();
            long biasLatent = Long.MIN_VALUE;    // encodes bias=0: raw ^ MIN_VALUE = 0
            long weightLatent = 0x80000000L;     // encodes weight=0: (int)(raw ^ 0x80000000) = 0
            MemorySegment chunkMeta = chunkMetaConv1(0, biasLatent, 1, new long[]{weightLatent});
            // Page: 1 × 32-bit state = 0x80000005 (encodes value 5), no residual bits.
            MemorySegment page = segmentOf((byte) 0x05, (byte) 0x00, (byte) 0x00, (byte) 0x80);
            DecodeContext ctx = ctxWith(
                    metaWithOneChunk(2),
                    new DType.Primitive(PType.I32, false),
                    2,
                    new MemorySegment[]{chunkMeta, page});

            // When
            var result = sut.decode(ctx);

            // Then
            assertThat(result.length()).isEqualTo(2);
            assertThat(((io.github.dfa1.vortex.core.array.IntArray) result).getInt(0)).isEqualTo(5);
            assertThat(((io.github.dfa1.vortex.core.array.IntArray) result).getInt(1)).isZero();
        }
    }

    @Nested
    class DecodeLookback {

        @Test
        void decode_lookback_corruptIndexZero_throwsVortexException() {
            // Given — Classic+Lookback, windowN=2, stateN=1, degenerate ANS (0 bins).
            // Degenerate tANS always outputs lower=0; lb=0 is out of [1, windowN=2].
            // pageN=2: stateN=1 initial value + 1 decoded value with lb=0.
            var sut = new PcoEncoding();
            DecodeContext ctx = ctxWith(
                    metaWithOneChunk(2),
                    new DType.Primitive(PType.U64, false),
                    2,
                    new MemorySegment[]{chunkMetaLookback(), lookbackPage(0L)});

            // When / Then
            assertThatThrownBy(() -> sut.decode(ctx))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("corrupt lookback index 0");
        }

        @Test
        void decode_lookback_stateNExceedsPageN_throwsVortexException() {
            // Given — stateN=2 (stateNLog=1) but pageN=1 → decodeN = 1-2 = -1 → corrupt.
            // chunkMeta bit layout (all LE, LSB-first):
            //   byte0: mode=0[3:0], delta=2[7:4] → 0x20
            //   byte1: windowNLog-1(5b)=0, stateNLog[0](1b)=1 → 0x20
            //   byte2: stateNLog[1..3](3b)=0, secondary(1b)=0, deltaAnsSizeLog(4b)=0 → 0x00
            //   bytes3-6: nDeltaBins(15b)=0, primaryAnsSizeLog(4b)=0, nBins(15b)=0 → 0x00
            var sut = new PcoEncoding();
            MemorySegment chunkMeta = segmentOf(
                    (byte) 0x20, (byte) 0x20, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00);
            DecodeContext ctx = ctxWith(
                    metaWithOneChunk(1),
                    new DType.Primitive(PType.U64, false),
                    1,
                    new MemorySegment[]{chunkMeta, segmentOf((byte) 0x00)});

            // When / Then
            assertThatThrownBy(() -> sut.decode(ctx))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("stateN");
        }

        @Test
        void decode_lookback_singleInitialValue_returnsIt() {
            // Given — pageN=1, stateN=1, decodeN=0: only the initial state value; no decoded values.
            var sut = new PcoEncoding();
            DecodeContext ctx = ctxWith(
                    metaWithOneChunk(1),
                    new DType.Primitive(PType.U64, false),
                    1,
                    new MemorySegment[]{chunkMetaLookback(), lookbackPage(42L)});

            // When
            var result = sut.decode(ctx);

            // Then
            assertThat(result.length()).isEqualTo(1);
            assertThat(((LongArray) result).getLong(0)).isEqualTo(42L);
        }
    }

    @Nested
    class DecodeLookbackDecodeN {

        @Test
        void lookback_decodeNExceedsMax_throwsVortexException() {
            // Given — stateN=1, pageN=(1<<23)+2 → decodeN=(1<<23)+1 > cap 1<<23.
            // Check fires before arena.allocate; page only needs 8 bytes (1×64-bit initial state).
            var sut = new PcoEncoding();
            int pageN = (1 << 23) + 2;
            DecodeContext ctx = ctxWith(
                    metaWithOneChunk(pageN),
                    new DType.Primitive(PType.U64, false),
                    pageN,
                    new MemorySegment[]{chunkMetaLookback(), segmentOf(new byte[8])});

            // When / Then
            assertThatThrownBy(() -> sut.decode(ctx))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("decodeN");
        }
    }

    @Nested
    class DecodeLookbackStateNWindow {

        @Test
        void lookback_stateNExceedsWindowN_throwsVortexException() {
            // Given — windowNLog=1 (windowN=2), stateNLog=2 (stateN=4) → stateN > windowN.
            // pageN=4 (≥ stateN=4 passes the pageN<stateN guard); page has 4×8=32 zero bytes.
            // Bit layout:
            //   byte0: mode=0[3:0], delta=2[7:4] → 0x20
            //   byte1: windowNLog-1(5b)=0, stateNLog[0..2](3b)=0b010 → 0x40
            //   byte2: stateNLog[3](1b)=0, secondary(1b)=0, ... → 0x00
            //   bytes3-6: 0x00
            var sut = new PcoEncoding();
            MemorySegment chunkMeta = segmentOf(
                    (byte) 0x20, (byte) 0x40, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00);
            MemorySegment page = segmentOf(new byte[32]);
            DecodeContext ctx = ctxWith(
                    metaWithOneChunk(4),
                    new DType.Primitive(PType.U64, false),
                    4,
                    new MemorySegment[]{chunkMeta, page});

            // When / Then
            assertThatThrownBy(() -> sut.decode(ctx))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("stateN");
        }
    }

    @Nested
    class DecodeLookbackWindowNLog {

        @Test
        void lookback_windowNLogExceedsMax_throwsVortexException() {
            // Given — mode=0 (Classic), delta=2 (Lookback), windowNLog=25 > max 24.
            // Bit layout (LSB-first after byte0):
            //   byte0: mode=0[3:0], delta=2[7:4] → 0x20
            //   byte1: windowNLog-1(5b)=24=0b11000 → 0x18; stateNLog bits start at bit5
            //   bytes2-6: all 0x00
            var sut = new PcoEncoding();
            MemorySegment chunkMeta = segmentOf(
                    (byte) 0x20, (byte) 0x18, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00);
            DecodeContext ctx = ctxWith(
                    metaWithOneChunk(1),
                    new DType.Primitive(PType.U64, false),
                    1,
                    new MemorySegment[]{chunkMeta, segmentOf((byte) 0x00)});

            // When / Then
            assertThatThrownBy(() -> sut.decode(ctx))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("windowNLog");
        }
    }

    @Nested
    class DecodeDict {

        @Test
        void dict_nUniqueExceedsMax_throwsVortexException() {
            // Given — mode=4 (Dict), nUnique=65537 > max 65536.
            // Bit layout (LSB-first): mode[3:0]=4, nUnique[28:4]=65537
            //   combined = 4 | (65537 << 4) = 0x100014
            //   bytes: 0x14, 0x00, 0x10, 0x00, 0x00
            var sut = new PcoEncoding();
            MemorySegment chunkMeta = segmentOf(
                    (byte) 0x14, (byte) 0x00, (byte) 0x10, (byte) 0x00, (byte) 0x00);
            DecodeContext ctx = ctxWith(
                    metaWithOneChunk(1),
                    new DType.Primitive(PType.U64, false),
                    1,
                    new MemorySegment[]{chunkMeta, segmentOf((byte) 0x00)});

            // When / Then
            assertThatThrownBy(() -> sut.decode(ctx))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("nUnique");
        }
    }

    /// Adversarial coverage: malformed inputs must throw VortexException — never AIOOBE, NPE, or OOM.
    @Nested
    class Adversarial {

        static Stream<byte[]> chunkMetaBytesProvider() {
            Random rng = new Random(0xDEADBEEFL);
            return Stream.generate(() -> {
                byte[] b = new byte[1 + rng.nextInt(64)];
                rng.nextBytes(b);
                return b;
            }).limit(50);
        }

        static Stream<byte[]> pageBytesProvider() {
            Random rng = new Random(0xCAFEBABEL);
            return Stream.generate(() -> {
                byte[] b = new byte[4 + rng.nextInt(125)];
                rng.nextBytes(b);
                return b;
            }).limit(50);
        }

        /// Random chunk-meta bytes — any exception must be a VortexException, not a JVM crash exception.
        @ParameterizedTest
        @MethodSource("chunkMetaBytesProvider")
        void randomChunkMetaBytes_neverThrowsJvmException(byte[] chunkMetaBytes) {
            // Given — valid pco header + 1 chunk with 1 page of 1 value; garbage chunk-meta bytes.
            var sut = new PcoEncoding();
            DecodeContext ctx = ctxWith(
                    metaWithOneChunk(1),
                    new DType.Primitive(PType.U64, false),
                    1,
                    new MemorySegment[]{segmentOf(chunkMetaBytes), segmentOf((byte) 0x00)});

            // When / Then — either succeeds or throws VortexException; never AIOOBE/NPE/OOM
            try {
                sut.decode(ctx);
            } catch (VortexException ignored) {
                // expected — malformed input
            }
        }

        /// Random page bytes after a valid Classic-mode chunk meta — must not crash the JVM.
        @ParameterizedTest
        @MethodSource("pageBytesProvider")
        void randomPageBytes_classicMode_neverThrowsJvmException(byte[] pageBytes) {
            // Given — Classic mode, delta=NoOp, ansSizeLog=0, nBins=0 chunk meta.
            var sut = new PcoEncoding();
            // byte0: mode=0 (bits3:0), deltaVariant=0 (bits7:4) → 0x00
            // byte1: ansSizeLog=0 (bits3:0), nBins low bits = 0
            // bytes 2-3: nBins high bits = 0
            DecodeContext ctx = ctxWith(
                    metaWithOneChunk(1),
                    new DType.Primitive(PType.U64, false),
                    1,
                    new MemorySegment[]{segmentOf((byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00),
                            segmentOf(pageBytes)});

            // When / Then
            try {
                sut.decode(ctx);
            } catch (VortexException ignored) {
                // expected — malformed page data
            }
        }

        /// Invalid mode nibbles (5–15) must produce a VortexException naming the mode number.
        @ParameterizedTest
        @ValueSource(ints = {5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15})
        void invalidModeNibble_throwsVortexException(int modeNibble) {
            // Given — chunk meta with unsupported mode nibble in bits[3:0].
            var sut = new PcoEncoding();
            // bits[3:0] = modeNibble, delta nibble doesn't matter (won't be reached)
            byte modeByte = (byte) (modeNibble & 0x0F);
            DecodeContext ctx = ctxWith(
                    metaWithOneChunk(1),
                    new DType.Primitive(PType.U64, false),
                    1,
                    new MemorySegment[]{
                            segmentOf(modeByte, (byte) 0x00, (byte) 0x00, (byte) 0x00),
                            segmentOf((byte) 0x00)});

            // When / Then
            assertThatThrownBy(() -> sut.decode(ctx))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("pco mode " + modeNibble);
        }

        /// Invalid delta variants (4–15) must produce a VortexException naming the variant number.
        @ParameterizedTest
        @ValueSource(ints = {4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15})
        void invalidDeltaVariant_throwsVortexException(int deltaVariant) {
            // Given — Classic mode (nibble=0) + invalid delta nibble in bits[7:4].
            var sut = new PcoEncoding();
            // byte0: bits[3:0]=mode=0, bits[7:4]=deltaVariant
            byte modeDeltaByte = (byte) ((deltaVariant & 0x0F) << 4);
            DecodeContext ctx = ctxWith(
                    metaWithOneChunk(1),
                    new DType.Primitive(PType.U64, false),
                    1,
                    new MemorySegment[]{
                            segmentOf(modeDeltaByte, (byte) 0x00, (byte) 0x00, (byte) 0x00),
                            segmentOf((byte) 0x00)});

            // When / Then
            assertThatThrownBy(() -> sut.decode(ctx))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("delta variant " + deltaVariant);
        }

        /// Conv1 delta with 64-bit dtype must throw VortexException; pcodec only supports 16/32-bit Conv1.
        @ParameterizedTest
        @EnumSource(value = PType.class, names = {"I64", "U64", "F64"})
        void conv1Delta_with64BitDtype_throwsVortexException(PType ptype) {
            // Given — Conv1 delta variant (nibble=3 in bits[7:4]), Classic mode (nibble=0 in bits[3:0]).
            // byte0: bits[3:0]=0 (Classic), bits[7:4]=3 (Conv1) → 0x30
            // Remaining bytes: conv1 bit fields (don't matter — error fires before parsing them).
            var sut = new PcoEncoding();
            DecodeContext ctx = ctxWith(
                    metaWithOneChunk(1),
                    new DType.Primitive(ptype, false),
                    1,
                    new MemorySegment[]{
                            segmentOf((byte) 0x30, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                                    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                                    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                                    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00),
                            segmentOf((byte) 0x00)});

            // When / Then
            assertThatThrownBy(() -> sut.decode(ctx))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("Conv1");
        }
    }
}
