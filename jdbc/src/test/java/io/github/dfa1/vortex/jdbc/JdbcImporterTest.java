package io.github.dfa1.vortex.jdbc;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.array.BoolArray;
import io.github.dfa1.vortex.core.array.DoubleArray;
import io.github.dfa1.vortex.core.array.LongArray;
import io.github.dfa1.vortex.core.array.VarBinArray;
import io.github.dfa1.vortex.io.VortexReader;
import io.github.dfa1.vortex.scan.ScanIterator;
import io.github.dfa1.vortex.scan.ScanOptions;
import io.github.dfa1.vortex.scan.ScanResult;
import io.github.dfa1.vortex.writer.WriteOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcImporterTest {

    private Connection conn;

    @BeforeEach
    void openConnection() throws Exception {
        conn = DriverManager.getConnection("jdbc:h2:mem:test;DB_CLOSE_DELAY=-1");
    }

    @AfterEach
    void closeConnection() throws Exception {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("DROP ALL OBJECTS");
        }
        conn.close();
    }

    @Nested
    class ImportQuery {

        @Test
        void roundTripsAllSupportedTypes(@TempDir Path tmp) throws Exception {
            // Given
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE TABLE events (id BIGINT NOT NULL, name VARCHAR(100) NOT NULL, score DOUBLE NOT NULL, active BOOLEAN NOT NULL)");
                stmt.execute("INSERT INTO events VALUES (1, 'Alice', 1.5, TRUE)");
                stmt.execute("INSERT INTO events VALUES (2, 'Bob', 2.7, FALSE)");
            }
            Path vortex = tmp.resolve("events.vortex");

            // When
            JdbcImporter.importQuery(conn, "SELECT * FROM events ORDER BY id", vortex);

            // Then
            try (VortexReader reader = VortexReader.open(vortex)) {
                assertThat(reader.dtype()).isInstanceOf(DType.Struct.class);
                DType.Struct schema = (DType.Struct) reader.dtype();
                assertThat(schema.fieldNames()).containsExactly("ID", "NAME", "SCORE", "ACTIVE");
                assertThat(schema.fieldTypes().get(0)).isEqualTo(new DType.Primitive(PType.I64, false));
                assertThat(schema.fieldTypes().get(1)).isEqualTo(new DType.Utf8(false));
                assertThat(schema.fieldTypes().get(2)).isEqualTo(new DType.Primitive(PType.F64, false));
                assertThat(schema.fieldTypes().get(3)).isEqualTo(new DType.Bool(false));

                try (ScanIterator iter = reader.scan(ScanOptions.all())) {
                    assertThat(iter.hasNext()).isTrue();
                    ScanResult chunk = iter.next();
                    assertThat(chunk.rowCount()).isEqualTo(2);

                    LongArray ids = chunk.column("ID");
                    assertThat(ids.getLong(0)).isEqualTo(1L);
                    assertThat(ids.getLong(1)).isEqualTo(2L);

                    VarBinArray names = chunk.column("NAME");
                    assertThat(names.getString(0)).isEqualTo("Alice");
                    assertThat(names.getString(1)).isEqualTo("Bob");

                    DoubleArray scores = chunk.column("SCORE");
                    assertThat(scores.getDouble(0)).isEqualTo(1.5);
                    assertThat(scores.getDouble(1)).isEqualTo(2.7);

                    BoolArray active = chunk.column("ACTIVE");
                    assertThat(active.getBoolean(0)).isTrue();
                    assertThat(active.getBoolean(1)).isFalse();
                }
            }
        }

        @Test
        void splitsIntoMultipleChunks(@TempDir Path tmp) throws Exception {
            // Given
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE TABLE nums (n BIGINT NOT NULL)");
                for (int i = 0; i < 5; i++) {
                    stmt.execute("INSERT INTO nums VALUES (" + i + ")");
                }
            }
            Path vortex = tmp.resolve("nums.vortex");
            JdbcImportOptions options = JdbcImportOptions.defaults()
                                                .withChunkSize(2)
                                                .withWriteOptions(WriteOptions.defaults());

            // When
            JdbcImporter.importQuery(conn, "SELECT * FROM nums ORDER BY n", vortex, options);

            // Then
            try (VortexReader reader = VortexReader.open(vortex)) {
                List<Long> collected = new ArrayList<>();
                try (ScanIterator iter = reader.scan(ScanOptions.all())) {
                    while (iter.hasNext()) {
                        ScanResult chunk = iter.next();
                        LongArray arr = chunk.column("N");
                        for (long r = 0; r < chunk.rowCount(); r++) {
                            collected.add(arr.getLong(r));
                        }
                    }
                }
                assertThat(collected).containsExactly(0L, 1L, 2L, 3L, 4L);
            }
        }

        @Test
        void emptyResultSetProducesEmptyFile(@TempDir Path tmp) throws Exception {
            // Given
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE TABLE empty_table (id BIGINT NOT NULL)");
            }
            Path vortex = tmp.resolve("empty.vortex");

            // When
            JdbcImporter.importQuery(conn, "SELECT * FROM empty_table", vortex);

            // Then
            try (VortexReader reader = VortexReader.open(vortex)) {
                assertThat(reader.dtype()).isInstanceOf(DType.Struct.class);
                try (ScanIterator iter = reader.scan(ScanOptions.all())) {
                    assertThat(iter.hasNext()).isFalse();
                }
            }
        }

        @Test
        void nullValuesMapToZeroOrEmpty(@TempDir Path tmp) throws Exception {
            // Given
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE TABLE nullable_cols (n BIGINT, s VARCHAR(50))");
                stmt.execute("INSERT INTO nullable_cols VALUES (NULL, NULL)");
            }
            Path vortex = tmp.resolve("nullable.vortex");
            JdbcImportOptions options = JdbcImportOptions.defaults().withWriteOptions(WriteOptions.defaults());

            // When
            JdbcImporter.importQuery(conn, "SELECT * FROM nullable_cols", vortex, options);

            // Then
            try (VortexReader reader = VortexReader.open(vortex)) {
                try (ScanIterator iter = reader.scan(ScanOptions.all())) {
                    assertThat(iter.hasNext()).isTrue();
                    ScanResult chunk = iter.next();
                    LongArray nums = chunk.column("N");
                    assertThat(nums.getLong(0)).isEqualTo(0L);
                    VarBinArray strs = chunk.column("S");
                    assertThat(strs.getString(0)).isEqualTo("");
                }
            }
        }

        @Test
        void progressListenerIsInvokedPerChunk(@TempDir Path tmp) throws Exception {
            // Given
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE TABLE progress_test (n BIGINT NOT NULL)");
                for (int i = 0; i < 10; i++) {
                    stmt.execute("INSERT INTO progress_test VALUES (" + i + ")");
                }
            }
            Path vortex = tmp.resolve("progress.vortex");
            List<Long> checkpoints = new ArrayList<>();
            JdbcImportOptions options = JdbcImportOptions.defaults()
                                                .withChunkSize(3)
                                                .withProgressListener((done, total) -> checkpoints.add(done));

            // When
            JdbcImporter.importQuery(conn, "SELECT * FROM progress_test", vortex, options);

            // Then — 3 full chunks of 3 rows each trigger the listener; last partial chunk does not
            assertThat(checkpoints).containsExactly(3L, 6L, 9L);
        }
    }

    @Nested
    class ImportTable {

        @Test
        void selectsAllRowsFromTable(@TempDir Path tmp) throws Exception {
            // Given
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE TABLE products (id BIGINT NOT NULL, label VARCHAR(50) NOT NULL)");
                stmt.execute("INSERT INTO products VALUES (10, 'Widget')");
                stmt.execute("INSERT INTO products VALUES (20, 'Gadget')");
            }
            Path vortex = tmp.resolve("products.vortex");

            // When
            JdbcImporter.importTable(conn, "products", vortex);

            // Then
            try (VortexReader reader = VortexReader.open(vortex)) {
                DType.Struct schema = (DType.Struct) reader.dtype();
                assertThat(schema.fieldNames()).containsExactly("ID", "LABEL");
                try (ScanIterator iter = reader.scan(ScanOptions.all())) {
                    assertThat(iter.hasNext()).isTrue();
                    ScanResult chunk = iter.next();
                    assertThat(chunk.rowCount()).isEqualTo(2);
                }
            }
        }
    }
}
