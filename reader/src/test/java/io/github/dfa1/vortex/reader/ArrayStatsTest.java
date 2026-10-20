package io.github.dfa1.vortex.reader;

import io.github.dfa1.vortex.core.fbs.FbsBuilder;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.proto.ProtoScalarValue;
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
            assertThat(sut.sum()).isNull();
            assertThat(sut.isSorted()).isNull();
            assertThat(sut.isStrictSorted()).isNull();
            assertThat(sut.isConstant()).isNull();
            assertThat(sut.nullCount()).isNull();
            assertThat(sut.uncompressedSizeInBytes()).isNull();
            assertThat(sut.nanCount()).isNull();
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
            io.github.dfa1.vortex.core.fbs.FbsArrayStats fbs = buildFbs(null, null);

            // When
            ArrayStats sut = ArrayStats.fromFbs(fbs);

            // Then
            assertThat(sut).isSameAs(ArrayStats.empty());
        }

        @Test
        void int64Scalar_decodes() {
            // Given
            io.github.dfa1.vortex.core.fbs.FbsArrayStats fbs = buildFbs(
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
            io.github.dfa1.vortex.core.fbs.FbsArrayStats fbs = buildFbs(
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
            io.github.dfa1.vortex.core.fbs.FbsArrayStats fbs = buildFbs(
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
            io.github.dfa1.vortex.core.fbs.FbsArrayStats fbs = buildFbs(
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
            io.github.dfa1.vortex.core.fbs.FbsArrayStats fbs = buildFbs(
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
            io.github.dfa1.vortex.core.fbs.FbsArrayStats fbs = buildFbs(
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
            io.github.dfa1.vortex.core.fbs.FbsArrayStats fbs = buildFbs(
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
            io.github.dfa1.vortex.core.fbs.FbsArrayStats fbs = buildFbs(
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
            io.github.dfa1.vortex.core.fbs.FbsArrayStats fbs = buildFbs(new byte[0], new byte[0]);

            // When
            ArrayStats sut = ArrayStats.fromFbs(fbs);

            // Then
            assertThat(sut).isSameAs(ArrayStats.empty());
        }

        @Test
        void everyNonScalarStat_decodes() {
            // Given — every stat besides min/max (covered above), with false/0 values on purpose:
            // they equal the FlatBuffer scalar defaults, so a reader that skips a field or tests the
            // value instead of its presence reads them as unknown. A lost nan_count = 0 reads as
            // "may hold NaN", which caps a float chunk's max at NaN so it never prunes.
            FbsBuilder builder = new FbsBuilder(128);
            int sumOff = io.github.dfa1.vortex.core.fbs.FbsArrayStats.createSumVector(
                    builder, ProtoScalarValue.ofInt64Value(12L).encode());
            builder.forceDefaults(true);
            io.github.dfa1.vortex.core.fbs.FbsArrayStats.startFbsArrayStats(builder);
            io.github.dfa1.vortex.core.fbs.FbsArrayStats.addSum(builder, sumOff);
            io.github.dfa1.vortex.core.fbs.FbsArrayStats.addIsSorted(builder, true);
            io.github.dfa1.vortex.core.fbs.FbsArrayStats.addIsStrictSorted(builder, false);
            io.github.dfa1.vortex.core.fbs.FbsArrayStats.addIsConstant(builder, false);
            io.github.dfa1.vortex.core.fbs.FbsArrayStats.addNullCount(builder, 0L);
            io.github.dfa1.vortex.core.fbs.FbsArrayStats.addUncompressedSizeInBytes(builder, 800L);
            io.github.dfa1.vortex.core.fbs.FbsArrayStats.addNanCount(builder, 0L);
            builder.finish(io.github.dfa1.vortex.core.fbs.FbsArrayStats.endFbsArrayStats(builder));
            var fbs = io.github.dfa1.vortex.core.fbs.FbsArrayStats.getRootAsFbsArrayStats(builder.dataSegment());

            // When
            ArrayStats result = ArrayStats.fromFbs(fbs);

            // Then
            assertThat(result).isEqualTo(
                    new ArrayStats(null, false, null, false, 12L, true, false, false, 0L, 800L, 0L));
        }

        @Test
        void exactPrecision_marksBoundsExact() {
            // Given
            io.github.dfa1.vortex.core.fbs.FbsArrayStats fbs = buildFbs(
                    ProtoScalarValue.ofStringValue("alpha").encode(),
                    ProtoScalarValue.ofStringValue("omega").encode(),
                    (int) io.github.dfa1.vortex.core.fbs.FbsPrecision.Exact);

            // When
            ArrayStats result = ArrayStats.fromFbs(fbs);

            // Then
            assertThat(result.minExact()).isTrue();
            assertThat(result.maxExact()).isTrue();
        }

        @Test
        void absentPrecision_readsInexact() {
            // Given — no precision written: the FlatBuffer default is Inexact, as Rust reads it. A
            // truncated 64-byte string bound must not be reported as the column's real MAX.
            io.github.dfa1.vortex.core.fbs.FbsArrayStats fbs = buildFbs(
                    ProtoScalarValue.ofStringValue("alpha").encode(),
                    ProtoScalarValue.ofStringValue("omega").encode());

            // When
            ArrayStats result = ArrayStats.fromFbs(fbs);

            // Then
            assertThat(result.min()).isEqualTo("alpha");
            assertThat(result.minExact()).isFalse();
            assertThat(result.max()).isEqualTo("omega");
            assertThat(result.maxExact()).isFalse();
        }

        @Test
        void corruptedPrecision_throwsVortexException() {
            // Given — only Inexact (0) and Exact (1) exist; Rust bails on anything else
            io.github.dfa1.vortex.core.fbs.FbsArrayStats fbs = buildFbs(
                    ProtoScalarValue.ofInt64Value(1L).encode(),
                    ProtoScalarValue.ofInt64Value(2L).encode(),
                    2);

            // When / Then
            assertThatThrownBy(() -> ArrayStats.fromFbs(fbs))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("corrupted min_precision");
        }

        @Test
        void malformedScalarBytes_throwsVortexException() {
            // Given — varint tag with no continuation byte: ProtoReader hits EOF inside readVarint
            io.github.dfa1.vortex.core.fbs.FbsArrayStats fbs = buildFbs(new byte[]{(byte) 0x80}, null);

            // When / Then
            assertThatThrownBy(() -> ArrayStats.fromFbs(fbs))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("invalid scalar value");
        }
    }

    private static io.github.dfa1.vortex.core.fbs.FbsArrayStats buildFbs(byte[] minBytes, byte[] maxBytes) {
        return buildFbs(minBytes, maxBytes, null);
    }

    /// `precision` is written to both `min_precision` and `max_precision`, or left absent when `null`.
    private static io.github.dfa1.vortex.core.fbs.FbsArrayStats buildFbs(byte[] minBytes, byte[] maxBytes,
                                                                         Integer precision) {
        FbsBuilder b = new FbsBuilder(64);
        int minOff = minBytes == null ? 0 : io.github.dfa1.vortex.core.fbs.FbsArrayStats.createMinVector(b, minBytes);
        int maxOff = maxBytes == null ? 0 : io.github.dfa1.vortex.core.fbs.FbsArrayStats.createMaxVector(b, maxBytes);
        io.github.dfa1.vortex.core.fbs.FbsArrayStats.startFbsArrayStats(b);
        if (minBytes != null) {
            io.github.dfa1.vortex.core.fbs.FbsArrayStats.addMin(b, minOff);
        }
        if (maxBytes != null) {
            io.github.dfa1.vortex.core.fbs.FbsArrayStats.addMax(b, maxOff);
        }
        if (precision != null) {
            io.github.dfa1.vortex.core.fbs.FbsArrayStats.addMinPrecision(b, precision);
            io.github.dfa1.vortex.core.fbs.FbsArrayStats.addMaxPrecision(b, precision);
        }
        int root = io.github.dfa1.vortex.core.fbs.FbsArrayStats.endFbsArrayStats(b);
        b.finish(root);
        return io.github.dfa1.vortex.core.fbs.FbsArrayStats.getRootAsFbsArrayStats(b.dataSegment());
    }
}
