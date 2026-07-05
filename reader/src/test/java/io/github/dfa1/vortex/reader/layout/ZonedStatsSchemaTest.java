package io.github.dfa1.vortex.reader.layout;


import java.lang.foreign.MemorySegment;

import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ZonedStatsSchemaTest {

    @Nested
    class ZoneLength {
        @Test
        void readsLittleEndianU32() {
            // Given — 8192 = 0x2000 stored as LE u32
            MemorySegment meta = MemorySegment.ofArray(new byte[4]);
            meta.set(io.github.dfa1.vortex.core.io.VortexFormat.LE_INT, 0, 8192);

            // When
            long result = ZonedStatsSchema.zoneLength(meta);

            // Then
            assertThat(result).isEqualTo(8192L);
        }

        @Test
        void returnsZeroForNullMetadata() {
            // Given / When
            long result = ZonedStatsSchema.zoneLength(null);

            // Then — nothing to decode; caller treats as "no zones"
            assertThat(result).isZero();
        }

        @Test
        void returnsZeroForShortMetadata() {
            // Given — only 2 bytes (insufficient for a u32)
            MemorySegment meta = MemorySegment.ofArray(new byte[2]);

            // When
            long result = ZonedStatsSchema.zoneLength(meta);

            // Then — defensively zero rather than throwing
            assertThat(result).isZero();
        }
    }

    @Nested
    class PresentStats {
        @Test
        void decodesBitsetLsbFirst() {
            // Given — bits for Min (4) and Max (3) set: 0b0001_1000 = 0x18
            MemorySegment meta = metaWithBitset(8192, 0x18);

            // When
            List<ZonedStatsSchema.Stat> stats = ZonedStatsSchema.presentStats(meta);

            // Then — Max (ord 3) comes before Min (ord 4) since iteration is ordinal-ascending
            assertThat(stats).containsExactly(ZonedStatsSchema.Stat.MAX, ZonedStatsSchema.Stat.MIN);
        }

        @Test
        void decodesMultiByteBitset() {
            // Given — set bits for MAX (3), MIN (4), NULL_COUNT (6), NAN_COUNT (8)
            // byte 0: 0b0101_1000 = 0x58 (bits 3,4,6)
            // byte 1: 0b0000_0001 = 0x01 (bit 8 → bit 0 of second byte)
            MemorySegment meta = MemorySegment.ofArray(new byte[4 + 2]);
            meta.set(io.github.dfa1.vortex.core.io.VortexFormat.LE_INT, 0, 8192);
            meta.set(java.lang.foreign.ValueLayout.JAVA_BYTE, 4, (byte) 0x58);
            meta.set(java.lang.foreign.ValueLayout.JAVA_BYTE, 5, (byte) 0x01);

            // When
            List<ZonedStatsSchema.Stat> stats = ZonedStatsSchema.presentStats(meta);

            // Then
            assertThat(stats).containsExactly(
                    ZonedStatsSchema.Stat.MAX,
                    ZonedStatsSchema.Stat.MIN,
                    ZonedStatsSchema.Stat.NULL_COUNT,
                    ZonedStatsSchema.Stat.NAN_COUNT);
        }

        @Test
        void returnsEmptyWhenMetadataHasNoBitset() {
            // Given — exactly 4 bytes (zone length only, no bitset trailer)
            MemorySegment meta = MemorySegment.ofArray(new byte[4]);

            // When
            List<ZonedStatsSchema.Stat> stats = ZonedStatsSchema.presentStats(meta);

            // Then
            assertThat(stats).isEmpty();
        }

        @Test
        void ignoresFutureStatBits() {
            // Given — bit 31 set (beyond any known stat) plus MAX/MIN
            // byte 0: 0x18 (MAX|MIN), bytes 1-2: 0, byte 3: 0x80 (bit 31)
            MemorySegment meta = MemorySegment.ofArray(new byte[4 + 4]);
            meta.set(io.github.dfa1.vortex.core.io.VortexFormat.LE_INT, 0, 8192);
            meta.set(java.lang.foreign.ValueLayout.JAVA_BYTE, 4, (byte) 0x18);
            meta.set(java.lang.foreign.ValueLayout.JAVA_BYTE, 7, (byte) 0x80);

            // When
            List<ZonedStatsSchema.Stat> stats = ZonedStatsSchema.presentStats(meta);

            // Then — forward-compat: drop unknown stats rather than throw
            assertThat(stats).containsExactly(ZonedStatsSchema.Stat.MAX, ZonedStatsSchema.Stat.MIN);
        }
    }

    @Nested
    class StatsTableDtype {
        @Test
        void buildsStructWithMinMaxAndTruncationFlags() {
            // Given — i64 column with Min, Max, Sum
            DType columnDtype = DType.I64;
            List<ZonedStatsSchema.Stat> present = List.of(
                    ZonedStatsSchema.Stat.MAX,
                    ZonedStatsSchema.Stat.MIN,
                    ZonedStatsSchema.Stat.SUM);

            // When
            DType.Struct schema = ZonedStatsSchema.statsTableDtype(columnDtype, present);

            // Then — matches Rust's stats_table_dtype ordering exactly
            assertThat(schema.fieldNames().stream().map(ColumnName::value).toList()).containsExactly(
                    "max", "max_is_truncated",
                    "min", "min_is_truncated",
                    "sum");
            // i64 sum widens to nullable i64; min/max stay i64 but nullable; truncation flags are non-null Bool
            assertThat(schema.fieldTypes()).containsExactly(
                    new DType.Primitive(PType.I64, true),
                    DType.BOOL,
                    new DType.Primitive(PType.I64, true),
                    DType.BOOL,
                    new DType.Primitive(PType.I64, true));
            assertThat(schema.nullable()).isFalse();
        }

        @Test
        void dropsMaxAndMinForDTypeNull() {
            // Given — DType.Null has no value domain; min/max are dropped
            List<ZonedStatsSchema.Stat> present = List.of(
                    ZonedStatsSchema.Stat.MAX,
                    ZonedStatsSchema.Stat.MIN,
                    ZonedStatsSchema.Stat.NULL_COUNT);

            // When
            DType.Struct schema = ZonedStatsSchema.statsTableDtype(new DType.Null(true), present);

            // Then — only null_count survives; truncation flags drop with their parent stat
            assertThat(schema.fieldNames().stream().map(ColumnName::value).toList()).containsExactly("null_count");
            assertThat(schema.fieldTypes()).containsExactly(new DType.Primitive(PType.U64, true));
        }

        @Test
        void dropsSumForUnsupportedColumnDtype() {
            // Given — Utf8 column has no sum (Rust returns None)
            DType columnDtype = DType.UTF8;
            List<ZonedStatsSchema.Stat> present = List.of(
                    ZonedStatsSchema.Stat.MIN,
                    ZonedStatsSchema.Stat.SUM,
                    ZonedStatsSchema.Stat.NULL_COUNT);

            // When
            DType.Struct schema = ZonedStatsSchema.statsTableDtype(columnDtype, present);

            // Then
            assertThat(schema.fieldNames().stream().map(ColumnName::value).toList()).containsExactly("min", "min_is_truncated", "null_count");
        }

        @Test
        void dropsNanCountForNonFloatColumn() {
            // Given — nan_count only makes sense for floats
            DType columnDtype = DType.I32;
            List<ZonedStatsSchema.Stat> present = List.of(
                    ZonedStatsSchema.Stat.MAX,
                    ZonedStatsSchema.Stat.NAN_COUNT,
                    ZonedStatsSchema.Stat.NULL_COUNT);

            // When
            DType.Struct schema = ZonedStatsSchema.statsTableDtype(columnDtype, present);

            // Then
            assertThat(schema.fieldNames().stream().map(ColumnName::value).toList()).containsExactly("max", "max_is_truncated", "null_count");
        }

        @Test
        void keepsNanCountForFloatColumn() {
            // Given — float column → nan_count is u64
            DType columnDtype = DType.F64;
            List<ZonedStatsSchema.Stat> present = List.of(
                    ZonedStatsSchema.Stat.MAX, ZonedStatsSchema.Stat.NAN_COUNT);

            // When
            DType.Struct schema = ZonedStatsSchema.statsTableDtype(columnDtype, present);

            // Then
            assertThat(schema.fieldNames().stream().map(ColumnName::value).toList()).containsExactly("max", "max_is_truncated", "nan_count");
            assertThat(schema.fieldTypes()).element(2).isEqualTo(new DType.Primitive(PType.U64, true));
        }

        @Test
        void resolvesExtensionViaStorageDType() {
            // Given — ext over i32 storage; sum should widen using storage dtype (i32 → i64)
            DType columnDtype = new DType.Extension("ip.address",
                    DType.I32, null, false);
            List<ZonedStatsSchema.Stat> present = List.of(
                    ZonedStatsSchema.Stat.MIN, ZonedStatsSchema.Stat.SUM);

            // When
            DType.Struct schema = ZonedStatsSchema.statsTableDtype(columnDtype, present);

            // Then — min keeps extension dtype, sum widens to i64 via storage
            assertThat(schema.fieldNames().stream().map(ColumnName::value).toList()).containsExactly("min", "min_is_truncated", "sum");
            assertThat(schema.fieldTypes().get(0)).isEqualTo(columnDtype.withNullable(true));
            assertThat(schema.fieldTypes().get(2)).isEqualTo(new DType.Primitive(PType.I64, true));
        }

        @Test
        void allStatsTogetherForI32() {
            // Given — sanity that every stat slots correctly into i32 column
            DType columnDtype = DType.I32;
            List<ZonedStatsSchema.Stat> present = List.copyOf(EnumSet.allOf(ZonedStatsSchema.Stat.class));

            // When
            DType.Struct schema = ZonedStatsSchema.statsTableDtype(columnDtype, present);

            // Then — nan_count is dropped (i32 not float); everything else present.
            assertThat(schema.fieldNames().stream().map(ColumnName::value).toList()).containsExactly(
                    "is_constant", "is_sorted", "is_strict_sorted",
                    "max", "max_is_truncated",
                    "min", "min_is_truncated",
                    "sum",
                    "null_count",
                    "uncompressed_size_in_bytes");
        }
    }

    private static MemorySegment metaWithBitset(int zoneLen, int firstByte) {
        MemorySegment meta = MemorySegment.ofArray(new byte[4 + 1]);
        meta.set(io.github.dfa1.vortex.core.io.VortexFormat.LE_INT, 0, zoneLen);
        meta.set(java.lang.foreign.ValueLayout.JAVA_BYTE, 4, (byte) firstByte);
        return meta;
    }
}
