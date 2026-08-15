package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.reader.array.VarBinArray;
import io.github.dfa1.vortex.reader.decode.ArrayNode;
import io.github.dfa1.vortex.core.testing.DTypes;
import io.github.dfa1.vortex.reader.decode.DecodeContext;

import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.decode.TestRegistry;
import io.github.dfa1.vortex.core.proto.ProtoVarBinMetadata;
import io.github.dfa1.vortex.reader.decode.PrimitiveEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.VarBinEncodingDecoder;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VarBinEncodingEncoderTest {

    private static final VarBinEncodingEncoder ENCODER = new VarBinEncodingEncoder();
    private static final VarBinEncodingDecoder DECODER = new VarBinEncodingDecoder();
    private static final ReadRegistry REGISTRY = TestRegistry.ofDecoders(DECODER, new PrimitiveEncodingDecoder());

    @Nested
    class Encode {

        @Test
        void encodingId_isVortexVarbin() {
            // Given
            // When
            EncodingId result = ENCODER.encodingId();

            // Then
            assertThat(result).isEqualTo(EncodingId.VORTEX_VARBIN);
        }

        @Test
        void accepts_utf8Dtype_returnsTrue() {
            // Given
            // When
            boolean result = ENCODER.accepts(DTypes.UTF8);

            // Then
            assertThat(result).isTrue();
        }

        @Test
        void accepts_binaryDtype_returnsTrue() {
            // Given
            // When
            boolean result = ENCODER.accepts(DTypes.BINARY);

            // Then
            assertThat(result).isTrue();
        }

        @Test
        void accepts_primitiveDtype_returnsFalse() {
            // Given
            // When
            boolean result = ENCODER.accepts(DType.I64);

            // Then
            assertThat(result).isFalse();
        }

        @Test
        void encode_singleString_roundTrips() {
            // Given
            String[] data = {"hello"};
            EncodeResult encoded = ENCODER.encode(DTypes.UTF8, data, EncodeTestHelper.testCtx());
            DecodeContext ctx = DecodeTestHelper.toDecodeContext(encoded, data.length, DTypes.UTF8, REGISTRY);

            // When
            VarBinArray result = (VarBinArray) DECODER.decode(ctx);

            // Then
            assertThat(result.length()).isEqualTo(1);
            assertThat(result.getBytes(0)).isEqualTo("hello".getBytes(StandardCharsets.UTF_8));
        }

        @Test
        void encode_multipleStrings_roundTrips() {
            // Given
            String[] data = {"foo", "bar", "baz"};
            EncodeResult encoded = ENCODER.encode(DTypes.UTF8, data, EncodeTestHelper.testCtx());
            DecodeContext ctx = DecodeTestHelper.toDecodeContext(encoded, data.length, DTypes.UTF8, REGISTRY);

            // When
            VarBinArray result = (VarBinArray) DECODER.decode(ctx);

            // Then
            assertThat(result.length()).isEqualTo(3);
            for (int i = 0; i < data.length; i++) {
                assertThat(result.getBytes(i)).isEqualTo(data[i].getBytes(StandardCharsets.UTF_8));
            }
        }

        @Test
        void encode_unicodeString_roundTrips() {
            // Given
            String[] data = {"héllo", "wörld", "日本語"};
            EncodeResult encoded = ENCODER.encode(DTypes.UTF8, data, EncodeTestHelper.testCtx());
            DecodeContext ctx = DecodeTestHelper.toDecodeContext(encoded, data.length, DTypes.UTF8, REGISTRY);

            // When
            VarBinArray result = (VarBinArray) DECODER.decode(ctx);

            // Then
            assertThat(result.length()).isEqualTo(3);
            for (int i = 0; i < data.length; i++) {
                assertThat(result.getBytes(i)).isEqualTo(data[i].getBytes(StandardCharsets.UTF_8));
            }
        }

        @Test
        void encode_rawBytes_roundTripByteForByte_notThroughUtf8() {
            // Given — non-UTF8 bytes (0x80 alone is not a valid UTF-8 sequence); routing this
            // through String[].getBytes(UTF_8) would corrupt it via the UTF-8 replacement
            // character. DType.Binary carries data as byte[][], not String[].
            byte[][] data = {{(byte) 0x80, (byte) 0xFF, 0x00, 0x01}, {}, {0x41, 0x42}};
            EncodeResult encoded = ENCODER.encode(DTypes.BINARY, data, EncodeTestHelper.testCtx());
            DecodeContext ctx = DecodeTestHelper.toDecodeContext(encoded, data.length, DTypes.BINARY, REGISTRY);

            // When
            VarBinArray result = (VarBinArray) DECODER.decode(ctx);

            // Then
            assertThat(result.length()).isEqualTo(3);
            assertThat(result.getBytes(0)).isEqualTo(data[0]);
            assertThat(result.getBytes(1)).isEmpty();
            assertThat(result.getBytes(2)).isEqualTo(data[2]);
        }

        @Test
        void encode_rawBytesWithNullEntry_nullBecomesZeroLengthSlot() {
            // Given — mirrors encode_emptyStringInArray_roundTrips for the byte[][] path: a null
            // entry (values child of a masked/nullable layout) contributes a zero-length slot
            byte[][] data = {{0x01}, null, {0x02}};
            EncodeResult encoded = ENCODER.encode(DTypes.BINARY, data, EncodeTestHelper.testCtx());
            DecodeContext ctx = DecodeTestHelper.toDecodeContext(encoded, data.length, DTypes.BINARY, REGISTRY);

            // When
            VarBinArray result = (VarBinArray) DECODER.decode(ctx);

            // Then
            assertThat(result.length()).isEqualTo(3);
            assertThat(result.getBytes(0)).isEqualTo(new byte[]{0x01});
            assertThat(result.getBytes(1)).isEmpty();
            assertThat(result.getBytes(2)).isEqualTo(new byte[]{0x02});
        }

        @Test
        void encode_emptyStringInArray_roundTrips() {
            // Given
            String[] data = {"a", "", "b"};
            EncodeResult encoded = ENCODER.encode(DTypes.UTF8, data, EncodeTestHelper.testCtx());
            DecodeContext ctx = DecodeTestHelper.toDecodeContext(encoded, data.length, DTypes.UTF8, REGISTRY);

            // When
            VarBinArray result = (VarBinArray) DECODER.decode(ctx);

            // Then
            assertThat(result.length()).isEqualTo(3);
            assertThat(result.getBytes(0)).isEqualTo(new byte[]{'a'});
            assertThat(result.getBytes(1)).isEmpty();
            assertThat(result.getBytes(2)).isEqualTo(new byte[]{'b'});
        }

        @Test
        void encode_emptyArray_producesZeroLengthResult() {
            // Given
            String[] data = {};
            EncodeResult encoded = ENCODER.encode(DTypes.UTF8, data, EncodeTestHelper.testCtx());
            DecodeContext ctx = DecodeTestHelper.toDecodeContext(encoded, data.length, DTypes.UTF8, REGISTRY);

            // When
            VarBinArray result = (VarBinArray) DECODER.decode(ctx);

            // Then
            assertThat(result.length()).isZero();
        }
    }

    @Nested
    class Decode {

        @Test
        void decode_missingMetadata_throwsVortexException() {
            // Given
            ArrayNode node = new ArrayNode(EncodingId.VORTEX_VARBIN, null, new ArrayNode[0], new int[0]);
            DecodeContext ctx = new DecodeContext(node, DTypes.UTF8, 3, new MemorySegment[0],
                    ReadRegistry.empty(), Arena.ofAuto());

            // When / Then
            assertThatThrownBy(() -> DECODER.decode(ctx))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("missing metadata");
        }
    }

    @Nested
    class Metadata {

        @Test
        void encode_utf8_metadata_offsetsPtype_isI64() throws Exception {
            // Given
            String[] data = {"hello", "world"};

            // When
            EncodeResult result = ENCODER.encode(DTypes.UTF8, data, EncodeTestHelper.testCtx());

            // Then
            var metaSeg = result.rootNode().metadata();
            ProtoVarBinMetadata meta = ProtoVarBinMetadata.decode(metaSeg, 0, metaSeg.byteSize());

            assertThat(meta.offsets_ptype().value()).isEqualTo(7);
        }
    }
}
