package io.github.dfa1.vortex.reader;

import io.github.dfa1.vortex.fbs.FbsBuilder;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.proto.ProtoScalarValue;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;


import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArrayStatsTest {

    @Nested
    class Empty {

        @Test
        void returnsCanonicalInstance() {
            // Given / When / Then — empty() is a singleton; callers may identity-compare.
            assertThat(ArrayStats.empty()).isSameAs(ArrayStats.empty());
        }

        @Test
        void hasAllNullComponents() {
            // Given / When
            ArrayStats sut = ArrayStats.empty();

            // Then
            assertThat(sut.min()).isNull();
            assertThat(sut.max()).isNull();
            assertThat(sut.trueCount()).isNull();
            assertThat(sut.nullCount()).isNull();
            assertThat(sut.isSorted()).isNull();
            assertThat(sut.isStrictSorted()).isNull();
        }
    }

    @Nested
    class FromFbs {

        @Test
        void nullFbs_returnsEmpty() {
            // Given / When
            ArrayStats sut = ArrayStats.fromFbs(null);

            // Then — same instance as empty(), not just an equal one
            assertThat(sut).isSameAs(ArrayStats.empty());
        }

        @Test
        void fbsWithNoMinOrMax_returnsEmpty() {
            // Given — ArrayStats table with no min/max byte vectors set
            io.github.dfa1.vortex.fbs.FbsArrayStats fbs = buildFbs(null, null);

            // When
            ArrayStats sut = ArrayStats.fromFbs(fbs);

            // Then
            assertThat(sut).isSameAs(ArrayStats.empty());
        }

        @Test
        void int64Scalar_decodes() {
            // Given
            io.github.dfa1.vortex.fbs.FbsArrayStats fbs = buildFbs(
                    ProtoScalarValue.ofInt64Value(-7L).encode(),
                    ProtoScalarValue.ofInt64Value(42L).encode());

            // When
            ArrayStats sut = ArrayStats.fromFbs(fbs);

            // Then
            assertThat(sut.min()).isEqualTo(-7L);
            assertThat(sut.max()).isEqualTo(42L);
        }

        @Test
        void uint64Scalar_decodes() {
            // Given
            io.github.dfa1.vortex.fbs.FbsArrayStats fbs = buildFbs(
                    ProtoScalarValue.ofUint64Value(0L).encode(),
                    ProtoScalarValue.ofUint64Value(Long.MAX_VALUE).encode());

            // When
            ArrayStats sut = ArrayStats.fromFbs(fbs);

            // Then
            assertThat(sut.min()).isEqualTo(0L);
            assertThat(sut.max()).isEqualTo(Long.MAX_VALUE);
        }

        @Test
        void f32Scalar_decodes() {
            // Given
            io.github.dfa1.vortex.fbs.FbsArrayStats fbs = buildFbs(
                    ProtoScalarValue.ofF32Value(1.5f).encode(),
                    ProtoScalarValue.ofF32Value(3.25f).encode());

            // When
            ArrayStats sut = ArrayStats.fromFbs(fbs);

            // Then
            assertThat(sut.min()).isEqualTo(1.5f);
            assertThat(sut.max()).isEqualTo(3.25f);
        }

        @Test
        void f64Scalar_decodes() {
            // Given
            io.github.dfa1.vortex.fbs.FbsArrayStats fbs = buildFbs(
                    ProtoScalarValue.ofF64Value(-0.5).encode(),
                    ProtoScalarValue.ofF64Value(99.875).encode());

            // When
            ArrayStats sut = ArrayStats.fromFbs(fbs);

            // Then
            assertThat(sut.min()).isEqualTo(-0.5);
            assertThat(sut.max()).isEqualTo(99.875);
        }

        @Test
        void boolScalar_decodes() {
            // Given
            io.github.dfa1.vortex.fbs.FbsArrayStats fbs = buildFbs(
                    ProtoScalarValue.ofBoolValue(false).encode(),
                    ProtoScalarValue.ofBoolValue(true).encode());

            // When
            ArrayStats sut = ArrayStats.fromFbs(fbs);

            // Then
            assertThat(sut.min()).isEqualTo(false);
            assertThat(sut.max()).isEqualTo(true);
        }

        @Test
        void stringScalar_decodes() {
            // Given
            io.github.dfa1.vortex.fbs.FbsArrayStats fbs = buildFbs(
                    ProtoScalarValue.ofStringValue("alpha").encode(),
                    ProtoScalarValue.ofStringValue("omega").encode());

            // When
            ArrayStats sut = ArrayStats.fromFbs(fbs);

            // Then
            assertThat(sut.min()).isEqualTo("alpha");
            assertThat(sut.max()).isEqualTo("omega");
        }

        @Test
        void bytesScalar_decodesAsUtf8String() {
            // Given — bytes scalar surfaces as UTF-8 String for stat display purposes.
            // This is the contract zone-map pruning relies on (string compare across bytes/utf8).
            io.github.dfa1.vortex.fbs.FbsArrayStats fbs = buildFbs(
                    ProtoScalarValue.ofBytesValue("aa".getBytes()).encode(),
                    ProtoScalarValue.ofBytesValue("zz".getBytes()).encode());

            // When
            ArrayStats sut = ArrayStats.fromFbs(fbs);

            // Then
            assertThat(sut.min()).isEqualTo("aa");
            assertThat(sut.max()).isEqualTo("zz");
        }

        @Test
        void minOnly_setsMaxToNull() {
            // Given
            io.github.dfa1.vortex.fbs.FbsArrayStats fbs = buildFbs(
                    ProtoScalarValue.ofInt64Value(1L).encode(), null);

            // When
            ArrayStats sut = ArrayStats.fromFbs(fbs);

            // Then
            assertThat(sut.min()).isEqualTo(1L);
            assertThat(sut.max()).isNull();
        }

        @Test
        void emptyByteVector_treatedAsAbsent() {
            // Given — zero-length min vector is structurally present but carries no scalar
            io.github.dfa1.vortex.fbs.FbsArrayStats fbs = buildFbs(new byte[0], new byte[0]);

            // When
            ArrayStats sut = ArrayStats.fromFbs(fbs);

            // Then
            assertThat(sut).isSameAs(ArrayStats.empty());
        }

        @Test
        void malformedScalarBytes_throwsVortexException() {
            // Given — varint tag with no continuation byte: ProtoReader hits EOF inside readVarint
            io.github.dfa1.vortex.fbs.FbsArrayStats fbs = buildFbs(new byte[]{(byte) 0x80}, null);

            // When / Then
            assertThatThrownBy(() -> ArrayStats.fromFbs(fbs))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("invalid scalar value");
        }
    }

    private static io.github.dfa1.vortex.fbs.FbsArrayStats buildFbs(byte[] minBytes, byte[] maxBytes) {
        FbsBuilder b = new FbsBuilder(64);
        int minOff = minBytes == null ? 0 : io.github.dfa1.vortex.fbs.FbsArrayStats.createMinVector(b, minBytes);
        int maxOff = maxBytes == null ? 0 : io.github.dfa1.vortex.fbs.FbsArrayStats.createMaxVector(b, maxBytes);
        io.github.dfa1.vortex.fbs.FbsArrayStats.startFbsArrayStats(b);
        if (minBytes != null) {
            io.github.dfa1.vortex.fbs.FbsArrayStats.addMin(b, minOff);
        }
        if (maxBytes != null) {
            io.github.dfa1.vortex.fbs.FbsArrayStats.addMax(b, maxOff);
        }
        int root = io.github.dfa1.vortex.fbs.FbsArrayStats.endFbsArrayStats(b);
        b.finish(root);
        return io.github.dfa1.vortex.fbs.FbsArrayStats.getRootAsFbsArrayStats(b.dataSegment());
    }
}
