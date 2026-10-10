package io.github.dfa1.vortex.calcite;

import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.Editions;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.VortexReader;
import io.github.dfa1.vortex.writer.VortexWriter;
import io.github.dfa1.vortex.writer.WriteOptions;
import io.github.dfa1.vortex.writer.encode.NullableData;

import org.apache.calcite.jdbc.CalciteConnection;
import org.apache.calcite.jdbc.JavaTypeFactoryImpl;
import org.apache.calcite.linq4j.Enumerator;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.sql.type.SqlTypeName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/// #515: a table with an `F16` column could not be created (`unsupported ptype: F16`), and its
/// rows, SUM and filter literals had no F16 case either. SQL has no half-precision type, so an
/// F16 column is a `REAL`, read as the exact `float` the half widens to.
class Float16ColumnTest {

    private static final ColumnName H = ColumnName.of("h");
    private static final short ONE_AND_A_HALF = Float.floatToFloat16(1.5f);
    private static final short TWO_AND_A_HALF = Float.floatToFloat16(2.5f);

    @TempDir
    Path tmp;

    @Test
    void rowType_isReal() throws Exception {
        // Given
        Path file = write("type.vortex", WriteOptions.defaults(), false);

        // When
        RelDataType result = new VortexTable(file).getRowType(new JavaTypeFactoryImpl());

        // Then
        assertThat(result.getFieldList().getFirst().getType().getSqlTypeName()).isEqualTo(SqlTypeName.REAL);
        assertThat(result.getFieldList().getFirst().getType().isNullable()).isFalse();
    }

    @Test
    void scan_readsEveryRowAsAWidenedDouble() throws Exception {
        // Given
        Path file = write("scan.vortex", WriteOptions.defaults(), false);

        // When
        List<Object[]> result = drain(new VortexTable(file));

        // Then
        assertThat(result).extracting(row -> row[0]).containsExactly(1.5f, 2.5f, 1.5f);
    }

    @Test
    void scan_nullableColumnYieldsSqlNullForInvalidRows() throws Exception {
        // Given the middle row is null
        Path file = write("nullable.vortex", WriteOptions.defaults(), true);

        // When
        List<Object[]> result = drain(new VortexTable(file));

        // Then
        assertThat(result).extracting(row -> row[0]).containsExactly(1.5f, null, 1.5f);
    }

    @Test
    void sum_foldsFromEitherTheFullScanOrTheZoneMaps() throws Exception {
        // Given the same data without zone maps (streaming scanSum) and with legacy sums per zone
        Path scanned = write("sum-scan.vortex",
                WriteOptions.cascading(0).withZoneMaps(false).withGlobalDict(true).withoutEditions(), false);
        Path zoned = write("sum-zones.vortex",
                WriteOptions.cascading(0).withZoneMaps(true).withEdition(Editions.CORE_2025_10_0), false);

        // When / Then 1.5 + 2.5 + 1.5, exact in half precision
        try (VortexReader reader = VortexReader.open(scanned, ReadRegistry.builder().registerDefaults().build())) {
            VortexAggregates.Summary result = VortexAggregates.of(reader, H);
            assertThat(result.sumSource()).isEqualTo(VortexAggregates.Source.FULL_SCAN);
            assertThat(result.sum().doubleValue()).isEqualTo(5.5);
        }
        try (VortexReader reader = VortexReader.open(zoned, ReadRegistry.builder().registerDefaults().build())) {
            VortexAggregates.Summary result = VortexAggregates.of(reader, H);
            assertThat(result.sum().doubleValue()).isEqualTo(5.5);
            assertThat(((Number) result.min()).doubleValue()).isEqualTo(1.5);
            assertThat(((Number) result.max()).doubleValue()).isEqualTo(2.5);
        }
    }

    @Test
    void sqlSelectOfAnF16Column_runsEndToEnd() throws Exception {
        // Given the F32 path used to fail every SQL query with a ClassCastException (Double to Float)
        Path file = write("select.vortex", WriteOptions.defaults(), false);
        Properties info = new Properties();
        info.setProperty("lex", "JAVA");

        // When
        List<Float> result = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection("jdbc:calcite:", info)) {
            conn.unwrap(CalciteConnection.class).getRootSchema().add("vtx", new VortexSchema(Map.of("data", file)));
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("select h from vtx.data")) {
                while (rs.next()) {
                    result.add(rs.getFloat(1));
                }
            }
        }

        // Then
        assertThat(result).containsExactly(1.5f, 2.5f, 1.5f);
    }

    @Test
    void sqlWhereOnAnF16Column_returnsExactlyTheMatchingRows() throws Exception {
        // Given two chunks (one zone each) [1.5, 1.5] and [2.5, 3.5]; the predicate translator
        // coerces the literal to the Double the zone-map stats compare as, so chunk one can be pruned
        Path file = tmp.resolve("sql.vortex");
        DType.Struct schema = DType.structBuilder().field(H, DType.F16).build();
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             VortexWriter writer = VortexWriter.create(ch, schema,
                     WriteOptions.cascading(0).withZoneMaps(true).withEdition(Editions.CORE_2025_10_0))) {
            writer.writeChunk(Map.of(H, new short[]{ONE_AND_A_HALF, ONE_AND_A_HALF}));
            writer.writeChunk(Map.of(H, new short[]{TWO_AND_A_HALF, Float.floatToFloat16(3.5f)}));
        }
        Properties info = new Properties();
        info.setProperty("lex", "JAVA");

        // When
        List<Double> result = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection("jdbc:calcite:", info)) {
            conn.unwrap(CalciteConnection.class).getRootSchema().add("vtx", new VortexSchema(Map.of("data", file)));
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("select h from vtx.data where h > 2.0")) {
                while (rs.next()) {
                    result.add(rs.getDouble(1));
                }
            }
        }

        // Then
        assertThat(result).containsExactly(2.5, 3.5);
    }

    private Path write(String name, WriteOptions options, boolean nullable) throws Exception {
        Path file = tmp.resolve(name);
        DType.Struct schema = DType.structBuilder().field(H, nullable ? new DType.Primitive(io.github.dfa1.vortex.core.model.PType.F16, true) : DType.F16).build();
        short[] values = {ONE_AND_A_HALF, TWO_AND_A_HALF, ONE_AND_A_HALF};
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             VortexWriter writer = VortexWriter.create(ch, schema, options)) {
            writer.writeChunk(Map.of(H, nullable
                    ? new NullableData(new short[]{ONE_AND_A_HALF, 0, ONE_AND_A_HALF}, new boolean[]{true, false, true})
                    : values));
        }
        return file;
    }

    private static List<Object[]> drain(VortexTable table) {
        List<Object[]> rows = new ArrayList<>();
        Enumerator<Object[]> en = table.scan(null, List.of(), null).enumerator();
        try {
            while (en.moveNext()) {
                rows.add(en.current());
            }
        } finally {
            en.close();
        }
        return rows;
    }
}
