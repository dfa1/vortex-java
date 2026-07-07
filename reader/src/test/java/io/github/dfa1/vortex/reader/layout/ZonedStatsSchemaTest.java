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

    @Nested
    class AggregateStatsTableDtype {
        @Test
        void buildsNumericSchemaWithoutTruncationFlags() {
            // Given — a Rust >= 0.76 `vortex.zoned` column: default numeric aggregates in spec
            // order. nan_count has no state dtype for i64, so Rust drops it from the table.
            MemorySegment meta = aggregateMeta(8192,
                    "vortex.max", "vortex.min", "vortex.sum", "vortex.nan_count", "vortex.null_count");

            // When
            DType.Struct result = ZonedStatsSchema.aggregateStatsTableDtype(DType.I64, meta);

            // Then — no `_is_truncated` fields (the new format has none) and nan_count is dropped
            assertThat(result.fieldNames().stream().map(ColumnName::value).toList())
                    .containsExactly("max", "min", "sum", "null_count");
            assertThat(result.fieldTypes()).containsExactly(
                    new DType.Primitive(PType.I64, true),
                    new DType.Primitive(PType.I64, true),
                    new DType.Primitive(PType.I64, true),
                    new DType.Primitive(PType.U64, true));
            assertThat(result.nullable()).isFalse();
        }

        @Test
        void keepsNanCountForFloatColumn() {
            // Given — nan_count resolves to u64 for a float column, so it stays in the table
            MemorySegment meta = aggregateMeta(1024, "vortex.max", "vortex.nan_count");

            // When
            DType.Struct result = ZonedStatsSchema.aggregateStatsTableDtype(DType.F64, meta);

            // Then
            assertThat(result.fieldNames().stream().map(ColumnName::value).toList())
                    .containsExactly("max", "nan_count");
            assertThat(result.fieldTypes()).element(1).isEqualTo(new DType.Primitive(PType.U64, true));
        }

        @Test
        void bailsOnUnknownAggregate() {
            // Given — bounded_max stores a nested struct we cannot faithfully describe. Returning a
            // partial schema would misalign the positional decode, so reconstruction must bail.
            MemorySegment meta = aggregateMeta(4096, "vortex.bounded_max", "vortex.null_count");

            // When
            DType.Struct result = ZonedStatsSchema.aggregateStatsTableDtype(DType.UTF8, meta);

            // Then
            assertThat(result).isNull();
        }

        @Test
        void bailsForDecimalSumColumn() {
            // Given — Rust's default_zoned_aggregate_fns emits a `sum` column for a Decimal column
            // (Sum.return_dtype widens to Decimal(precision + 10)), but this reader cannot map a
            // decimal sum state. Reconstructing {max, min, null_count} while the encoded table is
            // {max, min, sum, null_count} would read null_count out of the sum buffer, so the whole
            // column must fall back to per-chunk (unpruned) stats rather than emit a mis-sized struct.
            MemorySegment meta = aggregateMeta(8192,
                    "vortex.max", "vortex.min", "vortex.sum", "vortex.null_count");

            // When
            DType.Struct result = ZonedStatsSchema.aggregateStatsTableDtype(DType.decimal(20, 4), meta);

            // Then
            assertThat(result).isNull();
        }

        @Test
        void bailsOnUnsupportedVersion() {
            // Given — a version byte other than the one Rust writes for `vortex.zoned` metadata
            MemorySegment meta = aggregateMeta(4096, "vortex.max");
            meta.set(java.lang.foreign.ValueLayout.JAVA_BYTE, 0, (byte) 2);

            // When
            DType.Struct result = ZonedStatsSchema.aggregateStatsTableDtype(DType.I64, meta);

            // Then
            assertThat(result).isNull();
        }

        @Test
        void bailsOnNullMetadata() {
            // Given / When — a zoned layout with no metadata cannot be reconstructed
            DType.Struct result = ZonedStatsSchema.aggregateStatsTableDtype(DType.I64, null);

            // Then
            assertThat(result).isNull();
        }

        @Test
        void returnsEmptyStructWhenNoAggregatesPresent() {
            // Given — a well-formed envelope carrying only zone_len, no aggregate specs
            MemorySegment meta = aggregateMeta(8192);

            // When
            DType.Struct result = ZonedStatsSchema.aggregateStatsTableDtype(DType.I64, meta);

            // Then — an empty (non-null) struct; the caller falls back to per-chunk stats
            assertThat(result).isNotNull();
            assertThat(result.fieldNames()).isEmpty();
        }

        @Test
        void buildsBooleanAndSizeStatColumns() {
            // Given — the boolean/size aggregates Rust can emit (is_constant/is_sorted/
            // is_strict_sorted → nullable Bool; uncompressed_size_in_bytes → nullable U64). These
            // exercise the statForAggregate arms that the max/min/sum/count tests never reach.
            MemorySegment meta = aggregateMeta(2048,
                    "vortex.is_constant", "vortex.is_sorted", "vortex.is_strict_sorted",
                    "vortex.uncompressed_size_in_bytes");

            // When
            DType.Struct result = ZonedStatsSchema.aggregateStatsTableDtype(DType.I64, meta);

            // Then
            assertThat(result.fieldNames().stream().map(ColumnName::value).toList())
                    .containsExactly("is_constant", "is_sorted", "is_strict_sorted",
                            "uncompressed_size_in_bytes");
            assertThat(result.fieldTypes()).containsExactly(
                    DType.BOOL.withNullable(true),
                    DType.BOOL.withNullable(true),
                    DType.BOOL.withNullable(true),
                    DType.U64.withNullable(true));
        }

        @Test
        void skipsFixedWidthFieldsBeforeSpec() {
            // Given — an envelope carrying unknown 64-bit and 32-bit fixed-width fields (wire types
            // I64 and I32) ahead of a real aggregate spec. A forward-compatible reader must skip both
            // fixed-width fields (the ProtoCursor WIRE_I64/WIRE_I32 skip arms) and still read the spec.
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            out.write(1); // envelope version
            writeTag(out, 3, 1); // field 3, wire I64 → advance(8)
            out.writeBytes(new byte[8]);
            writeTag(out, 4, 5); // field 4, wire I32 → advance(4)
            out.writeBytes(new byte[4]);
            byte[] idBytes = "vortex.max".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            java.io.ByteArrayOutputStream spec = new java.io.ByteArrayOutputStream();
            writeTag(spec, 1, 2); // id, wire LEN
            writeVarint(spec, idBytes.length);
            spec.writeBytes(idBytes);
            writeTag(out, 2, 2); // aggregate_specs, wire LEN
            writeVarint(out, spec.size());
            out.writeBytes(spec.toByteArray());

            // When
            DType.Struct result = ZonedStatsSchema.aggregateStatsTableDtype(
                    DType.I64, MemorySegment.ofArray(out.toByteArray()));

            // Then — the fixed-width fields are skipped and only the max spec survives
            assertThat(result.fieldNames().stream().map(ColumnName::value).toList())
                    .containsExactly("max");
        }
    }

    @Nested
    class MalformedAggregateMetadata {
        // These blobs are untrusted file metadata. Each malformed shape must degrade to the
        // fallback (null) return — never a raw JDK exception (NegativeArraySizeException /
        // IndexOutOfBoundsException), which the pre-fix overflow-unsafe bounds checks allowed.

        @Test
        void bailsOnTruncatedVarint() {
            // Given — the only field tag is an unterminated varint (continuation bit set, no next
            // byte); the cursor must fail rather than read past the buffer.
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            out.write(1); // envelope version
            out.write(0x80); // dangling varint continuation byte

            // When
            DType.Struct result = ZonedStatsSchema.aggregateStatsTableDtype(
                    DType.I64, MemorySegment.ofArray(out.toByteArray()));

            // Then
            assertThat(result).isNull();
        }

        @Test
        void bailsOnOversizedStringLength() {
            // Given — a framed AggregateSpecProto whose id-string length varint is Long.MAX_VALUE.
            // The pre-fix `pos + len > limit` guard overflowed to negative and passed, then
            // `new byte[(int) len]` threw NegativeArraySizeException.
            java.io.ByteArrayOutputStream spec = new java.io.ByteArrayOutputStream();
            writeTag(spec, 1, 2); // id, wire LEN
            writeVarintLong(spec, Long.MAX_VALUE); // absurd string length
            byte[] specBytes = spec.toByteArray();
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            out.write(1); // envelope version
            writeTag(out, 2, 2); // aggregate_specs, wire LEN
            writeVarint(out, specBytes.length);
            out.writeBytes(specBytes);

            // When / Then — must not throw NegativeArraySizeException
            DType.Struct result = ZonedStatsSchema.aggregateStatsTableDtype(
                    DType.I64, MemorySegment.ofArray(out.toByteArray()));
            assertThat(result).isNull();
        }

        @Test
        void bailsOnOversizedSkippedFieldLength() {
            // Given — a non-target length-delimited field whose length is Long.MAX_VALUE. The
            // pre-fix advance guard `pos + count > end` overflowed and advanced pos negative,
            // throwing IndexOutOfBoundsException on the next read.
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            out.write(1); // envelope version
            writeTag(out, 1, 2); // field 1 with wire LEN → skipped, not an aggregate spec
            writeVarintLong(out, Long.MAX_VALUE);

            // When / Then — must not throw IndexOutOfBoundsException
            DType.Struct result = ZonedStatsSchema.aggregateStatsTableDtype(
                    DType.I64, MemorySegment.ofArray(out.toByteArray()));
            assertThat(result).isNull();
        }

        @Test
        void bailsOnUnknownWireType() {
            // Given — a field using wire type 3 (group start), which the cursor cannot skip.
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            out.write(1); // envelope version
            writeTag(out, 3, 3); // field 3, wire type 3 (unsupported)

            // When
            DType.Struct result = ZonedStatsSchema.aggregateStatsTableDtype(
                    DType.I64, MemorySegment.ofArray(out.toByteArray()));

            // Then
            assertThat(result).isNull();
        }

        @Test
        void bailsOnOverlongVarintOverflow() {
            // Given — a tag varint of ten continuation bytes: it never terminates and pushes the
            // shift past 63 bits. readVarint must return -1 (the > 63-bit overflow arm) rather than
            // silently wrapping, so the caller bails.
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            out.write(1); // envelope version
            for (int i = 0; i < 10; i++) {
                out.write(0x80); // continuation bit set, no terminating byte
            }

            // When
            DType.Struct result = ZonedStatsSchema.aggregateStatsTableDtype(
                    DType.I64, MemorySegment.ofArray(out.toByteArray()));

            // Then
            assertThat(result).isNull();
        }

        @Test
        void bailsOnOverflowTagInsideSpec() {
            // Given — a framed AggregateSpecProto whose first inner tag is a ten-byte overflow
            // varint. Inside readAggregateSpecId the tag reads as -1, so it must bail (the inner
            // `tag < 0` arm) rather than treat the negative value as a field number.
            byte[] specBytes = new byte[10];
            java.util.Arrays.fill(specBytes, (byte) 0x80);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            out.write(1); // envelope version
            writeTag(out, 2, 2); // aggregate_specs, wire LEN
            writeVarint(out, specBytes.length);
            out.writeBytes(specBytes);

            // When
            DType.Struct result = ZonedStatsSchema.aggregateStatsTableDtype(
                    DType.I64, MemorySegment.ofArray(out.toByteArray()));

            // Then
            assertThat(result).isNull();
        }

        @Test
        void bailsOnUnknownWireTypeInsideSpec() {
            // Given — an AggregateSpecProto whose inner field uses wire type 3 (group start), which
            // the cursor cannot skip. readAggregateSpecId must bail on the failed skipField, not
            // loop forever or misread.
            java.io.ByteArrayOutputStream spec = new java.io.ByteArrayOutputStream();
            writeTag(spec, 2, 3); // inner field 2, wire type 3 (unsupported)
            byte[] specBytes = spec.toByteArray();
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            out.write(1); // envelope version
            writeTag(out, 2, 2); // aggregate_specs, wire LEN
            writeVarint(out, specBytes.length);
            out.writeBytes(specBytes);

            // When
            DType.Struct result = ZonedStatsSchema.aggregateStatsTableDtype(
                    DType.I64, MemorySegment.ofArray(out.toByteArray()));

            // Then
            assertThat(result).isNull();
        }

        @Test
        void bailsOnMessageLengthPastEnd() {
            // Given — an aggregate_specs field whose declared length runs past the buffer end.
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            out.write(1); // envelope version
            writeTag(out, 2, 2); // aggregate_specs, wire LEN
            writeVarint(out, 64); // claims 64 bytes...
            out.write(0x01); // ...but only one follows

            // When
            DType.Struct result = ZonedStatsSchema.aggregateStatsTableDtype(
                    DType.I64, MemorySegment.ofArray(out.toByteArray()));

            // Then
            assertThat(result).isNull();
        }
    }

    private static MemorySegment metaWithBitset(int zoneLen, int firstByte) {
        MemorySegment meta = MemorySegment.ofArray(new byte[4 + 1]);
        meta.set(io.github.dfa1.vortex.core.io.VortexFormat.LE_INT, 0, zoneLen);
        meta.set(java.lang.foreign.ValueLayout.JAVA_BYTE, 4, (byte) firstByte);
        return meta;
    }

    /// Builds `vortex.zoned` metadata: a version byte (1) followed by a protobuf
    /// `ZonedMetadataProto { uint32 zone_len = 1; repeated AggregateSpecProto specs = 2; }`,
    /// where each spec is `AggregateSpecProto { string id = 1; }` (options omitted).
    private static MemorySegment aggregateMeta(int zoneLen, String... aggregateIds) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.write(1); // envelope version
        writeTag(out, 1, 0); // zone_len, wire VARINT
        writeVarint(out, zoneLen);
        for (String id : aggregateIds) {
            byte[] idBytes = id.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            java.io.ByteArrayOutputStream spec = new java.io.ByteArrayOutputStream();
            writeTag(spec, 1, 2); // id, wire LEN
            writeVarint(spec, idBytes.length);
            spec.writeBytes(idBytes);
            writeTag(out, 2, 2); // aggregate_specs, wire LEN
            writeVarint(out, spec.size());
            out.writeBytes(spec.toByteArray());
        }
        return MemorySegment.ofArray(out.toByteArray());
    }

    private static void writeTag(java.io.ByteArrayOutputStream out, int fieldNumber, int wireType) {
        writeVarint(out, (fieldNumber << 3) | wireType);
    }

    private static void writeVarint(java.io.ByteArrayOutputStream out, int value) {
        int v = value;
        while ((v & ~0x7f) != 0) {
            out.write((v & 0x7f) | 0x80);
            v >>>= 7;
        }
        out.write(v);
    }

    /// Writes an unsigned base-128 varint of a `long` — used to inject absurd (overflow-inducing)
    /// lengths like `Long.MAX_VALUE` that a plain `int` varint cannot express.
    private static void writeVarintLong(java.io.ByteArrayOutputStream out, long value) {
        long v = value;
        while ((v & ~0x7fL) != 0) {
            out.write((int) ((v & 0x7f) | 0x80));
            v >>>= 7;
        }
        out.write((int) v);
    }
}
