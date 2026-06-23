package io.github.dfa1.vortex.writer;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.writer.encode.NullableData;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChunkImplTest {

    private static DType.Struct schema(DType dtype) {
        return new DType.Struct(List.of("c"), List.of(dtype), false);
    }

    private static DType prim(PType ptype, boolean nullable) {
        return new DType.Primitive(ptype, nullable);
    }

    private static Object putGet(DType dtype, Object value) {
        ChunkImpl sut = new ChunkImpl(schema(dtype));
        sut.put("c", value);
        return sut.finish().get("c");
    }

    @Nested
    class PutAndFinish {

        @Test
        void unknownColumnRejected() {
            // Given
            ChunkImpl sut = new ChunkImpl(schema(prim(PType.I32, false)));

            // When / Then
            assertThatThrownBy(() -> sut.put("nope", new int[]{1}))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unknown column");
        }

        @Test
        void duplicatePutRejected() {
            // Given
            ChunkImpl sut = new ChunkImpl(schema(prim(PType.I32, false)));
            sut.put("c", new int[]{1});

            // When / Then
            assertThatThrownBy(() -> sut.put("c", new int[]{2}))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("duplicate");
        }

        @Test
        void putReturnsSelfForChaining() {
            // Given
            ChunkImpl sut = new ChunkImpl(schema(prim(PType.I32, false)));

            // When
            Chunk result = sut.put("c", new int[]{1});

            // Then
            assertThat(result).isSameAs(sut);
        }

        @Test
        void finishRejectsMissingColumn() {
            // Given — schema has two columns, only one put
            ChunkImpl sut = new ChunkImpl(new DType.Struct(
                    List.of("a", "b"), List.of(prim(PType.I32, false), prim(PType.I32, false)), false));
            sut.put("a", new int[]{1});

            // When / Then
            assertThatThrownBy(sut::finish)
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("missing column");
        }

        @Test
        void finishReturnsAllColumns() {
            // Given
            ChunkImpl sut = new ChunkImpl(schema(prim(PType.I32, false)));
            int[] col = {1, 2, 3};
            sut.put("c", col);

            // When
            Map<String, Object> result = sut.finish();

            // Then
            assertThat(result).containsEntry("c", col);
        }

        @Test
        void nullValueRejected() {
            // When / Then
            assertThatThrownBy(() -> putGet(prim(PType.I32, false), null))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("null array");
        }

        @Test
        void unadaptedDtypePassesThrough() {
            // Given — a List dtype is not specially adapted; its carrier passes through unchanged
            DType listDtype = new DType.List(prim(PType.I32, false), false);
            Object carrier = new Object();

            // When
            Object result = putGet(listDtype, carrier);

            // Then
            assertThat(result).isSameAs(carrier);
        }
    }

    @Nested
    class Primitive {

        @Test
        void plainArraysAcceptedForEveryPType() {
            // Given / When / Then — the matching primitive array passes through
            assertThat(putGet(prim(PType.I8, false), new byte[]{1})).isInstanceOf(byte[].class);
            assertThat(putGet(prim(PType.I16, false), new short[]{1})).isInstanceOf(short[].class);
            assertThat(putGet(prim(PType.I32, false), new int[]{1})).isInstanceOf(int[].class);
            assertThat(putGet(prim(PType.I64, false), new long[]{1})).isInstanceOf(long[].class);
            assertThat(putGet(prim(PType.F32, false), new float[]{1})).isInstanceOf(float[].class);
            assertThat(putGet(prim(PType.F64, false), new double[]{1})).isInstanceOf(double[].class);
            assertThat(putGet(prim(PType.F16, false), new short[]{1})).isInstanceOf(short[].class);
        }

        @Test
        void boxedArraysConvertToNullableDataOnNullableColumns() {
            // Given / When / Then — null slots become invalid in the NullableData carrier
            assertValidity(putGet(prim(PType.I8, true), new Byte[]{1, null}));
            assertValidity(putGet(prim(PType.I16, true), new Short[]{1, null}));
            assertValidity(putGet(prim(PType.I32, true), new Integer[]{1, null}));
            assertValidity(putGet(prim(PType.I64, true), new Long[]{1L, null}));
            assertValidity(putGet(prim(PType.F32, true), new Float[]{1f, null}));
            assertValidity(putGet(prim(PType.F64, true), new Double[]{1.0, null}));
        }

        @Test
        void boxedArraysRejectedOnNonNullableColumns() {
            // Each must hit rejectNullable (the "rejects boxed array" message), not the
            // generic typeMismatch — asserting the message keeps these on the boxed arm.
            assertRejectsBoxed(prim(PType.I8, false), new Byte[]{1});
            assertRejectsBoxed(prim(PType.I16, false), new Short[]{1});
            assertRejectsBoxed(prim(PType.I32, false), new Integer[]{1});
            assertRejectsBoxed(prim(PType.I64, false), new Long[]{1L});
            assertRejectsBoxed(prim(PType.F32, false), new Float[]{1f});
            assertRejectsBoxed(prim(PType.F64, false), new Double[]{1.0});
        }

        private void assertRejectsBoxed(DType dtype, Object boxed) {
            assertThatThrownBy(() -> putGet(dtype, boxed))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("rejects boxed array");
        }

        @Test
        void wrongTypeRejectedForEveryPType() {
            for (PType p : List.of(PType.I8, PType.I16, PType.I32, PType.I64, PType.F32, PType.F64, PType.F16)) {
                assertThatThrownBy(() -> putGet(prim(p, false), "not an array"))
                        .as("ptype %s", p)
                        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("expects");
            }
        }

        private void assertValidity(Object result) {
            assertThat(result).isInstanceOf(NullableData.class);
            assertThat(((NullableData) result).validity()).containsExactly(true, false);
        }
    }

    @Nested
    class Utf8 {

        @Test
        void stringArrayAccepted() {
            assertThat(putGet(DType.UTF8, new String[]{"a", "b"})).isInstanceOf(String[].class);
        }

        @Test
        void nullableAllowsNullElements() {
            assertThat(putGet(new DType.Utf8(true), new String[]{"a", null})).isInstanceOf(String[].class);
        }

        @Test
        void nonNullableRejectsNullElement() {
            assertThatThrownBy(() -> putGet(DType.UTF8, new String[]{"a", null}))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("null at row 1");
        }

        @Test
        void wrongTypeRejected() {
            assertThatThrownBy(() -> putGet(DType.UTF8, new int[]{1}))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("expects String[]");
        }
    }

    @Nested
    class Bool {

        @Test
        void boolArrayAccepted() {
            assertThat(putGet(DType.BOOL, new boolean[]{true, false})).isInstanceOf(boolean[].class);
        }

        @Test
        void boxedConvertsToNullableDataOnNullableColumn() {
            // Given / When
            Object result = putGet(new DType.Bool(true), new Boolean[]{true, null, false});

            // Then
            assertThat(result).isInstanceOf(NullableData.class);
            assertThat(((NullableData) result).validity()).containsExactly(true, false, true);
        }

        @Test
        void boxedRejectedOnNonNullableColumn() {
            assertThatThrownBy(() -> putGet(DType.BOOL, new Boolean[]{true}))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("rejects Boolean[]");
        }

        @Test
        void wrongTypeRejected() {
            assertThatThrownBy(() -> putGet(DType.BOOL, new int[]{1}))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("expects boolean[]");
        }
    }
}
