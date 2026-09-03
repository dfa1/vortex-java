package io.github.dfa1.vortex.parquet;

import dev.hardwood.InputFile;
import dev.hardwood.metadata.LogicalType;
import dev.hardwood.metadata.PhysicalType;
import dev.hardwood.metadata.RepetitionType;
import dev.hardwood.reader.ParquetFileReader;
import dev.hardwood.reader.RowReader;
import dev.hardwood.schema.FileSchema;
import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.model.TimeUnit;
import io.github.dfa1.vortex.core.model.TimestampDtype;
import io.github.dfa1.vortex.reader.Chunk;
import io.github.dfa1.vortex.reader.ScanIterator;
import io.github.dfa1.vortex.reader.ScanOptions;
import io.github.dfa1.vortex.reader.VortexReader;
import io.github.dfa1.vortex.reader.array.BoolArray;
import io.github.dfa1.vortex.reader.array.ByteArray;
import io.github.dfa1.vortex.reader.array.DoubleArray;
import io.github.dfa1.vortex.reader.array.IntArray;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.VarBinArray;
import io.github.dfa1.vortex.writer.VortexWriter;
import io.github.dfa1.vortex.writer.WriteOptions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ParquetExporterTest {

    private static FileSchema schemaOf(String name, DType type) {
        FileSchema.Builder builder = FileSchema.builder("schema");
        ParquetExporter.addColumn(builder, name, type);
        return builder.build();
    }

    private static Path writeVortex(Path tmp, String fileName, List<ColumnName> names, List<DType> types,
            Map<ColumnName, Object> chunk) throws Exception {
        DType.Struct schema = new DType.Struct(names, types, false);
        Path vortex = tmp.resolve(fileName);
        try (FileChannel ch = FileChannel.open(vortex, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             VortexWriter writer = VortexWriter.create(ch, schema, WriteOptions.defaults())) {
            writer.writeChunk(chunk);
        }
        return vortex;
    }

    @Nested
    class TypeMapping {

        @Test
        void bool_mapsToBoolean_carryingNullability() {
            // Given / When / Then — REQUIRED is non-null, OPTIONAL is nullable
            assertThat(schemaOf("b", new DType.Bool(false)).getColumn(0).repetitionType())
                    .isEqualTo(RepetitionType.REQUIRED);
            assertThat(schemaOf("b", new DType.Bool(true)).getColumn(0).repetitionType())
                    .isEqualTo(RepetitionType.OPTIONAL);
            assertThat(schemaOf("b", new DType.Bool(false)).getColumn(0).type()).isEqualTo(PhysicalType.BOOLEAN);
        }

        @Test
        void i32_mapsToInt32_withoutAnnotation() {
            // Given
            DType type = new DType.Primitive(PType.I32, false);

            // When
            var column = schemaOf("i", type).getColumn(0);

            // Then — bare INT32, matching how ParquetImporter also reads an un-annotated INT32 as I32
            assertThat(column.type()).isEqualTo(PhysicalType.INT32);
            assertThat(column.logicalType()).isNull();
        }

        @Test
        void i64_mapsToInt64_withoutAnnotation() {
            // Given
            DType type = new DType.Primitive(PType.I64, false);

            // When
            var column = schemaOf("l", type).getColumn(0);

            // Then
            assertThat(column.type()).isEqualTo(PhysicalType.INT64);
            assertThat(column.logicalType()).isNull();
        }

        @ParameterizedTest
        @CsvSource({
                "I8,  8,  true",
                "U8,  8,  false",
                "I16, 16, true",
                "U16, 16, false",
                "U32, 32, false",
                "U64, 64, false",
        })
        void narrowOrUnsignedPrimitive_carriesIntAnnotation(PType ptype, int bitWidth, boolean signed) {
            // Given — every PType this codebase carries an IntType annotation for on export,
            // the inverse of ParquetImporter's mapInt32/mapInt64
            PhysicalType expectedPhysical = bitWidth == 64 ? PhysicalType.INT64 : PhysicalType.INT32;

            // When
            var column = schemaOf("i", new DType.Primitive(ptype, false)).getColumn(0);

            // Then
            assertThat(column.type()).isEqualTo(expectedPhysical);
            assertThat(column.logicalType()).isEqualTo(new LogicalType.IntType(bitWidth, signed));
        }

        @Test
        void f32_mapsToFloat_f64_mapsToDouble() {
            // Given / When / Then
            assertThat(schemaOf("f", new DType.Primitive(PType.F32, false)).getColumn(0).type())
                    .isEqualTo(PhysicalType.FLOAT);
            assertThat(schemaOf("d", new DType.Primitive(PType.F64, false)).getColumn(0).type())
                    .isEqualTo(PhysicalType.DOUBLE);
        }

        @Test
        void f16_throws() {
            // Given — mirrors ParquetImporter, which never produces F16 either
            DType f16 = new DType.Primitive(PType.F16, false);

            // When / Then
            assertThatThrownBy(() -> schemaOf("f", f16)).isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        void utf8_mapsToByteArray_withStringAnnotation() {
            // When
            var column = schemaOf("s", new DType.Utf8(true)).getColumn(0);

            // Then
            assertThat(column.type()).isEqualTo(PhysicalType.BYTE_ARRAY);
            assertThat(column.logicalType()).isEqualTo(new LogicalType.StringType());
            assertThat(column.repetitionType()).isEqualTo(RepetitionType.OPTIONAL);
        }

        @Test
        void binary_mapsToByteArray_withoutAnnotation() {
            // When
            var column = schemaOf("b", new DType.Binary(false)).getColumn(0);

            // Then
            assertThat(column.type()).isEqualTo(PhysicalType.BYTE_ARRAY);
            assertThat(column.logicalType()).isNull();
        }

        @ParameterizedTest
        @CsvSource({"Milliseconds, MILLIS", "Microseconds, MICROS", "Nanoseconds, NANOS"})
        void timestamp_mapsToInt64Timestamp_carryingUnit(TimeUnit unit, LogicalType.TimeUnit expectedUnit) {
            // Given — no timezone recorded, so isAdjustedToUTC is false
            DType.Extension ts = TimestampDtype.of(unit, null, true);

            // When
            var column = schemaOf("ts", ts).getColumn(0);

            // Then
            assertThat(column.type()).isEqualTo(PhysicalType.INT64);
            assertThat(column.logicalType()).isEqualTo(new LogicalType.TimestampType(false, expectedUnit));
            assertThat(column.repetitionType()).isEqualTo(RepetitionType.OPTIONAL);
        }

        @Test
        void timestamp_withTimezone_isAdjustedToUtc() {
            // Given
            DType.Extension ts = TimestampDtype.of(TimeUnit.Milliseconds, java.time.ZoneOffset.UTC, false);

            // When
            var column = schemaOf("ts", ts).getColumn(0);

            // Then
            assertThat(column.logicalType()).isEqualTo(new LogicalType.TimestampType(true, LogicalType.TimeUnit.MILLIS));
        }

        @ParameterizedTest
        @CsvSource({"Seconds", "Days"})
        void timestamp_secondsOrDaysResolution_throws(TimeUnit unit) {
            // Given — Parquet TIMESTAMP has no SECONDS/DAYS resolution
            DType.Extension ts = TimestampDtype.of(unit, null, false);

            // When / Then
            assertThatThrownBy(() -> schemaOf("ts", ts)).isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        void nonTimestampExtension_throws() {
            // Given — a made-up extension id this exporter has no mapping for
            DType.Extension ext = new DType.Extension("vortex.uuid", new DType.Primitive(PType.I64, false), null, false);

            // When / Then
            assertThatThrownBy(() -> schemaOf("u", ext)).isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        void struct_throws() {
            // Given — nested schemas are out of scope for this exporter
            DType.Struct nested = new DType.Struct(List.of(ColumnName.of("x")), List.of(DType.I32), false);

            // When / Then
            assertThatThrownBy(() -> schemaOf("s", nested)).isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        void list_throws() {
            // Given
            DType.List list = new DType.List(DType.I32, false);

            // When / Then
            assertThatThrownBy(() -> schemaOf("l", list)).isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    class Export {

        @Test
        void exportsFlatFile_schemaAndValuesRoundTrip(@TempDir Path tmp) throws Exception {
            // Given — nullable I64 id, nullable Utf8 name, non-null Bool active
            Path vortex = writeVortex(tmp, "in.vortex",
                    List.of(ColumnName.of("id"), ColumnName.of("name"), ColumnName.of("active")),
                    List.of(new DType.Primitive(PType.I64, true), new DType.Utf8(true), new DType.Bool(false)),
                    Map.of(
                            ColumnName.of("id"), new Long[]{1L, null, 3L},
                            ColumnName.of("name"), new String[]{"Ada", "Grace", null},
                            ColumnName.of("active"), new boolean[]{true, false, true}));
            Path parquet = tmp.resolve("out.parquet");

            // When
            ParquetExporter.exportParquet(vortex, parquet);

            // Then
            try (ParquetFileReader reader = ParquetFileReader.open(InputFile.of(parquet))) {
                assertThat(reader.getFileMetaData().numRows()).isEqualTo(3L);
                try (RowReader rows = reader.buildRowReader().build()) {
                    assertThat(rows.hasNext()).isTrue();
                    rows.next();
                    assertThat(rows.getLong("id")).isEqualTo(1L);
                    assertThat(rows.getString("name")).isEqualTo("Ada");
                    assertThat(rows.getBoolean("active")).isTrue();

                    assertThat(rows.hasNext()).isTrue();
                    rows.next();
                    assertThat(rows.isNull("id")).isTrue();
                    assertThat(rows.getString("name")).isEqualTo("Grace");
                    assertThat(rows.getBoolean("active")).isFalse();

                    assertThat(rows.hasNext()).isTrue();
                    rows.next();
                    assertThat(rows.getLong("id")).isEqualTo(3L);
                    assertThat(rows.isNull("name")).isTrue();
                    assertThat(rows.getBoolean("active")).isTrue();

                    assertThat(rows.hasNext()).isFalse();
                }
            }
        }

        @Test
        void exportsTimestampColumn_asInt64Timestamp(@TempDir Path tmp) throws Exception {
            // Given — a vortex.timestamp column at millisecond resolution
            DType.Extension tsDtype = TimestampDtype.of(TimeUnit.Milliseconds, null, false);
            Path vortex = writeVortex(tmp, "in.vortex",
                    List.of(ColumnName.of("events")),
                    List.of(tsDtype),
                    Map.of(ColumnName.of("events"), List.of(
                            Instant.ofEpochMilli(-1_500L),
                            Instant.EPOCH,
                            Instant.ofEpochMilli(1_733_000_000_000L))));
            Path parquet = tmp.resolve("out.parquet");

            // When
            ParquetExporter.exportParquet(vortex, parquet);

            // Then — the raw epoch-millisecond values round-trip exactly
            try (ParquetFileReader reader = ParquetFileReader.open(InputFile.of(parquet));
                 RowReader rows = reader.buildRowReader().build()) {
                rows.next();
                assertThat(rows.getLong("events")).isEqualTo(-1_500L);
                rows.next();
                assertThat(rows.getLong("events")).isZero();
                rows.next();
                assertThat(rows.getLong("events")).isEqualTo(1_733_000_000_000L);
            }
        }

        @Test
        void projection_exportsOnlyRequestedColumns(@TempDir Path tmp) throws Exception {
            // Given
            Path vortex = writeVortex(tmp, "in.vortex",
                    List.of(ColumnName.of("id"), ColumnName.of("name")),
                    List.of(new DType.Primitive(PType.I64, false), new DType.Utf8(false)),
                    Map.of(ColumnName.of("id"), new long[]{1L, 2L}, ColumnName.of("name"), new String[]{"a", "b"}));
            Path parquet = tmp.resolve("out.parquet");
            ExportOptions options = ExportOptions.defaults().withColumns(List.of("id"));

            // When
            ParquetExporter.exportParquet(vortex, parquet, options);

            // Then — only the projected column survives
            try (ParquetFileReader reader = ParquetFileReader.open(InputFile.of(parquet))) {
                assertThat(reader.getFileSchema().getColumns()).extracting(c -> c.name()).containsExactly("id");
            }
        }

        @Test
        void projection_unknownColumn_throws(@TempDir Path tmp) throws Exception {
            // Given
            Path vortex = writeVortex(tmp, "in.vortex",
                    List.of(ColumnName.of("id")), List.of(new DType.Primitive(PType.I64, false)),
                    Map.of(ColumnName.of("id"), new long[]{1L}));
            Path parquet = tmp.resolve("out.parquet");
            ExportOptions options = ExportOptions.defaults().withColumns(List.of("does_not_exist"));

            // When / Then
            assertThatThrownBy(() -> ParquetExporter.exportParquet(vortex, parquet, options))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("does_not_exist");
        }

        @Test
        void structColumn_throws(@TempDir Path tmp) throws Exception {
            // Given — a top-level Struct column, out of scope for this exporter; the schema
            // alone triggers the rejection (at FileSchema-building time, before any chunk is
            // read), so the file needs no rows
            DType.Struct fieldSchema = new DType.Struct(List.of(ColumnName.of("x")), List.of(DType.I32), false);
            DType.Struct schema = new DType.Struct(List.of(ColumnName.of("s")), List.of(fieldSchema), false);
            Path vortex = tmp.resolve("in.vortex");
            try (FileChannel ch = FileChannel.open(vortex, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 VortexWriter writer = VortexWriter.create(ch, schema, WriteOptions.defaults())) {
                // no writeChunk — an empty file still declares the Struct-typed column
                assertThat(writer).isNotNull();
            }
            Path parquet = tmp.resolve("out.parquet");

            // When / Then
            assertThatThrownBy(() -> ParquetExporter.exportParquet(vortex, parquet))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    class RoundTrip {

        @Test
        void flatTypes_roundTripThroughParquetAndBack(@TempDir Path tmp) throws Exception {
            // Given — nullable/non-null primitives (including U8/U32 boundary values, whose raw
            // bit pattern must survive both the Parquet IntType(unsigned) annotation on export
            // and ParquetImporter's re-decode), Utf8, Binary and Bool
            Path original = writeVortex(tmp, "original.vortex",
                    List.of(ColumnName.of("id"), ColumnName.of("age"), ColumnName.of("score"),
                            ColumnName.of("name"), ColumnName.of("blob"), ColumnName.of("active"),
                            ColumnName.of("bigCount")),
                    List.of(new DType.Primitive(PType.I64, true),
                            new DType.Primitive(PType.U8, false),
                            new DType.Primitive(PType.F64, true),
                            new DType.Utf8(true),
                            new DType.Binary(false),
                            new DType.Bool(false),
                            new DType.Primitive(PType.U32, false)),
                    Map.of(
                            ColumnName.of("id"), new Long[]{1L, null, -3L},
                            ColumnName.of("age"), new byte[]{0, (byte) 255, 42},
                            ColumnName.of("score"), new Double[]{1.5, null, -2.25},
                            ColumnName.of("name"), new String[]{"Ada", null, "Grace"},
                            ColumnName.of("blob"), new byte[][]{{1, 2}, {}, {9, 9, 9}},
                            ColumnName.of("active"), new boolean[]{true, false, true},
                            ColumnName.of("bigCount"), new int[]{0, -1, 12345}));
            Path parquet = tmp.resolve("out.parquet");
            Path reimported = tmp.resolve("reimported.vortex");

            // When
            ParquetExporter.exportParquet(original, parquet);
            ParquetImporter.importParquet(parquet, reimported);

            // Then — every column survives the Vortex -> Parquet -> Vortex trip unchanged
            try (VortexReader reader = VortexReader.open(reimported);
                 ScanIterator iter = reader.scan(ScanOptions.all())) {
                assertThat(iter.hasNext()).isTrue();
                try (Chunk chunk = iter.next()) {
                    MaskedArray id = chunk.column("id");
                    LongArray idValues = (LongArray) id.inner();
                    assertThat(id.isValid(0)).isTrue();
                    assertThat(idValues.getLong(0)).isEqualTo(1L);
                    assertThat(id.isValid(1)).isFalse();
                    assertThat(id.isValid(2)).isTrue();
                    assertThat(idValues.getLong(2)).isEqualTo(-3L);

                    ByteArray age = chunk.column("age");
                    assertThat(age.getInt(0)).isZero();
                    assertThat(age.getInt(1)).isEqualTo(255);
                    assertThat(age.getInt(2)).isEqualTo(42);

                    MaskedArray score = chunk.column("score");
                    DoubleArray scoreValues = (DoubleArray) score.inner();
                    assertThat(scoreValues.getDouble(0)).isEqualTo(1.5);
                    assertThat(score.isValid(1)).isFalse();
                    assertThat(scoreValues.getDouble(2)).isEqualTo(-2.25);

                    MaskedArray name = chunk.column("name");
                    VarBinArray nameValues = (VarBinArray) name.inner();
                    assertThat(nameValues.getString(0)).isEqualTo("Ada");
                    assertThat(name.isValid(1)).isFalse();
                    assertThat(nameValues.getString(2)).isEqualTo("Grace");

                    VarBinArray blob = chunk.column("blob");
                    assertThat(blob.getBytes(0)).isEqualTo(new byte[]{1, 2});
                    assertThat(blob.getBytes(1)).isEqualTo(new byte[]{});
                    assertThat(blob.getBytes(2)).isEqualTo(new byte[]{9, 9, 9});

                    BoolArray active = chunk.column("active");
                    assertThat(active.getBoolean(0)).isTrue();
                    assertThat(active.getBoolean(1)).isFalse();
                    assertThat(active.getBoolean(2)).isTrue();

                    // bigCount is U32: row 1's raw bit pattern -1 represents 4294967295 unsigned
                    IntArray bigCount = chunk.column("bigCount");
                    assertThat(bigCount.getInt(0)).isZero();
                    assertThat(bigCount.getInt(1)).isEqualTo(-1);
                    assertThat(bigCount.getInt(2)).isEqualTo(12345);
                }
            }
        }

        @Test
        void timestampColumn_roundTripThroughParquetAndBack(@TempDir Path tmp) throws Exception {
            // Given — microsecond-resolution vortex.timestamp, pre-epoch/epoch/future values
            DType.Extension tsDtype = TimestampDtype.of(TimeUnit.Microseconds, null, false);
            List<Instant> instants = List.of(
                    Instant.ofEpochMilli(-1_500L),
                    Instant.EPOCH,
                    Instant.ofEpochMilli(1_733_000_000_000L));
            Path original = writeVortex(tmp, "original.vortex",
                    List.of(ColumnName.of("events")), List.of(tsDtype),
                    Map.of(ColumnName.of("events"), instants));
            Path parquet = tmp.resolve("out.parquet");
            Path reimported = tmp.resolve("reimported.vortex");

            // When
            ParquetExporter.exportParquet(original, parquet);
            ParquetImporter.importParquet(parquet, reimported);

            // Then
            try (VortexReader reader = VortexReader.open(reimported);
                 ScanIterator iter = reader.scan(ScanOptions.all())) {
                assertThat(iter.hasNext()).isTrue();
                try (Chunk chunk = iter.next()) {
                    assertThat(chunk.as("events", Instant.class)).containsExactlyElementsOf(instants);
                }
            }
        }
    }
}
