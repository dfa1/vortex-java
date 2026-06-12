package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.core.array.VarBinArray;
import io.github.dfa1.vortex.reader.decode.ArrayNode;
import io.github.dfa1.vortex.encoding.DTypes;
import io.github.dfa1.vortex.reader.decode.DecodeContext;

import io.github.dfa1.vortex.encoding.EncodingId;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.decode.TestRegistry;
import io.github.dfa1.vortex.proto.VarBinMetadata;
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
            assertThat(ENCODER.encodingId()).isEqualTo(EncodingId.VORTEX_VARBIN);
        }

        @Test
        void accepts_utf8Dtype_returnsTrue() {
            assertThat(ENCODER.accepts(DTypes.UTF8)).isTrue();
        }

        @Test
        void accepts_binaryDtype_returnsTrue() {
            assertThat(ENCODER.accepts(DTypes.BINARY)).isTrue();
        }

        @Test
        void accepts_primitiveDtype_returnsFalse() {
            assertThat(ENCODER.accepts(new DType.Primitive(PType.I64, false))).isFalse();
        }

        @Test
        void encode_singleString_roundTrips() {
            String[] data = {"hello"};
            EncodeResult result = ENCODER.encode(DTypes.UTF8, data, EncodeTestHelper.testCtx());
            DecodeContext ctx = DecodeTestHelper.toDecodeContext(result, data.length, DTypes.UTF8, REGISTRY);
            VarBinArray decoded = (VarBinArray) DECODER.decode(ctx);

            assertThat(decoded.length()).isEqualTo(1);
            assertThat(decoded.getBytes(0)).isEqualTo("hello".getBytes(StandardCharsets.UTF_8));
        }

        @Test
        void encode_multipleStrings_roundTrips() {
            String[] data = {"foo", "bar", "baz"};
            EncodeResult result = ENCODER.encode(DTypes.UTF8, data, EncodeTestHelper.testCtx());
            DecodeContext ctx = DecodeTestHelper.toDecodeContext(result, data.length, DTypes.UTF8, REGISTRY);
            VarBinArray decoded = (VarBinArray) DECODER.decode(ctx);

            assertThat(decoded.length()).isEqualTo(3);
            for (int i = 0; i < data.length; i++) {
                assertThat(decoded.getBytes(i)).isEqualTo(data[i].getBytes(StandardCharsets.UTF_8));
            }
        }

        @Test
        void encode_unicodeString_roundTrips() {
            String[] data = {"héllo", "wörld", "日本語"};
            EncodeResult result = ENCODER.encode(DTypes.UTF8, data, EncodeTestHelper.testCtx());
            DecodeContext ctx = DecodeTestHelper.toDecodeContext(result, data.length, DTypes.UTF8, REGISTRY);
            VarBinArray decoded = (VarBinArray) DECODER.decode(ctx);

            assertThat(decoded.length()).isEqualTo(3);
            for (int i = 0; i < data.length; i++) {
                assertThat(decoded.getBytes(i)).isEqualTo(data[i].getBytes(StandardCharsets.UTF_8));
            }
        }

        @Test
        void encode_emptyStringInArray_roundTrips() {
            String[] data = {"a", "", "b"};
            EncodeResult result = ENCODER.encode(DTypes.UTF8, data, EncodeTestHelper.testCtx());
            DecodeContext ctx = DecodeTestHelper.toDecodeContext(result, data.length, DTypes.UTF8, REGISTRY);
            VarBinArray decoded = (VarBinArray) DECODER.decode(ctx);

            assertThat(decoded.length()).isEqualTo(3);
            assertThat(decoded.getBytes(0)).isEqualTo(new byte[]{'a'});
            assertThat(decoded.getBytes(1)).isEmpty();
            assertThat(decoded.getBytes(2)).isEqualTo(new byte[]{'b'});
        }

        @Test
        void encode_emptyArray_producesZeroLengthResult() {
            String[] data = {};
            EncodeResult result = ENCODER.encode(DTypes.UTF8, data, EncodeTestHelper.testCtx());
            DecodeContext ctx = DecodeTestHelper.toDecodeContext(result, data.length, DTypes.UTF8, REGISTRY);
            VarBinArray decoded = (VarBinArray) DECODER.decode(ctx);

            assertThat(decoded.length()).isZero();
        }
    }

    @Nested
    class Decode {

        @Test
        void decode_missingMetadata_throwsVortexException() {
            ArrayNode node = ArrayNode.of(EncodingId.VORTEX_VARBIN, null, new ArrayNode[0], new int[0], null);
            DecodeContext ctx = new DecodeContext(node, DTypes.UTF8, 3, new MemorySegment[0],
                    ReadRegistry.empty(), Arena.ofAuto());
            assertThatThrownBy(() -> DECODER.decode(ctx))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("missing metadata");
        }
    }

    @Nested
    class Metadata {

        @Test
        void encode_utf8_metadata_offsetsPtype_isI64() throws Exception {
            String[] data = {"hello", "world"};
            EncodeResult result = ENCODER.encode(DTypes.UTF8, data, EncodeTestHelper.testCtx());
            var metaSeg = java.lang.foreign.MemorySegment.ofBuffer(result.rootNode().metadata().duplicate());
            VarBinMetadata meta = VarBinMetadata.decode(metaSeg, 0, metaSeg.byteSize());

            assertThat(meta.offsets_ptype().value()).isEqualTo(7);
        }
    }
}
