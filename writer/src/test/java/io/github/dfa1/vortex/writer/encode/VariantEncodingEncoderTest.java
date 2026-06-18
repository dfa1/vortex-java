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
import java.nio.ByteOrder;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VariantEncodingEncoderTest {

    private static final VariantEncodingEncoder SUT = new VariantEncodingEncoder();
    private static final DType.Variant VARIANT = new DType.Variant(false);

    private static Scalar i32Scalar(long value) {
        // Inner typed scalar carrying its own i32 dtype, wrapped as a variant value
        // (mirrors Rust Scalar::variant(Scalar::primitive(value))).
        return new Scalar(
                io.github.dfa1.vortex.proto.DType.ofPrimitive(
                        new Primitive(io.github.dfa1.vortex.proto.PType.I32, false)),
                ScalarValue.ofInt64Value(value));
    }

    private static long innerInt(MemorySegment buf) throws Exception {
        ScalarValue scalar = ScalarValue.decode(buf, 0, buf.byteSize());
        assertThat(scalar.variant_value()).isNotNull();
        return scalar.variant_value().value().int64_value();
    }

    @Nested
    class Accepts {

        @Test
        void trueForVariant_falseForPrimitive() {
            assertThat(SUT.accepts(VARIANT)).isTrue();
            assertThat(SUT.accepts(new DType.Primitive(io.github.dfa1.vortex.core.PType.I64, false))).isFalse();
        }
    }

    @Nested
    class ConstantColumn {

        @Test
        void allEqual_emitsSingleConstantChild() throws Exception {
            // Given a column whose rows are all the same value
            VariantData data = VariantData.constant(5, i32Scalar(7L));

            // When
            EncodeResult result = SUT.encode(VARIANT, data, EncodeTestHelper.testCtx());

            // Then — container holds exactly one buffer-backed constant child, no chunked layer.
            EncodeNode root = result.rootNode();
            assertThat(root.encodingId()).isEqualTo(EncodingId.VORTEX_VARIANT);
            assertThat(root.bufferIndices()).isEmpty();
            assertThat(root.children()).hasSize(1);

            EncodeNode child = root.children()[0];
            assertThat(child.encodingId()).isEqualTo(EncodingId.VORTEX_CONSTANT);
            assertThat(child.bufferIndices()).containsExactly(0);
            assertThat(result.buffers()).hasSize(1);
            assertThat(innerInt(result.buffers().get(0))).isEqualTo(7L);
        }

        @Test
        void metadataHasNoShreddedDtype() throws Exception {
            EncodeResult result = SUT.encode(VARIANT, VariantData.constant(3, i32Scalar(7L)), EncodeTestHelper.testCtx());

            MemorySegment meta = MemorySegment.ofBuffer(result.rootNode().metadata().duplicate());
            VariantMetadata decoded = VariantMetadata.decode(meta, 0, meta.byteSize());
            assertThat(decoded.shredded_dtype()).isNull();
        }
    }

    @Nested
    class VaryingColumn {

        @Test
        void distinctValues_emitChunkedOfConstants() throws Exception {
            // Given three distinct per-row values
            VariantData data = new VariantData(List.of(i32Scalar(7L), i32Scalar(8L), i32Scalar(9L)));

            // When
            EncodeResult result = SUT.encode(VARIANT, data, EncodeTestHelper.testCtx());

            // Then — container wraps a chunked node: child 0 is the offsets, then one constant per run.
            EncodeNode chunked = result.rootNode().children()[0];
            assertThat(chunked.encodingId()).isEqualTo(EncodingId.VORTEX_CHUNKED);
            assertThat(chunked.children()).hasSize(4);
            assertThat(chunked.children()[0].encodingId()).isEqualTo(EncodingId.VORTEX_PRIMITIVE);
            for (int i = 1; i <= 3; i++) {
                assertThat(chunked.children()[i].encodingId()).isEqualTo(EncodingId.VORTEX_CONSTANT);
            }
            // offsets buffer (index 0) + one buffer per constant = 4 total
            assertThat(result.buffers()).hasSize(4);
        }

        @Test
        void distinctValues_offsetsAreCumulativeRunLengths() {
            // Given one row per distinct value: run offsets must be 0,1,2,3
            VariantData data = new VariantData(List.of(i32Scalar(7L), i32Scalar(8L), i32Scalar(9L)));

            EncodeResult result = SUT.encode(VARIANT, data, EncodeTestHelper.testCtx());

            MemorySegment offsets = result.buffers().get(0);
            var bb = offsets.asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
            assertThat(bb.getLong(0)).isZero();
            assertThat(bb.getLong(8)).isEqualTo(1L);
            assertThat(bb.getLong(16)).isEqualTo(2L);
            assertThat(bb.getLong(24)).isEqualTo(3L);
        }

        @Test
        void adjacentEqualValues_coalesceIntoOneRun() throws Exception {
            // Given [7,7,8]: two runs (7 length 2, 8 length 1) → offsets 0,2,3
            VariantData data = new VariantData(List.of(i32Scalar(7L), i32Scalar(7L), i32Scalar(8L)));

            EncodeResult result = SUT.encode(VARIANT, data, EncodeTestHelper.testCtx());

            EncodeNode chunked = result.rootNode().children()[0];
            assertThat(chunked.children()).hasSize(3); // offsets + 2 constants
            assertThat(result.buffers()).hasSize(3);

            MemorySegment offsets = result.buffers().get(0);
            var bb = offsets.asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
            assertThat(bb.getLong(0)).isZero();
            assertThat(bb.getLong(8)).isEqualTo(2L);
            assertThat(bb.getLong(16)).isEqualTo(3L);
            // run values preserved in order
            assertThat(innerInt(result.buffers().get(1))).isEqualTo(7L);
            assertThat(innerInt(result.buffers().get(2))).isEqualTo(8L);
        }
    }

    @Nested
    class Errors {

        @Test
        void wrongDtype_throws() {
            VariantData data = VariantData.constant(1, i32Scalar(1L));
            assertThatThrownBy(() -> SUT.encode(
                    new DType.Primitive(io.github.dfa1.vortex.core.PType.I64, false), data, EncodeTestHelper.testCtx()))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("Variant dtype");
        }

        @Test
        void wrongDataType_throws() {
            assertThatThrownBy(() -> SUT.encode(VARIANT, new long[]{1L}, EncodeTestHelper.testCtx()))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("VariantData");
        }
    }

    @Nested
    class Validation {

        @Test
        void emptyValues_throws() {
            assertThatThrownBy(() -> new VariantData(List.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("empty");
        }

        @Test
        void constant_nonPositiveLength_throws() {
            assertThatThrownBy(() -> VariantData.constant(0, i32Scalar(1L)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("length");
        }

        @Test
        void constant_nullValue_throws() {
            assertThatThrownBy(() -> VariantData.constant(1, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("value");
        }
    }
}
