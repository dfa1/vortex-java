package io.github.dfa1.vortex.parquet;

import dev.hardwood.InputFile;
import dev.hardwood.metadata.LogicalType;
import dev.hardwood.metadata.PhysicalType;
import dev.hardwood.metadata.RepetitionType;
import dev.hardwood.reader.ParquetFileReader;
import dev.hardwood.reader.RowReader;
import dev.hardwood.schema.ColumnSchema;
import dev.hardwood.schema.FileSchema;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.io.VortexFormat;
import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.ExtensionId;
import io.github.dfa1.vortex.core.model.DTypes;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.model.TimeUnit;
import io.github.dfa1.vortex.core.model.TimestampDtype;
import io.github.dfa1.vortex.reader.Chunk;
import io.github.dfa1.vortex.reader.ScanIterator;
import io.github.dfa1.vortex.reader.ScanOptions;
import io.github.dfa1.vortex.reader.VortexReader;
import io.github.dfa1.vortex.reader.array.BoolArray;
import io.github.dfa1.vortex.reader.array.ByteArray;
import io.github.dfa1.vortex.reader.array.DecimalArray;
import io.github.dfa1.vortex.reader.array.DoubleArray;
import io.github.dfa1.vortex.reader.array.Float16Array;
import io.github.dfa1.vortex.reader.array.IntArray;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.VarBinArray;
import io.github.dfa1.vortex.writer.VortexWriter;
import io.github.dfa1.vortex.writer.WriteOptions;
import io.github.dfa1.vortex.writer.encode.FixedSizeListData;
import io.github.dfa1.vortex.writer.encode.ListData;
import io.github.dfa1.vortex.writer.encode.ListViewData;
import io.github.dfa1.vortex.writer.encode.NullableData;
import io.github.dfa1.vortex.writer.encode.StructData;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.math.BigDecimal;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Arrays;
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
        void f16_mapsToTwoByteFixedLenByteArray_withFloat16Annotation() {
            // When
            var column = schemaOf("h", new DType.Primitive(PType.F16, true)).getColumn(0);

            // Then Parquet's FLOAT16 is a 2-byte FIXED_LEN_BYTE_ARRAY
            assertThat(column.type()).isEqualTo(PhysicalType.FIXED_LEN_BYTE_ARRAY);
            assertThat(column.typeLength()).isEqualTo(2);
            assertThat(column.logicalType()).isEqualTo(new LogicalType.Float16Type());
            assertThat(column.repetitionType()).isEqualTo(RepetitionType.OPTIONAL);
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
            assertThatThrownBy(() -> schemaOf("ts", ts)).isInstanceOf(VortexException.class).hasMessageContaining("column: ts");
        }

        @Test
        void nonTimestampExtension_throws() {
            // Given — a made-up extension id this exporter has no mapping for
            DType.Extension ext = new DType.Extension(ExtensionId.VORTEX_UUID, new DType.Primitive(PType.I64, false),
                    io.github.dfa1.vortex.core.model.OpaqueMetadata.EMPTY, false);

            // When / Then
            assertThatThrownBy(() -> schemaOf("u", ext)).isInstanceOf(VortexException.class).hasMessageContaining("column: u");
        }

        @Test
        void nullableStruct_mapsToOptionalGroup_ofItsFields() {
            // Given
            DType.Struct struct = new DType.Struct(List.of(ColumnName.of("x"), ColumnName.of("y")),
                    List.of(DTypes.I32, new DType.Utf8(true)), true);

            // When
            FileSchema result = schemaOf("s", struct);

            // Then — the group's nullability raises the leaves' definition level, not their repetition
            assertThat(result.getColumn("s.x").repetitionType()).isEqualTo(RepetitionType.REQUIRED);
            assertThat(result.getColumn("s.x").maxDefinitionLevel()).isEqualTo(1);
            assertThat(result.getColumn("s.y").logicalType()).isEqualTo(new LogicalType.StringType());
            assertThat(result.getColumn("s.y").maxDefinitionLevel()).isEqualTo(2);
        }

        @Test
        void listAndFixedSizeList_mapToRepeatedListElement() {
            // Given — Parquet has no fixed-size list, so both become LIST
            DType.List list = new DType.List(DTypes.F32, true);
            DType.FixedSizeList fixed = new DType.FixedSizeList(DTypes.I32, 4, false);
            FileSchema.Builder builder = FileSchema.builder("schema");
            ParquetExporter.addColumn(builder, "l", list);
            ParquetExporter.addColumn(builder, "f", fixed);

            // When
            FileSchema result = builder.build();

            // Then
            assertThat(result.getColumn("l.list.element").type()).isEqualTo(PhysicalType.FLOAT);
            assertThat(result.getColumn("l.list.element").maxRepetitionLevel()).isEqualTo(1);
            assertThat(result.getColumn("f.list.element").type()).isEqualTo(PhysicalType.INT32);
            assertThat(result.getColumn("f.list.element").maxRepetitionLevel()).isEqualTo(1);
        }

        @Test
        void map_mapsToRequiredKeyAndValue() {
            // Given
            DType.Map map = new DType.Map(DTypes.UTF8, DTypes.I64.asNullable(), false, true);

            // When
            FileSchema result = schemaOf("m", map);

            // Then
            assertThat(result.getColumn("m.key_value.key").repetitionType()).isEqualTo(RepetitionType.REQUIRED);
            assertThat(result.getColumn("m.key_value.key").logicalType()).isEqualTo(new LogicalType.StringType());
            assertThat(result.getColumn("m.key_value.value").repetitionType()).isEqualTo(RepetitionType.OPTIONAL);
        }

        @Test
        void dottedNestedColumnName_throws() {
            // Given — Hardwood addresses a struct by its dotted path, which "a.b" would misdirect
            DType.Struct struct = new DType.Struct(List.of(ColumnName.of("x")), List.of(DTypes.I32), false);

            // When / Then
            assertThatThrownBy(() -> schemaOf("a.b", struct))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("a.b");
        }

        @Test
        void dottedLeafName_isKept() {
            // Given — a leaf is addressed by index, so a dot in its name is harmless
            DType.Struct struct = new DType.Struct(List.of(ColumnName.of("x.y")), List.of(DTypes.I32), false);

            // When
            FileSchema result = schemaOf("s", struct);

            // Then
            assertThat(result.getColumn(0).fieldPath().elements()).containsExactly("s", "x.y");
        }

        @Test
        void unsupportedNestedLeaf_throws_namingItsPath() {
            // Given — a Null dtype has no Parquet leaf; the message must point at the nested column
            DType.List list = new DType.List(new DType.Null(true), false);

            // When / Then
            assertThatThrownBy(() -> schemaOf("l", list))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("column: l.list.element");
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
            ExportOptions options = ExportOptions.defaults().withColumns(List.of(ColumnName.of("id")));

            // When
            ParquetExporter.exportParquet(vortex, parquet, options);

            // Then — only the projected column survives
            try (ParquetFileReader reader = ParquetFileReader.open(InputFile.of(parquet))) {
                assertThat(reader.getFileSchema().getColumns()).extracting(ColumnSchema::name).containsExactly("id");
            }
        }

        @Test
        void projection_unknownColumn_throws(@TempDir Path tmp) throws Exception {
            // Given
            Path vortex = writeVortex(tmp, "in.vortex",
                    List.of(ColumnName.of("id")), List.of(new DType.Primitive(PType.I64, false)),
                    Map.of(ColumnName.of("id"), new long[]{1L}));
            Path parquet = tmp.resolve("out.parquet");
            ExportOptions options = ExportOptions.defaults().withColumns(List.of(ColumnName.of("does_not_exist")));

            // When / Then
            assertThatThrownBy(() -> ParquetExporter.exportParquet(vortex, parquet, options))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("does_not_exist");
        }

        @Test
        void structWithUnsupportedField_throwsVortexException_namingTheField(@TempDir Path tmp) throws Exception {
            // Given — a struct holding a Variant, which has no Parquet mapping here; the schema
            // alone triggers the rejection (at FileSchema-building time, before any chunk is
            // read), so the file needs no rows
            DType.Struct fieldSchema = new DType.Struct(List.of(ColumnName.of("x")), List.of(DTypes.VARIANT), false);
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
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("column: s.x");
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
                    MaskedArray id = chunk.column(ColumnName.of("id"));
                    LongArray idValues = (LongArray) id.inner();
                    assertThat(id.isValid(0)).isTrue();
                    assertThat(idValues.getLong(0)).isEqualTo(1L);
                    assertThat(id.isValid(1)).isFalse();
                    assertThat(id.isValid(2)).isTrue();
                    assertThat(idValues.getLong(2)).isEqualTo(-3L);

                    ByteArray age = chunk.column(ColumnName.of("age"));
                    assertThat(age.getInt(0)).isZero();
                    assertThat(age.getInt(1)).isEqualTo(255);
                    assertThat(age.getInt(2)).isEqualTo(42);

                    MaskedArray score = chunk.column(ColumnName.of("score"));
                    DoubleArray scoreValues = (DoubleArray) score.inner();
                    assertThat(scoreValues.getDouble(0)).isEqualTo(1.5);
                    assertThat(score.isValid(1)).isFalse();
                    assertThat(scoreValues.getDouble(2)).isEqualTo(-2.25);

                    MaskedArray name = chunk.column(ColumnName.of("name"));
                    VarBinArray nameValues = (VarBinArray) name.inner();
                    assertThat(nameValues.getString(0)).isEqualTo("Ada");
                    assertThat(name.isValid(1)).isFalse();
                    assertThat(nameValues.getString(2)).isEqualTo("Grace");

                    VarBinArray blob = chunk.column(ColumnName.of("blob"));
                    assertThat(blob.getBytes(0)).isEqualTo(new byte[]{1, 2});
                    assertThat(blob.getBytes(1)).isEqualTo(new byte[]{});
                    assertThat(blob.getBytes(2)).isEqualTo(new byte[]{9, 9, 9});

                    BoolArray active = chunk.column(ColumnName.of("active"));
                    assertThat(active.getBoolean(0)).isTrue();
                    assertThat(active.getBoolean(1)).isFalse();
                    assertThat(active.getBoolean(2)).isTrue();

                    // bigCount is U32: row 1's raw bit pattern -1 represents 4294967295 unsigned
                    IntArray bigCount = chunk.column(ColumnName.of("bigCount"));
                    assertThat(bigCount.getInt(0)).isZero();
                    assertThat(bigCount.getInt(1)).isEqualTo(-1);
                    assertThat(bigCount.getInt(2)).isEqualTo(12345);
                }
            }
        }

        @Test
        void float16Column_roundTripsEveryHalfBitThroughParquetAndBack(@TempDir Path tmp) throws Exception {
            // Given a non-null and a nullable F16 column: 1.5, -0.0, a NaN payload and +Inf, whose
            // bits a trip through float would change (the NaN payload, the sign of zero)
            short[] bits = {(short) 0x3E00, (short) 0x8000, (short) 0x7C01, (short) 0x7C00};
            Path original = writeVortex(tmp, "f16.vortex",
                    List.of(ColumnName.of("h"), ColumnName.of("n")),
                    List.of(new DType.Primitive(PType.F16, false), new DType.Primitive(PType.F16, true)),
                    Map.of(
                            ColumnName.of("h"), bits,
                            ColumnName.of("n"), new Short[]{bits[0], null, bits[2], bits[3]}));
            Path parquet = tmp.resolve("f16.parquet");
            Path reimported = tmp.resolve("f16-reimported.vortex");

            // When
            ParquetExporter.exportParquet(original, parquet);
            ParquetImporter.importParquet(parquet, reimported);

            // Then
            try (VortexReader reader = VortexReader.open(reimported);
                 ScanIterator iter = reader.scan(ScanOptions.all());
                 Arena arena = Arena.ofConfined()) {
                assertThat(reader.dtype()).isEqualTo(new DType.Struct(
                        List.of(ColumnName.of("h"), ColumnName.of("n")),
                        List.of(new DType.Primitive(PType.F16, false), new DType.Primitive(PType.F16, true)), false));
                assertThat(iter.hasNext()).isTrue();
                try (Chunk chunk = iter.next()) {
                    Float16Array plain = chunk.column(ColumnName.of("h"));
                    MemorySegment plainBits = plain.materialize(arena);
                    MaskedArray nullable = chunk.column(ColumnName.of("n"));
                    MemorySegment nullableBits = ((Float16Array) nullable.inner()).materialize(arena);
                    for (int i = 0; i < bits.length; i++) {
                        assertThat(plainBits.getAtIndex(VortexFormat.LE_SHORT, i)).as("h row %d", i).isEqualTo(bits[i]);
                    }
                    assertThat(nullable.isValid(0)).isTrue();
                    assertThat(nullable.isValid(1)).isFalse();
                    for (int i : new int[]{0, 2, 3}) {
                        assertThat(nullable.isValid(i)).isTrue();
                        assertThat(nullableBits.getAtIndex(VortexFormat.LE_SHORT, i)).as("n row %d", i).isEqualTo(bits[i]);
                    }
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
                    assertThat(chunk.as(ColumnName.of("events"), Instant.class)).containsExactlyElementsOf(instants);
                }
            }
        }

        @Test
        void nullableTimestampColumn_withNulls_roundTripThroughParquetAndBack(@TempDir Path tmp) throws Exception {
            // Given — an OPTIONAL Parquet TIMESTAMP holding nulls, first row included (#627: the
            // import read a null into a primitive long[] and failed as "declared REQUIRED")
            DType.Extension tsDtype = TimestampDtype.of(TimeUnit.Microseconds, null, true);
            List<Instant> instants = Arrays.asList(
                    null,
                    Instant.ofEpochMilli(-1_500L),
                    null,
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
                    assertThat(chunk.as(ColumnName.of("events"), Instant.class)).containsExactlyElementsOf(instants);
                }
            }
        }

        @ParameterizedTest(name = "DECIMAL({0}, 2)")
        @CsvSource({"9", "10", "15", "18", "19", "38", "76"})
        void decimalColumn_roundTripsThroughParquetAndBack(int precision, @TempDir Path tmp) throws Exception {
            // Given — a nullable decimal holding nulls, negatives and the widest value of its precision (#655)
            DType.Decimal decimal = DType.Decimal.of(precision, 2, true);
            BigDecimal widest = new BigDecimal(java.math.BigInteger.TEN.pow(precision).subtract(java.math.BigInteger.ONE), 2);
            List<BigDecimal> values = Arrays.asList(
                    new BigDecimal("17.00"), null, new BigDecimal("-0.05"), widest, widest.negate(), BigDecimal.ZERO.setScale(2));
            Path original = writeVortex(tmp, "original.vortex",
                    List.of(ColumnName.of("d")), List.of(decimal),
                    Map.of(ColumnName.of("d"), values.toArray(BigDecimal[]::new)));
            Path parquet = tmp.resolve("out.parquet");
            Path reimported = tmp.resolve("reimported.vortex");

            // When
            ParquetExporter.exportParquet(original, parquet);
            ParquetImporter.importParquet(parquet, reimported);

            // Then
            try (VortexReader reader = VortexReader.open(reimported);
                 ScanIterator iter = reader.scan(ScanOptions.all())) {
                assertThat(((DType.Struct) reader.dtype()).fieldTypes().getFirst()).isEqualTo(decimal);
                try (Chunk chunk = iter.next()) {
                    MaskedArray masked = (MaskedArray) chunk.column(ColumnName.of("d"));
                    DecimalArray inner = (DecimalArray) masked.inner();
                    List<BigDecimal> result = new java.util.ArrayList<>();
                    for (int i = 0; i < values.size(); i++) {
                        result.add(masked.isValid(i) ? inner.getDecimal(i) : null);
                    }
                    assertThat(result).containsExactlyElementsOf(values);
                }
            }
        }
    }

    @Nested
    class NestedColumns {

        private static final DType.Struct IMAGE = new DType.Struct(
                List.of(ColumnName.of("bytes"), ColumnName.of("path")),
                List.of(DTypes.BINARY, new DType.Utf8(true)), true);
        private static final DType.Struct TURN = new DType.Struct(
                List.of(ColumnName.of("question"), ColumnName.of("answer")),
                List.of(DTypes.UTF8, new DType.Utf8(true)), false);
        private static final DType.Struct DOC = new DType.Struct(
                List.of(ColumnName.of("tags"), ColumnName.of("n")),
                List.of(new DType.List(DTypes.UTF8, false), DTypes.I32), true);
        private static final List<ColumnName> NAMES = List.of(ColumnName.of("img"), ColumnName.of("emb"),
                ColumnName.of("vec"), ColumnName.of("conv"), ColumnName.of("doc"));
        private static final List<DType> TYPES = List.of(IMAGE, new DType.List(DTypes.F32, true),
                new DType.FixedSizeList(DTypes.I32, 2, false), new DType.List(TURN, false), DOC);

        /// The column shapes of #721's Raincloud datasets (`mnist`'s image struct,
        /// `cohere-wikipedia-simple-embed`'s embedding list, `coig`'s list of structs), plus the two
        /// shapes Parquet forbids entries under: row 1's null `emb` list still spans one element, and
        /// row 1's null `doc` struct holds a `tags` list spanning one, so both must be dropped.
        private Path writeNested(Path tmp) throws Exception {
            boolean[] row1Null = {true, false, true};
            return writeVortex(tmp, "nested.vortex", NAMES, TYPES, Map.of(
                    ColumnName.of("img"), new NullableData(new StructData(List.of(
                            new byte[][]{{1}, {}, {2, 3}},
                            new NullableData(new String[]{"a.png", "", ""}, new boolean[]{true, false, false}))), row1Null),
                    ColumnName.of("emb"), new NullableData(
                            new ListData(new float[]{1f, 2f, 9f}, new long[]{0, 2, 3, 3}, 3), row1Null),
                    ColumnName.of("vec"), new FixedSizeListData(new int[]{1, 2, 3, 4, 5, 6}, 3),
                    ColumnName.of("conv"), new ListData(new StructData(List.of(
                            new String[]{"q1", "q2", "q3"},
                            new NullableData(new String[]{"a1", "", "a3"}, new boolean[]{true, false, true}))),
                            new long[]{0, 2, 2, 3}, 3),
                    ColumnName.of("doc"), new NullableData(new StructData(List.of(
                            new ListData(new String[]{"a", "x", "b"}, new long[]{0, 1, 2, 3}, 3),
                            new int[]{1, 2, 3})), row1Null)));
        }

        @Test
        void structAndListColumns_exportAsParquetGroups(@TempDir Path tmp) throws Exception {
            // Given
            Path vortex = writeNested(tmp);
            Path parquet = tmp.resolve("out.parquet");

            // When
            ParquetExporter.exportParquet(vortex, parquet);

            // Then
            try (ParquetFileReader reader = ParquetFileReader.open(InputFile.of(parquet));
                 RowReader rows = reader.buildRowReader().build()) {
                rows.next();
                assertThat(rows.getStruct("img").getBinary("bytes")).isEqualTo(new byte[]{1});
                assertThat(rows.getStruct("img").getString("path")).isEqualTo("a.png");
                assertThat(rows.getList("emb").floats()).containsExactly(1f, 2f);
                assertThat(rows.getList("vec").values()).containsExactly(1, 2);
                List<dev.hardwood.row.PqStruct> turns = rows.getList("conv").structs();
                assertThat(turns).hasSize(2);
                assertThat(turns.get(0).getString("question")).isEqualTo("q1");
                assertThat(turns.get(0).getString("answer")).isEqualTo("a1");
                assertThat(turns.get(1).getString("question")).isEqualTo("q2");
                assertThat(turns.get(1).isNull("answer")).isTrue();
                assertThat(rows.getStruct("doc").getList("tags").strings()).containsExactly("a");
                assertThat(rows.getStruct("doc").getInt("n")).isEqualTo(1);

                rows.next();
                assertThat(rows.isNull("img")).isTrue();
                assertThat(rows.isNull("emb")).isTrue();
                assertThat(rows.getList("vec").values()).containsExactly(3, 4);
                assertThat(rows.getList("conv").isEmpty()).isTrue();
                assertThat(rows.isNull("doc")).isTrue();

                rows.next();
                assertThat(rows.getStruct("img").getBinary("bytes")).isEqualTo(new byte[]{2, 3});
                assertThat(rows.getStruct("img").isNull("path")).isTrue();
                assertThat(rows.getList("emb").isEmpty()).isTrue();
                assertThat(rows.getList("vec").values()).containsExactly(5, 6);
                assertThat(rows.getList("conv").structs().getFirst().getString("answer")).isEqualTo("a3");
                assertThat(rows.getStruct("doc").getList("tags").strings()).containsExactly("b");
                assertThat(rows.getStruct("doc").getInt("n")).isEqualTo(3);
                assertThat(rows.hasNext()).isFalse();
            }
        }

        @Test
        void structAndListColumns_roundTripTheirDtypesThroughParquet(@TempDir Path tmp) throws Exception {
            // Given
            Path vortex = writeNested(tmp);
            Path parquet = tmp.resolve("out.parquet");
            Path reimported = tmp.resolve("reimported.vortex");

            // When
            ParquetExporter.exportParquet(vortex, parquet);
            ParquetImporter.importParquet(parquet, reimported);

            // Then — every dtype survives, except that Parquet has no fixed-size list to keep `vec` one
            try (VortexReader reader = VortexReader.open(reimported)) {
                List<DType> result = ((DType.Struct) reader.dtype()).fieldTypes();
                assertThat(result).containsExactly(TYPES.get(0), TYPES.get(1),
                        new DType.List(DTypes.I32, false), TYPES.get(3), TYPES.get(4));
            }
        }

        /// Pins a known limitation: the reader folds a struct's validity into its fields, so a
        /// present struct whose every field is null cannot be told from a null struct, and exports
        /// as one (the reading `CsvExporter` takes too).
        @Test
        void presentStructWithEveryFieldNull_exportsAsNullStruct(@TempDir Path tmp) throws Exception {
            // Given — row 0 is a present struct holding {null, null}
            DType.Struct type = new DType.Struct(List.of(ColumnName.of("a"), ColumnName.of("b")),
                    List.of(new DType.Utf8(true), DTypes.I32.asNullable()), true);
            Path vortex = writeVortex(tmp, "allnull.vortex", List.of(ColumnName.of("s")), List.of(type),
                    Map.of(ColumnName.of("s"), new NullableData(new StructData(List.of(
                            new NullableData(new String[]{"", "x"}, new boolean[]{false, true}),
                            new NullableData(new int[]{0, 1}, new boolean[]{false, true}))),
                            new boolean[]{true, true})));
            Path parquet = tmp.resolve("out.parquet");

            // When
            ParquetExporter.exportParquet(vortex, parquet);

            // Then
            try (ParquetFileReader reader = ParquetFileReader.open(InputFile.of(parquet));
                 RowReader rows = reader.buildRowReader().build()) {
                rows.next();
                assertThat(rows.isNull("s")).isTrue();
                rows.next();
                assertThat(rows.getStruct("s").getString("a")).isEqualTo("x");
            }
        }

        @Test
        void mapColumn_exportsAsParquetMap(@TempDir Path tmp) throws Exception {
            // Given — a nullable map<utf8, i64?>: {a: 1, b: null}, null, {}
            DType.Map type = new DType.Map(DTypes.UTF8, DTypes.I64.asNullable(), false, true);
            ListViewData entries = new ListViewData(new StructData(List.of(new String[]{"a", "b"},
                    new NullableData(new long[]{1, 0}, new boolean[]{true, false}))),
                    new int[]{0, 2, 2}, new int[]{2, 0, 0}, 3);
            Path vortex = writeVortex(tmp, "map.vortex", List.of(ColumnName.of("m")), List.of(type),
                    Map.of(ColumnName.of("m"), new NullableData(entries, new boolean[]{true, false, true})));
            Path parquet = tmp.resolve("out.parquet");

            // When
            ParquetExporter.exportParquet(vortex, parquet);

            // Then
            try (ParquetFileReader reader = ParquetFileReader.open(InputFile.of(parquet));
                 RowReader rows = reader.buildRowReader().build()) {
                rows.next();
                assertThat(rows.getMap("m").size()).isEqualTo(2);
                assertThat(rows.getMap("m").getValue("a")).isEqualTo(1L);
                assertThat(rows.getMap("m").containsKey("b")).isTrue();
                assertThat(rows.getMap("m").getValue("b")).isNull();
                rows.next();
                assertThat(rows.isNull("m")).isTrue();
                rows.next();
                assertThat(rows.getMap("m").isEmpty()).isTrue();
            }
        }
    }
}
