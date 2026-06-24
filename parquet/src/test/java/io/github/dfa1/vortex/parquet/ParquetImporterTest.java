package io.github.dfa1.vortex.parquet;

import dev.hardwood.metadata.FieldPath;
import dev.hardwood.metadata.LogicalType;
import dev.hardwood.metadata.PhysicalType;
import dev.hardwood.metadata.RepetitionType;
import dev.hardwood.schema.ColumnSchema;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.reader.Chunk;
import io.github.dfa1.vortex.reader.ScanIterator;
import io.github.dfa1.vortex.reader.ScanOptions;
import io.github.dfa1.vortex.reader.VortexReader;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.array.VarBinArray;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ParquetImporterTest {

    private static ColumnSchema col(String name, PhysicalType type, RepetitionType rep, LogicalType logical) {
        return new ColumnSchema(FieldPath.of(name), type, rep, null, 0, 0, 0, logical);
    }

    @Nested
    class TypeMapping {

        @Test
        void boolean_mapsToBool_carryingNullability() {
            // Given / When / Then — REQUIRED is non-null, OPTIONAL is nullable
            assertThat(ParquetImporter.mapDType(col("b", PhysicalType.BOOLEAN, RepetitionType.REQUIRED, null)))
                    .isEqualTo(DType.BOOL);
            assertThat(ParquetImporter.mapDType(col("b", PhysicalType.BOOLEAN, RepetitionType.OPTIONAL, null)))
                    .isEqualTo(new DType.Bool(true));
        }

        @Test
        void int32_withoutAnnotation_mapsToI32() {
            // When
            DType result = ParquetImporter.mapDType(col("i", PhysicalType.INT32, RepetitionType.REQUIRED, null));

            // Then
            assertThat(result).isEqualTo(DType.I32);
        }

        @ParameterizedTest
        @CsvSource({
                "8,  true,  I8",
                "8,  false, U8",
                "16, true,  I16",
                "16, false, U16",
                "32, true,  I32",
                "32, false, U32",
        })
        void int32_withIntAnnotation_mapsToSizedPType(int bitWidth, boolean signed, PType expected) {
            // Given — INT32 carrying a width/sign annotation selects the narrow PType
            ColumnSchema schema = col("i", PhysicalType.INT32, RepetitionType.REQUIRED,
                    new LogicalType.IntType(bitWidth, signed));

            // When
            DType result = ParquetImporter.mapDType(schema);

            // Then
            assertThat(result).isEqualTo(new DType.Primitive(expected, false));
        }

        @Test
        void int64_signedAndUnsigned_mapToI64AndU64() {
            // Given / When / Then
            assertThat(ParquetImporter.mapDType(col("l", PhysicalType.INT64, RepetitionType.REQUIRED, null)))
                    .isEqualTo(DType.I64);
            assertThat(ParquetImporter.mapDType(col("l", PhysicalType.INT64, RepetitionType.REQUIRED,
                    new LogicalType.IntType(64, true)))).isEqualTo(DType.I64);
            assertThat(ParquetImporter.mapDType(col("l", PhysicalType.INT64, RepetitionType.REQUIRED,
                    new LogicalType.IntType(64, false)))).isEqualTo(DType.U64);
        }

        @ParameterizedTest
        @CsvSource({"MILLIS", "MICROS", "NANOS"})
        void int64_timestamp_mapsToTimestampExtensionOverI64(LogicalType.TimeUnit unit) {
            // Given — a TIMESTAMP-annotated INT64
            ColumnSchema schema = col("ts", PhysicalType.INT64, RepetitionType.OPTIONAL,
                    new LogicalType.TimestampType(true, unit));

            // When
            DType result = ParquetImporter.mapDType(schema);

            // Then — vortex.timestamp extension over nullable I64 storage
            assertThat(result).isInstanceOf(DType.Extension.class);
            DType.Extension ext = (DType.Extension) result;
            assertThat(ext.extensionId()).isEqualTo("vortex.timestamp");
            assertThat(ext.storageDType()).isEqualTo(new DType.Primitive(PType.I64, true));
            assertThat(ext.nullable()).isTrue();
        }

        @Test
        void float_and_double_mapToF32AndF64() {
            // Given / When / Then
            assertThat(ParquetImporter.mapDType(col("f", PhysicalType.FLOAT, RepetitionType.REQUIRED, null)))
                    .isEqualTo(DType.F32);
            assertThat(ParquetImporter.mapDType(col("d", PhysicalType.DOUBLE, RepetitionType.REQUIRED, null)))
                    .isEqualTo(DType.F64);
        }

        @Test
        void byteArray_stringLikeAnnotations_mapToUtf8() {
            // Given — STRING / ENUM / JSON are all logical strings
            for (LogicalType logical : List.of(new LogicalType.StringType(),
                    new LogicalType.EnumType(), new LogicalType.JsonType())) {
                // When
                DType result = ParquetImporter.mapDType(
                        col("s", PhysicalType.BYTE_ARRAY, RepetitionType.OPTIONAL, logical));

                // Then
                assertThat(result).as("logical %s", logical).isEqualTo(new DType.Utf8(true));
            }
        }

        @Test
        void byteArray_withoutStringAnnotation_throws() {
            // Given — raw BYTE_ARRAY with no string logical type is unsupported
            ColumnSchema schema = col("blob", PhysicalType.BYTE_ARRAY, RepetitionType.REQUIRED, null);

            // When / Then
            assertThatThrownBy(() -> ParquetImporter.mapDType(schema))
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("blob");
        }

        @ParameterizedTest
        @CsvSource({"INT96", "FIXED_LEN_BYTE_ARRAY"})
        void unsupportedPhysicalType_throws(PhysicalType type) {
            // Given
            ColumnSchema schema = col("x", type, RepetitionType.REQUIRED, null);

            // When / Then
            assertThatThrownBy(() -> ParquetImporter.mapDType(schema))
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("unsupported Parquet physical type");
        }
    }

    @Nested
    class FilterColumns {

        @Test
        void keepsRequestedColumnsInRequestedOrder() {
            // Given — schema a, b, c; request c, a
            List<ColumnSchema> all = List.of(
                    col("a", PhysicalType.INT32, RepetitionType.REQUIRED, null),
                    col("b", PhysicalType.INT32, RepetitionType.REQUIRED, null),
                    col("c", PhysicalType.INT32, RepetitionType.REQUIRED, null));

            // When
            List<ColumnSchema> result = ParquetImporter.filterColumns(all, List.of("c", "a"));

            // Then — projection order wins over schema order
            assertThat(result).extracting(ColumnSchema::name).containsExactly("c", "a");
        }

        @Test
        void unknownColumn_throws() {
            // Given
            List<ColumnSchema> all = List.of(col("a", PhysicalType.INT32, RepetitionType.REQUIRED, null));

            // When / Then
            assertThatThrownBy(() -> ParquetImporter.filterColumns(all, List.of("missing")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("missing");
        }
    }

    @Nested
    class Import {

        @Test
        void importsFixture_schemaAndRowCount(@TempDir Path tmp) throws Exception {
            // Given — 100-row TPC-DS customer fixture (INT64 + STRING, all nullable)
            Path vortex = tmp.resolve("out.vortex");

            // When
            ParquetImporter.importParquet(fixture(), vortex);

            // Then
            try (VortexReader reader = VortexReader.open(vortex)) {
                assertThat(reader.dtype()).isInstanceOf(DType.Struct.class);
                DType.Struct schema = (DType.Struct) reader.dtype();
                assertThat(schema.fieldNames()).contains("c_customer_sk", "c_first_name");
                assertThat(countRows(reader)).isEqualTo(100L);
            }
        }

        @Test
        void importsFixture_columnValuesRoundTrip(@TempDir Path tmp) throws Exception {
            // Given
            Path vortex = tmp.resolve("out.vortex");

            // When
            ParquetImporter.importParquet(fixture(), vortex);

            // Then — known first three values of each column
            try (VortexReader reader = VortexReader.open(vortex);
                 ScanIterator iter = reader.scan(ScanOptions.all())) {
                assertThat(iter.hasNext()).isTrue();
                try (Chunk first = iter.next()) {
                    LongArray sk = first.column("c_customer_sk");
                    assertThat(sk.getLong(0)).isEqualTo(100L);
                    assertThat(sk.getLong(1)).isEqualTo(99L);
                    assertThat(sk.getLong(2)).isEqualTo(98L);

                    VarBinArray name = first.column("c_first_name");
                    assertThat(name.getString(0)).isEqualTo("Jeannette");
                    assertThat(name.getString(1)).isEqualTo("Austin");
                    assertThat(name.getString(2)).isEqualTo("David");
                }
            }
        }

        @Test
        void projection_importsOnlyRequestedColumns(@TempDir Path tmp) throws Exception {
            // Given — project a single column out of the fixture
            Path vortex = tmp.resolve("out.vortex");
            ImportOptions options = ImportOptions.defaults().withColumns(List.of("c_customer_sk"));

            // When
            ParquetImporter.importParquet(fixture(), vortex, options);

            // Then — only the projected column survives
            try (VortexReader reader = VortexReader.open(vortex)) {
                DType.Struct schema = (DType.Struct) reader.dtype();
                assertThat(schema.fieldNames()).containsExactly("c_customer_sk");
                assertThat(countRows(reader)).isEqualTo(100L);
            }
        }

        @Test
        void smallChunkSize_splitsIntoMultipleChunks(@TempDir Path tmp) throws Exception {
            // Given — chunk size 30 forces 4 chunks over 100 rows (exercises trim + chunk flush)
            Path vortex = tmp.resolve("out.vortex");
            ImportOptions options = ImportOptions.defaults().withChunkSize(30);

            // When
            ParquetImporter.importParquet(fixture(), vortex, options);

            // Then — row count is preserved across the chunk boundaries
            try (VortexReader reader = VortexReader.open(vortex);
                 ScanIterator iter = reader.scan(ScanOptions.all())) {
                long chunks = 0;
                long rows = 0;
                while (iter.hasNext()) {
                    try (Chunk c = iter.next()) {
                        chunks++;
                        rows += c.rowCount();
                    }
                }
                assertThat(rows).isEqualTo(100L);
                assertThat(chunks).isGreaterThan(1L);
            }
        }

        @Test
        void projection_unknownColumn_throws(@TempDir Path tmp) {
            // Given
            Path vortex = tmp.resolve("out.vortex");
            ImportOptions options = ImportOptions.defaults().withColumns(List.of("does_not_exist"));

            // When / Then
            assertThatThrownBy(() -> ParquetImporter.importParquet(fixture(), vortex, options))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("does_not_exist");
        }
    }

    private static Path fixture() throws Exception {
        return Path.of(ParquetImporterTest.class
                .getResource("/fixtures/delta_encoding_optional_column.parquet").toURI());
    }

    private static long countRows(VortexReader reader) {
        AtomicLong total = new AtomicLong();
        try (ScanIterator iter = reader.scan(ScanOptions.all())) {
            iter.forEachRemaining(c -> total.addAndGet(c.rowCount()));
        }
        return total.get();
    }
}
