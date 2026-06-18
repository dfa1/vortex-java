package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.encoding.EncodingId;
import io.github.dfa1.vortex.proto.Primitive;
import io.github.dfa1.vortex.proto.Scalar;
import io.github.dfa1.vortex.proto.ScalarValue;
import io.github.dfa1.vortex.proto.VariantMetadata;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VariantEncodingEncoderTest {

    private static final VariantEncodingEncoder SUT = new VariantEncodingEncoder();

    private static Scalar i32Scalar(long value) {
        // Inner typed scalar carrying its own i32 dtype, wrapped as a variant value
        // (mirrors Rust Scalar::variant(Scalar::primitive(value))).
        return new Scalar(
                io.github.dfa1.vortex.proto.DType.ofPrimitive(
                        new Primitive(io.github.dfa1.vortex.proto.PType.I32, false)),
                ScalarValue.ofInt64Value(value));
    }

    @Nested
    class Accepts {

        @Test
        void trueForVariant_falseForPrimitive() {
            assertThat(SUT.accepts(new DType.Variant(false))).isTrue();
            assertThat(SUT.accepts(new DType.Primitive(io.github.dfa1.vortex.core.PType.I64, false))).isFalse();
        }
    }

    @Nested
    class Encode {

        @Test
        void constantColumn_emitsVariantContainerOverConstantChild() throws Exception {
            // Given
            DType.Variant dtype = new DType.Variant(false);
            VariantData data = new VariantData(5, i32Scalar(7L));

            // When
            EncodeResult result = SUT.encode(dtype, data, EncodeTestHelper.testCtx());

            // Then — container node holds exactly one buffer-backed constant child, no buffers of its own.
            EncodeNode root = result.rootNode();
            assertThat(root.encodingId()).isEqualTo(EncodingId.VORTEX_VARIANT);
            assertThat(root.bufferIndices()).isEmpty();
            assertThat(root.children()).hasSize(1);

            EncodeNode child = root.children()[0];
            assertThat(child.encodingId()).isEqualTo(EncodingId.VORTEX_CONSTANT);
            assertThat(child.bufferIndices()).containsExactly(0);
            assertThat(result.buffers()).hasSize(1);
        }

        @Test
        void constantColumn_metadataHasNoShreddedDtype() throws Exception {
            // Given a Layer-A constant column (no shredding)
            VariantData data = new VariantData(3, i32Scalar(7L));

            // When
            EncodeResult result = SUT.encode(new DType.Variant(false), data, EncodeTestHelper.testCtx());

            // Then the container metadata decodes to VariantMetadata with absent shredded_dtype.
            MemorySegment meta = MemorySegment.ofBuffer(result.rootNode().metadata().duplicate());
            VariantMetadata decoded = VariantMetadata.decode(meta, 0, meta.byteSize());
            assertThat(decoded.shredded_dtype()).isNull();
        }

        @Test
        void constantColumn_childBufferIsVariantScalar() throws Exception {
            // Given
            VariantData data = new VariantData(3, i32Scalar(42L));

            // When
            EncodeResult result = SUT.encode(new DType.Variant(false), data, EncodeTestHelper.testCtx());

            // Then the constant child's buffer is a ScalarValue wrapping the inner i32 variant value.
            MemorySegment buf = result.buffers().get(0);
            ScalarValue scalar = ScalarValue.decode(buf, 0, buf.byteSize());
            assertThat(scalar.variant_value()).isNotNull();
            assertThat(scalar.variant_value().value().int64_value()).isEqualTo(42L);
        }

        @Test
        void wrongDtype_throws() {
            VariantData data = new VariantData(1, i32Scalar(1L));
            assertThatThrownBy(() -> SUT.encode(
                    new DType.Primitive(io.github.dfa1.vortex.core.PType.I64, false), data, EncodeTestHelper.testCtx()))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("Variant dtype");
        }

        @Test
        void wrongDataType_throws() {
            assertThatThrownBy(() -> SUT.encode(
                    new DType.Variant(false), new long[]{1L}, EncodeTestHelper.testCtx()))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("VariantData");
        }
    }

    @Nested
    class Validation {

        @Test
        void negativeLength_throws() {
            assertThatThrownBy(() -> new VariantData(-1, i32Scalar(1L)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("length");
        }

        @Test
        void nullConstantValue_throws() {
            assertThatThrownBy(() -> new VariantData(1, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("constantValue");
        }
    }
}
