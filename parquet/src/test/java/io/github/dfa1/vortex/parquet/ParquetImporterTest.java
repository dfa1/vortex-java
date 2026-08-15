package io.github.dfa1.vortex.parquet;

import io.github.dfa1.vortex.core.model.ColumnName;
import dev.hardwood.metadata.ConvertedType;
import dev.hardwood.metadata.FieldPath;
import dev.hardwood.metadata.LogicalType;
import dev.hardwood.metadata.PhysicalType;
import dev.hardwood.metadata.RepetitionType;
import dev.hardwood.schema.ColumnSchema;
import dev.hardwood.schema.SchemaNode;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.reader.Chunk;
import io.github.dfa1.vortex.reader.ScanIterator;
import io.github.dfa1.vortex.reader.ScanOptions;
import io.github.dfa1.vortex.reader.VortexReader;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.VarBinArray;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ParquetImporterTest {

    private static ColumnSchema col(String name, PhysicalType type, RepetitionType rep, LogicalType logical) {
        return new ColumnSchema(FieldPath.of(name), type, rep, null, 0, 0, 0, logical);
    }

    private static SchemaNode.PrimitiveNode primitiveNode(
            String name, PhysicalType type, RepetitionType rep, LogicalType logical) {
        return new SchemaNode.PrimitiveNode(name, type, rep, logical, 0, 0, 0);
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
        void byteArray_withoutAnnotation_mapsToBinary() {
            // Given — raw BYTE_ARRAY with no logical-type annotation (e.g. an embedded audio
            // blob, Raincloud's waxal-dagbani-asr-test "audio.bytes" field)
            ColumnSchema schema = col("blob", PhysicalType.BYTE_ARRAY, RepetitionType.OPTIONAL, null);

            // When
            DType result = ParquetImporter.mapDType(schema);

            // Then
            assertThat(result).isEqualTo(new DType.Binary(true));
        }

        @Test
        void byteArray_unsupportedAnnotation_stillThrows() {
            // Given — a BYTE_ARRAY annotation that is neither string-like nor absent (e.g.
            // DECIMAL) must not be silently downgraded to raw Binary, losing its semantic
            ColumnSchema schema = col("amount", PhysicalType.BYTE_ARRAY, RepetitionType.REQUIRED,
                    new LogicalType.DecimalType(2, 10));

            // When / Then
            assertThatThrownBy(() -> ParquetImporter.mapDType(schema))
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("amount");
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
    class FilterTopLevel {

        @Test
        void keepsRequestedColumnsInRequestedOrder() {
            // Given — schema a, b, c; request c, a
            List<SchemaNode> all = List.of(
                    primitiveNode("a", PhysicalType.INT32, RepetitionType.REQUIRED, null),
                    primitiveNode("b", PhysicalType.INT32, RepetitionType.REQUIRED, null),
                    primitiveNode("c", PhysicalType.INT32, RepetitionType.REQUIRED, null));

            // When
            List<SchemaNode> result = ParquetImporter.filterTopLevel(all, List.of("c", "a"));

            // Then — projection order wins over schema order
            assertThat(result).extracting(SchemaNode::name).containsExactly("c", "a");
        }

        @Test
        void unknownColumn_throws() {
            // Given
            List<SchemaNode> all = List.of(primitiveNode("a", PhysicalType.INT32, RepetitionType.REQUIRED, null));

            // When / Then
            assertThatThrownBy(() -> ParquetImporter.filterTopLevel(all, List.of("missing")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("missing");
        }
    }

    @Nested
    class NestedTypeMapping {

        @Test
        void struct_mapsToDTypeStruct_recursingOverFields() {
            // Given — a plain group (no LIST/MAP annotation) with two primitive fields, matching
            // Raincloud's waxal-dagbani-asr-test "audio: STRUCT{bytes: binary, path: string}"
            SchemaNode.GroupNode group = new SchemaNode.GroupNode(
                    "audio", RepetitionType.OPTIONAL, null, null,
                    List.of(
                            primitiveNode("bytes", PhysicalType.BYTE_ARRAY, RepetitionType.OPTIONAL, null),
                            primitiveNode("path", PhysicalType.BYTE_ARRAY, RepetitionType.OPTIONAL,
                                    new LogicalType.StringType())),
                    0, 0);

            // When
            DType result = ParquetImporter.mapDType(group);

            // Then
            assertThat(result).isEqualTo(new DType.Struct(
                    List.of(ColumnName.of("bytes"), ColumnName.of("path")),
                    List.of(new DType.Binary(true), new DType.Utf8(true)),
                    true));
        }

        @Test
        void list_ofPrimitive_mapsToDTypeList_twoLevelEncoding() {
            // Given — legacy 2-level LIST encoding: the repeated field is itself the element
            // (not a group), matching Raincloud's finetranslations-swedish "og_chunks: LIST<string>"
            SchemaNode.GroupNode list = new SchemaNode.GroupNode(
                    "chunks", RepetitionType.OPTIONAL, ConvertedType.LIST, null,
                    List.of(primitiveNode("chunks_tuple", PhysicalType.BYTE_ARRAY, RepetitionType.REPEATED,
                            new LogicalType.StringType())),
                    0, 0);

            // When
            DType result = ParquetImporter.mapDType(list);

            // Then
            assertThat(result).isEqualTo(new DType.List(new DType.Utf8(false), true));
        }

        @Test
        void list_ofStruct_mapsToDTypeListOfStruct_threeLevelEncoding() {
            // Given — standard 3-level LIST encoding wrapping a struct element, matching
            // Raincloud's ultrachat-200k "messages: LIST<STRUCT{content, role}>"
            SchemaNode.GroupNode element = new SchemaNode.GroupNode(
                    "element", RepetitionType.OPTIONAL, null, null,
                    List.of(
                            primitiveNode("content", PhysicalType.BYTE_ARRAY, RepetitionType.OPTIONAL,
                                    new LogicalType.StringType()),
                            primitiveNode("role", PhysicalType.BYTE_ARRAY, RepetitionType.OPTIONAL,
                                    new LogicalType.StringType())),
                    0, 0);
            SchemaNode.GroupNode wrapper = new SchemaNode.GroupNode(
                    "list", RepetitionType.REPEATED, null, null, List.of(element), 0, 0);
            SchemaNode.GroupNode messages = new SchemaNode.GroupNode(
                    "messages", RepetitionType.OPTIONAL, null, new LogicalType.ListType(),
                    List.of(wrapper), 0, 0);

            // When
            DType result = ParquetImporter.mapDType(messages);

            // Then
            DType.Struct expectedElement = new DType.Struct(
                    List.of(ColumnName.of("content"), ColumnName.of("role")),
                    List.of(new DType.Utf8(true), new DType.Utf8(true)),
                    true);
            assertThat(result).isEqualTo(new DType.List(expectedElement, true));
        }

        @Test
        void mapGroup_throws() {
            // Given — MAP is not yet supported
            SchemaNode.GroupNode keyValue = new SchemaNode.GroupNode(
                    "key_value", RepetitionType.REPEATED, null, null,
                    List.of(
                            primitiveNode("key", PhysicalType.BYTE_ARRAY, RepetitionType.REQUIRED,
                                    new LogicalType.StringType()),
                            primitiveNode("value", PhysicalType.INT32, RepetitionType.OPTIONAL, null)),
                    0, 0);
            SchemaNode.GroupNode map = new SchemaNode.GroupNode(
                    "counts", RepetitionType.OPTIONAL, null, new LogicalType.MapType(),
                    List.of(keyValue), 0, 0);

            // When / Then
            assertThatThrownBy(() -> ParquetImporter.mapDType(map))
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("counts");
        }
    }

    @Nested
    class DuplicateNames {

        @Test
        void noDuplicates_doesNotThrow() {
            // Given — a, b, c are all distinct
            List<ColumnName> names = List.of(ColumnName.of("a"), ColumnName.of("b"), ColumnName.of("c"));

            // When / Then
            assertThatCode(() -> ParquetImporter.checkNoDuplicateNames(names, Path.of("source.parquet")))
                    .doesNotThrowAnyException();
        }

        @Test
        void duplicateName_throwsWithNameAndSourcePath() {
            // Given — two columns both named "A", as seen on the Raincloud uk-price-paid slug (#280): a
            // headerless source CSV made a Parquet conversion tool use the first data row as column
            // names, and two property-type flag columns happened to share the value "A".
            List<ColumnName> names = List.of(ColumnName.of("A"), ColumnName.of("B"), ColumnName.of("A"));
            Path source = Path.of("uk-price-paid.parquet");

            // When / Then
            assertThatThrownBy(() -> ParquetImporter.checkNoDuplicateNames(names, source))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("A")
                    .hasMessageContaining("uk-price-paid.parquet");
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
                assertThat(schema.fieldNames()).contains(ColumnName.of("c_customer_sk"), ColumnName.of("c_first_name"));
                assertThat(reader.layout().rowCount()).isEqualTo(100L);
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

                    // c_first_name is nullable Utf8, so it round-trips as a MaskedArray (validity +
                    // VarBin values child), like nullable primitives. The first three rows are non-null.
                    MaskedArray name = first.column("c_first_name");
                    VarBinArray nameValues = (VarBinArray) name.inner();
                    assertThat(nameValues.getString(0)).isEqualTo("Jeannette");
                    assertThat(nameValues.getString(1)).isEqualTo("Austin");
                    assertThat(nameValues.getString(2)).isEqualTo("David");
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
                assertThat(schema.fieldNames()).containsExactly(ColumnName.of("c_customer_sk"));
                assertThat(reader.layout().rowCount()).isEqualTo(100L);
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
}
