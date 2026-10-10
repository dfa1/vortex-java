package io.github.dfa1.vortex.calcite;

import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.writer.VortexWriter;
import io.github.dfa1.vortex.writer.WriteOptions;

import org.apache.calcite.jdbc.CalciteConnection;
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

/// An F32 column is a `REAL`, which Calcite's generated code reads as `java.lang.Float`; the
/// enumerator returned a `Double`, so any SQL query that touched an F32 column failed with a
/// `ClassCastException`.
class Float32ColumnSqlTest {

    @TempDir
    Path tmp;

    @Test
    void selectAndFilterOnAnF32Column_runEndToEnd() throws Exception {
        // Given
        Path file = tmp.resolve("f32.vortex");
        DType.Struct schema = DType.structBuilder().field(ColumnName.of("r"), DType.F32).build();
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             VortexWriter writer = VortexWriter.create(ch, schema, WriteOptions.defaults())) {
            writer.writeChunk(Map.of(ColumnName.of("r"), new float[]{1.5f, 2.5f, 3.5f}));
        }
        Properties info = new Properties();
        info.setProperty("lex", "JAVA");

        // When
        List<Float> all = new ArrayList<>();
        List<Float> filtered = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection("jdbc:calcite:", info)) {
            conn.unwrap(CalciteConnection.class).getRootSchema().add("vtx", new VortexSchema(Map.of("data", file)));
            try (Statement st = conn.createStatement()) {
                try (ResultSet rs = st.executeQuery("select r from vtx.data")) {
                    while (rs.next()) {
                        all.add(rs.getFloat(1));
                    }
                }
                try (ResultSet rs = st.executeQuery("select r from vtx.data where r > 2.0")) {
                    while (rs.next()) {
                        filtered.add(rs.getFloat(1));
                    }
                }
            }
        }

        // Then
        assertThat(all).containsExactly(1.5f, 2.5f, 3.5f);
        assertThat(filtered).containsExactly(2.5f, 3.5f);
    }
}
