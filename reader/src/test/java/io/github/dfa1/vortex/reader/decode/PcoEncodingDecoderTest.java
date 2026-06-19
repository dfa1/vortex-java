package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.reader.ReadRegistry;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.encoding.EncodingId;

import io.github.dfa1.vortex.proto.PcoChunkInfo;
import io.github.dfa1.vortex.proto.PcoMetadata;
import io.github.dfa1.vortex.proto.PcoPageInfo;
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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PcoEncodingDecoderTest {

    private static final PcoEncodingDecoder SUT = new PcoEncodingDecoder();

    private static ByteBuffer validMetaBuffer() {
        PcoMetadata meta = new PcoMetadata(new byte[]{PcoEncodingDecoder.PCO_FORMAT_MAJOR, PcoEncodingDecoder.PCO_FORMAT_MINOR}, java.util.List.of());
        return ByteBuffer.wrap(meta.encode());
    }

    private static DecodeContext ctxWith(ByteBuffer meta, DType dtype, long rowCount, MemorySegment[] buffers) {
        ArrayNode node = ArrayNode.of(EncodingId.VORTEX_PCO, meta, new ArrayNode[0],
                bufferIndices(buffers.length));
        return new DecodeContext(node, dtype, rowCount, buffers, ReadRegistry.empty(), Arena.ofAuto());
    }

    private static DecodeContext ctxWithValidity(ByteBuffer meta, DType dtype, long rowCount,
            MemorySegment validityBuf, MemorySegment[] pcoBuffers) {
        MemorySegment[] allBuffers = new MemorySegment[1 + pcoBuffers.length];
        allBuffers[0] = validityBuf;
        System.arraycopy(pcoBuffers, 0, allBuffers, 1, pcoBuffers.length);

        ArrayNode validityNode = ArrayNode.of(EncodingId.VORTEX_BOOL, null, new ArrayNode[0],
                new int[]{0});

        int[] pcoBufferIndices = new int[pcoBuffers.length];
        for (int i = 0; i < pcoBuffers.length; i++) {
            pcoBufferIndices[i] = i + 1;
        }
        ArrayNode pcoNode = ArrayNode.of(EncodingId.VORTEX_PCO, meta, new ArrayNode[]{validityNode},
                pcoBufferIndices);

        ReadRegistry registry = TestRegistry.ofDecoders(new BoolEncodingDecoder());
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

    private static ByteBuffer metaWithOneChunk(int nValues) {
        PcoMetadata meta = new PcoMetadata(
                new byte[]{PcoEncodingDecoder.PCO_FORMAT_MAJOR, PcoEncodingDecoder.PCO_FORMAT_MINOR},
                java.util.List.of(new PcoChunkInfo(java.util.List.of(new PcoPageInfo(nValues)))));
        return ByteBuffer.wrap(meta.encode());
    }

    private static MemorySegment chunkMetaConsecutive(int order) {
        return segmentOf((byte) 0x10, (byte) order, (byte) 0x00, (byte) 0x00);
    }

    private static MemorySegment pageWithMoments(long... moments) {
        byte[] buf = new byte[moments.length * Long.BYTES];
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(buf).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        for (long m : moments) {
            bb.putLong(m);
        }
        return segmentOf(buf);
    }

    private static MemorySegment chunkMetaConv1(int quantization, long biasLatent,
            int order, long[] weightLatents) {
        java.util.BitSet bits = new java.util.BitSet();
        int pos = 0;
        pos += 4;
        bits.set(pos);
        bits.set(pos + 1);
        pos += 4;
        for (int i = 0; i < 5; i++) {
            if (((quantization >> i) & 1) != 0) {
                bits.set(pos);
            }
            pos++;
        }
        for (int i = 0; i < 64; i++) {
            if (((biasLatent >> i) & 1L) != 0L) {
                bits.set(pos);
            }
            pos++;
        }
        for (int i = 0; i < 5; i++) {
            if ((((order - 1) >> i) & 1) != 0) {
                bits.set(pos);
            }
            pos++;
        }
        for (long wl : weightLatents) {
            for (int i = 0; i < 32; i++) {
                if (((wl >> i) & 1L) != 0L) {
                    bits.set(pos);
                }
                pos++;
            }
        }
        pos += 4;
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

    private static MemorySegment chunkMetaLookback() {
        return segmentOf((byte) 0x20, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00);
    }

    private static MemorySegment lookbackPage(long initialState) {
        byte[] buf = new byte[Long.BYTES];
        java.nio.ByteBuffer.wrap(buf).order(java.nio.ByteOrder.LITTLE_ENDIAN).putLong(initialState);
        return segmentOf(buf);
    }

    @Nested
    class EncodingIdNested {
        @Test
        void encodingId_isVortexPco() {
            assertThat(SUT.encodingId()).isEqualTo(EncodingId.VORTEX_PCO);
        }
    }

    @Nested
    class Decode {

        @Test
        void decode_nullMetadata_throwsMissingMeta() {
            DecodeContext ctx = ctxWith(null, new DType.Primitive(PType.I64, false), 0, new MemorySegment[0]);
            assertThatThrownBy(() -> SUT.decode(ctx))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("missing PcoMetadata");
        }

        @Test
        void decode_invalidHeaderVersion_throwsUnsupported() {
            PcoMetadata meta = new PcoMetadata(new byte[]{0x03, 0x00}, java.util.List.of());
            DecodeContext ctx = ctxWith(ByteBuffer.wrap(meta.encode()),
                    new DType.Primitive(PType.I64, false), 0, new MemorySegment[0]);
            assertThatThrownBy(() -> SUT.decode(ctx))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("unsupported pco format version 03.00");
        }

        @Test
        void decode_nonPrimitiveDtype_throws() {
            DecodeContext ctx = ctxWith(validMetaBuffer(), new DType.Utf8(false), 0, new MemorySegment[0]);
            assertThatThrownBy(() -> SUT.decode(ctx))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("Primitive dtype");
        }

        @Test
        void decode_unsupportedPtype_throws() {
            DecodeContext ctx = ctxWith(validMetaBuffer(), new DType.Primitive(PType.F16, false), 0,
                    new MemorySegment[0]);
            assertThatThrownBy(() -> SUT.decode(ctx))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("unsupported ptype");
        }

        @ParameterizedTest
        @EnumSource(value = PType.class, names = {"I16", "U16", "I32", "U32", "F32", "I64", "U64", "F64"})
        void decode_zeroChunks_returnsEmptyArray(PType ptype) {
            DecodeContext ctx = ctxWith(validMetaBuffer(), new DType.Primitive(ptype, false), 0, new MemorySegment[0]);
            var result = SUT.decode(ctx);
            assertThat(result.length()).isZero();
        }

        @Test
        void decode_consecutiveDelta_order1_singleValue_decodes() {
            DecodeContext ctx = ctxWith(metaWithOneChunk(1), new DType.Primitive(PType.U64, false), 1,
                    new MemorySegment[]{chunkMetaConsecutive(1), pageWithMoments(42L)});
            var result = SUT.decode(ctx);
            assertThat(result.length()).isEqualTo(1);
            assertThat(((LongArray) result).getLong(0)).isEqualTo(42L);
        }

        @Test
        void decode_consecutiveDelta_order2_twoValues_decodes() {
            DecodeContext ctx = ctxWith(metaWithOneChunk(2), new DType.Primitive(PType.U64, false), 2,
                    new MemorySegment[]{chunkMetaConsecutive(2), pageWithMoments(10L, 7L)});
            var result = SUT.decode(ctx);
            assertThat(result.length()).isEqualTo(2);
            assertThat(((LongArray) result).getLong(0)).isEqualTo(10L);
            assertThat(((LongArray) result).getLong(1)).isEqualTo(17L);
        }

        @Test
        void decode_multiPage_singleChunk_decodes() {
            PcoMetadata meta = new PcoMetadata(
                    new byte[]{PcoEncodingDecoder.PCO_FORMAT_MAJOR, PcoEncodingDecoder.PCO_FORMAT_MINOR},
                    java.util.List.of(new PcoChunkInfo(java.util.List.of(new PcoPageInfo(1), new PcoPageInfo(1)))));
            DecodeContext ctx = ctxWith(ByteBuffer.wrap(meta.encode()), new DType.Primitive(PType.U64, false), 2,
                    new MemorySegment[]{chunkMetaConsecutive(1), pageWithMoments(10L), pageWithMoments(20L)});
            var result = SUT.decode(ctx);
            assertThat(result.length()).isEqualTo(2);
            assertThat(((LongArray) result).getLong(0)).isEqualTo(10L);
            assertThat(((LongArray) result).getLong(1)).isEqualTo(20L);
        }

        @Test
        void decode_multiChunk_decodes() {
            // Buffer layout: all chunk metas first, then all pages (matches Rust vortex PcoArray).
            PcoMetadata meta = new PcoMetadata(
                    new byte[]{PcoEncodingDecoder.PCO_FORMAT_MAJOR, PcoEncodingDecoder.PCO_FORMAT_MINOR},
                    java.util.List.of(
                            new PcoChunkInfo(java.util.List.of(new PcoPageInfo(1))),
                            new PcoChunkInfo(java.util.List.of(new PcoPageInfo(1)))));
            DecodeContext ctx = ctxWith(ByteBuffer.wrap(meta.encode()), new DType.Primitive(PType.U64, false), 2,
                    new MemorySegment[]{chunkMetaConsecutive(1), chunkMetaConsecutive(1),
                            pageWithMoments(100L), pageWithMoments(200L)});
            var result = SUT.decode(ctx);
            assertThat(result.length()).isEqualTo(2);
            assertThat(((LongArray) result).getLong(0)).isEqualTo(100L);
            assertThat(((LongArray) result).getLong(1)).isEqualTo(200L);
        }
    }

    @Nested
    class DecodeNullable {

        @Test
        void decode_nullable_someNulls_scattersCorrectly() {
            PcoMetadata meta = new PcoMetadata(
                    new byte[]{PcoEncodingDecoder.PCO_FORMAT_MAJOR, PcoEncodingDecoder.PCO_FORMAT_MINOR},
                    java.util.List.of(new PcoChunkInfo(java.util.List.of(new PcoPageInfo(1), new PcoPageInfo(1)))));
            MemorySegment validityBuf = segmentOf((byte) 0x05);
            DecodeContext ctx = ctxWithValidity(
                    ByteBuffer.wrap(meta.encode()), new DType.Primitive(PType.U64, true), 3, validityBuf,
                    new MemorySegment[]{chunkMetaConsecutive(1), pageWithMoments(100L), pageWithMoments(200L)});
            var result = SUT.decode(ctx);

            assertThat(result).isInstanceOf(MaskedArray.class);
            MaskedArray masked = (MaskedArray) result;
            assertThat(masked.isValid(0)).isTrue();
            assertThat(masked.isValid(1)).isFalse();
            assertThat(masked.isValid(2)).isTrue();
            assertThat(((LongArray) masked.inner()).getLong(0)).isEqualTo(100L);
            assertThat(((LongArray) masked.inner()).getLong(2)).isEqualTo(200L);
        }

        @Test
        void decode_nullable_allNull_returnsAllZeroed() {
            MemorySegment validityBuf = segmentOf((byte) 0x00);
            DecodeContext ctx = ctxWithValidity(validMetaBuffer(), new DType.Primitive(PType.U64, true), 2,
                    validityBuf, new MemorySegment[0]);
            var result = SUT.decode(ctx);

            assertThat(result).isInstanceOf(MaskedArray.class);
            MaskedArray masked = (MaskedArray) result;
            assertThat(masked.isValid(0)).isFalse();
            assertThat(masked.isValid(1)).isFalse();
            assertThat(((LongArray) masked.inner()).getLong(0)).isZero();
            assertThat(((LongArray) masked.inner()).getLong(1)).isZero();
        }

        @Test
        void decode_nullable_allValid_returnsMaskedWithAllValues() {
            MemorySegment validityBuf = segmentOf((byte) 0x03);
            DecodeContext ctx = ctxWithValidity(metaWithOneChunk(2), new DType.Primitive(PType.U64, true), 2,
                    validityBuf, new MemorySegment[]{chunkMetaConsecutive(2), pageWithMoments(10L, 10L)});
            var result = SUT.decode(ctx);

            assertThat(result).isInstanceOf(MaskedArray.class);
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
            long biasLatent = Long.MIN_VALUE;
            long weightLatent = 0x80000000L;
            MemorySegment chunkMeta = chunkMetaConv1(0, biasLatent, 1, new long[]{weightLatent});
            MemorySegment page = segmentOf((byte) 0x05, (byte) 0x00, (byte) 0x00, (byte) 0x80);
            DecodeContext ctx = ctxWith(metaWithOneChunk(2), new DType.Primitive(PType.I32, false), 2,
                    new MemorySegment[]{chunkMeta, page});
            var result = SUT.decode(ctx);

            assertThat(result.length()).isEqualTo(2);
            assertThat(((io.github.dfa1.vortex.reader.array.IntArray) result).getInt(0)).isEqualTo(5);
            assertThat(((io.github.dfa1.vortex.reader.array.IntArray) result).getInt(1)).isZero();
        }
    }

    @Nested
    class DecodeLookback {

        @Test
        void decode_lookback_corruptIndexZero_throwsVortexException() {
            DecodeContext ctx = ctxWith(metaWithOneChunk(2), new DType.Primitive(PType.U64, false), 2,
                    new MemorySegment[]{chunkMetaLookback(), lookbackPage(0L)});
            assertThatThrownBy(() -> SUT.decode(ctx))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("corrupt lookback index 0");
        }

        @Test
        void decode_lookback_stateNExceedsPageN_throwsVortexException() {
            MemorySegment chunkMeta = segmentOf(
                    (byte) 0x20, (byte) 0x20, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00);
            DecodeContext ctx = ctxWith(metaWithOneChunk(1), new DType.Primitive(PType.U64, false), 1,
                    new MemorySegment[]{chunkMeta, segmentOf((byte) 0x00)});
            assertThatThrownBy(() -> SUT.decode(ctx))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("stateN");
        }

        @Test
        void decode_lookback_singleInitialValue_returnsIt() {
            DecodeContext ctx = ctxWith(metaWithOneChunk(1), new DType.Primitive(PType.U64, false), 1,
                    new MemorySegment[]{chunkMetaLookback(), lookbackPage(42L)});
            var result = SUT.decode(ctx);
            assertThat(result.length()).isEqualTo(1);
            assertThat(((LongArray) result).getLong(0)).isEqualTo(42L);
        }
    }

    @Nested
    class DecodeLookbackDecodeN {
        @Test
        void lookback_decodeNExceedsMax_throwsVortexException() {
            int pageN = (1 << 23) + 2;
            DecodeContext ctx = ctxWith(metaWithOneChunk(pageN), new DType.Primitive(PType.U64, false), pageN,
                    new MemorySegment[]{chunkMetaLookback(), segmentOf(new byte[8])});
            assertThatThrownBy(() -> SUT.decode(ctx))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("decodeN");
        }
    }

    @Nested
    class DecodeLookbackStateNWindow {
        @Test
        void lookback_stateNExceedsWindowN_throwsVortexException() {
            MemorySegment chunkMeta = segmentOf(
                    (byte) 0x20, (byte) 0x40, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00);
            MemorySegment page = segmentOf(new byte[32]);
            DecodeContext ctx = ctxWith(metaWithOneChunk(4), new DType.Primitive(PType.U64, false), 4,
                    new MemorySegment[]{chunkMeta, page});
            assertThatThrownBy(() -> SUT.decode(ctx))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("stateN");
        }
    }

    @Nested
    class DecodeLookbackWindowNLog {
        @Test
        void lookback_windowNLogExceedsMax_throwsVortexException() {
            MemorySegment chunkMeta = segmentOf(
                    (byte) 0x20, (byte) 0x18, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00);
            DecodeContext ctx = ctxWith(metaWithOneChunk(1), new DType.Primitive(PType.U64, false), 1,
                    new MemorySegment[]{chunkMeta, segmentOf((byte) 0x00)});
            assertThatThrownBy(() -> SUT.decode(ctx))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("windowNLog");
        }
    }

    @Nested
    class DecodeDict {
        @Test
        void dict_nUniqueExceedsMax_throwsVortexException() {
            MemorySegment chunkMeta = segmentOf((byte) 0x14, (byte) 0x00, (byte) 0x10, (byte) 0x00, (byte) 0x00);
            DecodeContext ctx = ctxWith(metaWithOneChunk(1), new DType.Primitive(PType.U64, false), 1,
                    new MemorySegment[]{chunkMeta, segmentOf((byte) 0x00)});
            assertThatThrownBy(() -> SUT.decode(ctx))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("nUnique");
        }
    }

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

        @ParameterizedTest
        @MethodSource("chunkMetaBytesProvider")
        void randomChunkMetaBytes_neverThrowsJvmException(byte[] chunkMetaBytes) {
            DecodeContext ctx = ctxWith(metaWithOneChunk(1), new DType.Primitive(PType.U64, false), 1,
                    new MemorySegment[]{segmentOf(chunkMetaBytes), segmentOf((byte) 0x00)});
            // VortexException is acceptable; NPE/AIOOBE/etc. are not
            assertThatCode(() -> {
                try {
                    SUT.decode(ctx);
                } catch (VortexException _) { // acceptable; only non-Vortex failures should escape
                }
            }).doesNotThrowAnyException();
        }

        @ParameterizedTest
        @MethodSource("pageBytesProvider")
        void randomPageBytes_classicMode_neverThrowsJvmException(byte[] pageBytes) {
            DecodeContext ctx = ctxWith(metaWithOneChunk(1), new DType.Primitive(PType.U64, false), 1,
                    new MemorySegment[]{segmentOf((byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00),
                            segmentOf(pageBytes)});
            // VortexException is acceptable; NPE/AIOOBE/etc. are not
            assertThatCode(() -> {
                try {
                    SUT.decode(ctx);
                } catch (VortexException _) { // acceptable; only non-Vortex failures should escape
                }
            }).doesNotThrowAnyException();
        }

        @ParameterizedTest
        @ValueSource(ints = {5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15})
        void invalidModeNibble_throwsVortexException(int modeNibble) {
            byte modeByte = (byte) (modeNibble & 0x0F);
            DecodeContext ctx = ctxWith(metaWithOneChunk(1), new DType.Primitive(PType.U64, false), 1,
                    new MemorySegment[]{segmentOf(modeByte, (byte) 0x00, (byte) 0x00, (byte) 0x00),
                            segmentOf((byte) 0x00)});
            assertThatThrownBy(() -> SUT.decode(ctx))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("pco mode " + modeNibble);
        }

        @ParameterizedTest
        @ValueSource(ints = {4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15})
        void invalidDeltaVariant_throwsVortexException(int deltaVariant) {
            byte modeDeltaByte = (byte) ((deltaVariant & 0x0F) << 4);
            DecodeContext ctx = ctxWith(metaWithOneChunk(1), new DType.Primitive(PType.U64, false), 1,
                    new MemorySegment[]{segmentOf(modeDeltaByte, (byte) 0x00, (byte) 0x00, (byte) 0x00),
                            segmentOf((byte) 0x00)});
            assertThatThrownBy(() -> SUT.decode(ctx))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("delta variant " + deltaVariant);
        }

        @ParameterizedTest
        @EnumSource(value = PType.class, names = {"I64", "U64", "F64"})
        void conv1Delta_with64BitDtype_throwsVortexException(PType ptype) {
            DecodeContext ctx = ctxWith(metaWithOneChunk(1), new DType.Primitive(ptype, false), 1,
                    new MemorySegment[]{
                            segmentOf((byte) 0x30, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                                    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                                    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                                    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00),
                            segmentOf((byte) 0x00)});
            assertThatThrownBy(() -> SUT.decode(ctx))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("Conv1");
        }
    }
}
