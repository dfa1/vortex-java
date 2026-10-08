package io.github.dfa1.vortex.writer;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.writer.encode.NullableData;
import io.github.dfa1.vortex.writer.encode.StructData;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class ZoneAccumulatorTest {

    @Nested
    class Zones {

        @Test
        void zonesFollowTheRowStrideNotTheBatches() {
            // Given batches that do not divide 8192: zone 0 straddles batches 1-2, zone 1 batches
            // 2-3. Rust zones by row (r / 8192), so a zone must close mid-batch and carry over.
            ZoneAccumulator sut = new ZoneAccumulator(DType.I64);
            long next = 0;
            for (int len : new int[]{5000, 5000, 7000}) {
                long[] batch = new long[len];
                for (int i = 0; i < len; i++) {
                    batch[i] = next++;
                }
                sut.add(batch, len);
            }

            // When
            sut.finish();
            StructData result = sut.tableData();

            // Then — values equal row numbers, so each zone's min/max is its first/last row
            assertThat(sut.zoneCount()).isEqualTo(3);
            assertThat(values(result, 0)).isEqualTo(new long[]{8191, 16383, 16999});
            assertThat(values(result, 1)).isEqualTo(new long[]{0, 8192, 16384});
            assertThat(values(result, 2)).isEqualTo(new long[]{0, 0, 0});
        }

        @Test
        void allNullZoneHasNullMinMaxAndCountsItsNulls() {
            // Given one zone of nulls only: Rust stores a null min/max partial and the null count
            ZoneAccumulator sut = new ZoneAccumulator(DType.I64.withNullable(true));
            sut.add(new NullableData(new long[3], new boolean[3]), 3);

            // When
            sut.finish();
            StructData result = sut.tableData();

            // Then
            assertThat(((NullableData) result.fieldArrays().get(0)).validity()).containsExactly(false);
            assertThat(values(result, 2)).isEqualTo(new long[]{3});
        }

        @Test
        void floatsSkipNanInMinMaxAndCountIt() {
            // Given a leading NaN — Rust's max/min skip NaN and record them in nan_count
            ZoneAccumulator sut = new ZoneAccumulator(DType.F64);
            sut.add(new double[]{Double.NaN, 2.0, -1.0, Double.NaN}, 4);

            // When
            sut.finish();
            StructData result = sut.tableData();

            // Then — fields: max, min, nan_count, null_count
            assertThat((double[]) ((NullableData) result.fieldArrays().get(0)).values()).containsExactly(2.0);
            assertThat((double[]) ((NullableData) result.fieldArrays().get(1)).values()).containsExactly(-1.0);
            assertThat(values(result, 2)).isEqualTo(new long[]{2});
        }

        @Test
        void listColumnRecordsOnlyNullCount() {
            // Given a dtype with no min/max: Rust records just null_count
            DType list = new DType.List(DType.I32, true);
            ZoneAccumulator sut = new ZoneAccumulator(list);
            sut.add(new NullableData(new Object(), new boolean[]{true, false}), 2);

            // When
            sut.finish();

            // Then
            assertThat(sut.aggregateIds()).containsExactly("vortex.null_count");
            assertThat(values(sut.tableData(), 0)).isEqualTo(new long[]{1});
        }
    }

    @Nested
    class Metadata {

        @Test
        void floatMetadataIsByteIdenticalToVortexJni() {
            // Given the zoned metadata vortex-jni 0.86.1 writes for an F64 column
            String jni = "0108804012100a0a766f727465782e6d61781202080112100a0a766f727465782e6d696e1202080112"
                    + "120a10766f727465782e6e616e5f636f756e7412130a11766f727465782e6e756c6c5f636f756e74";

            // When
            byte[] result = new ZoneAccumulator(DType.F64).metadata();

            // Then
            assertThat(HexFormat.of().formatHex(result)).isEqualTo(jni);
        }

        @Test
        void utf8MetadataIsByteIdenticalToVortexJni() {
            // Given the zoned metadata vortex-jni 0.86.1 writes for a Utf8 column
            String jni = "01088040121e0a12766f727465782e626f756e6465645f6d617812084000000000000000121e0a12766f"
                    + "727465782e626f756e6465645f6d696e1208400000000000000012130a11766f727465782e6e756c6c"
                    + "5f636f756e74";

            // When
            byte[] result = new ZoneAccumulator(DType.UTF8).metadata();

            // Then
            assertThat(HexFormat.of().formatHex(result)).isEqualTo(jni);
        }
    }

    @Nested
    class Truncation {

        @Test
        void shortValueIsItsOwnBound() {
            // Given a value within 64 bytes: Rust stores it untruncated
            byte[] value = "abc".getBytes(StandardCharsets.UTF_8);

            // When
            byte[] result = ZoneAccumulator.utf8UpperBound(value);

            // Then
            assertThat(result).isEqualTo(value);
        }

        @Test
        void longUtf8UpperBoundIncrementsTheLastCharOfThePrefix() {
            // Given 70 'a's: cut to 64 bytes, then the last char bumped so the bound sorts above
            byte[] value = "a".repeat(70).getBytes(StandardCharsets.UTF_8);

            // When
            byte[] upper = ZoneAccumulator.utf8UpperBound(value);
            byte[] lower = ZoneAccumulator.utf8LowerBound(value);

            // Then
            assertThat(new String(upper, StandardCharsets.UTF_8)).isEqualTo("a".repeat(63) + "b");
            assertThat(new String(lower, StandardCharsets.UTF_8)).isEqualTo("a".repeat(64));
        }

        @Test
        void utf8CutStopsAtACharacterBoundary() {
            // Given 63 ASCII bytes then a 3-byte char straddling byte 64: the cut must not split it
            byte[] value = ("a".repeat(63) + "€" + "zz").getBytes(StandardCharsets.UTF_8);

            // When
            byte[] result = ZoneAccumulator.utf8LowerBound(value);

            // Then
            assertThat(result).hasSize(63);
        }

        @Test
        void binaryUpperBoundCarriesAndIsUnknownWhenEveryByteOverflows() {
            // Given 70 bytes of 0xFF: incrementing overflows every byte of the 64-byte prefix, so
            // no upper bound fits and Rust marks the zone's bounded_max unknown
            byte[] value = new byte[70];
            Arrays.fill(value, (byte) 0xFF);

            // When
            byte[] result = ZoneAccumulator.binaryUpperBound(value);

            // Then
            assertThat(result).isNull();
        }
    }

    private static long[] values(StructData table, int field) {
        Object f = table.fieldArrays().get(field);
        return (long[]) (f instanceof NullableData nd ? nd.values() : f);
    }
}
