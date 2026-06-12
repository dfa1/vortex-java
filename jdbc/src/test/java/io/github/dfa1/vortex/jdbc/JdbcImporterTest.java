package io.github.dfa1.vortex.jdbc;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.array.BoolArray;
import io.github.dfa1.vortex.core.array.DoubleArray;
import io.github.dfa1.vortex.core.array.LongArray;
import io.github.dfa1.vortex.core.array.VarBinArray;
import io.github.dfa1.vortex.reader.VortexReader;
import io.github.dfa1.vortex.reader.Chunk;
import io.github.dfa1.vortex.reader.ScanIterator;
import io.github.dfa1.vortex.reader.ScanOptions;
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
import java.util.UUID;

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
                    try (Chunk chunk = iter.next()) {
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
        }

        @Test
        void roundTripsTemporalSqlTypesViaExtensions(@TempDir Path tmp) throws Exception {
            // Given — DATE, TIME, TIMESTAMP columns map to vortex.date / .time / .timestamp
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE TABLE temporal ("
                        + "id BIGINT NOT NULL, "
                        + "d DATE NOT NULL, "
                        + "t TIME NOT NULL, "
                        + "ts TIMESTAMP NOT NULL)");
                stmt.execute("INSERT INTO temporal VALUES "
                        + "(1, DATE '1996-02-12', TIME '01:01:01', TIMESTAMP '2026-06-10 12:00:00')");
                stmt.execute("INSERT INTO temporal VALUES "
                        + "(2, DATE '2026-06-10', TIME '00:00:00', TIMESTAMP '1970-01-01 00:00:00')");
            }
            Path vortex = tmp.resolve("temporal.vortex");

            // When
            JdbcImporter.importQuery(conn, "SELECT * FROM temporal ORDER BY id", vortex);

            // Then — schema declares the three extension dtypes
            try (VortexReader reader = VortexReader.open(vortex,
                    io.github.dfa1.vortex.reader.ReadRegistry.loadAll())) {
                DType.Struct schema = (DType.Struct) reader.dtype();
                assertThat(schema.fieldTypes().get(1))
                        .isEqualTo(io.github.dfa1.vortex.writer.encode.DateExtensionEncoder.INSTANCE.dtype(false));
                assertThat(schema.fieldTypes().get(2))
                        .isEqualTo(io.github.dfa1.vortex.writer.encode.TimeExtensionEncoder.INSTANCE.dtype(false));
                assertThat(schema.fieldTypes().get(3))
                        .isEqualTo(io.github.dfa1.vortex.writer.encode.TimestampExtensionEncoder.INSTANCE.dtype(false));

                // And — decoded values round-trip through the matching extension impl
                try (ScanIterator iter = reader.scan(ScanOptions.all())) {
                    assertThat(iter.hasNext()).isTrue();
                    try (Chunk chunk = iter.next()) {
                        assertThat(chunk.rowCount()).isEqualTo(2);

                        assertThat(io.github.dfa1.vortex.reader.extension.DateExtensionDecoder.INSTANCE
                                .decodeAll(chunk.column("D")))
                                .containsExactly(
                                        java.time.LocalDate.of(1996, 2, 12),
                                        java.time.LocalDate.of(2026, 6, 10));

                        DType.Extension tsDtype = (DType.Extension) schema.fieldTypes().get(3);
                        assertThat(io.github.dfa1.vortex.reader.extension.TimestampExtensionDecoder.INSTANCE
                                .decodeAll(tsDtype, chunk.column("TS")))
                                .containsExactly(
                                        java.sql.Timestamp.valueOf("2026-06-10 12:00:00").toInstant(),
                                        java.sql.Timestamp.valueOf("1970-01-01 00:00:00").toInstant());
                    }
                }
            }
        }

        @Test
        void roundTripsNullableExtensionColumns(@TempDir Path tmp) throws Exception {
            // Given — nullable DATE/TIME/TIMESTAMP/UUID columns with mixed NULL rows.
            // Validates the writer's ExtEncoding -> MaskedEncoding -> primitive layout
            // and that JdbcImporter preserves SQL NULL through to MaskedArray validity bits.
            UUID u1 = UUID.fromString("12345678-1234-5678-9abc-def012345678");
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE TABLE nullable_ext ("
                        + "id BIGINT NOT NULL, "
                        + "d DATE, "
                        + "t TIME, "
                        + "ts TIMESTAMP, "
                        + "u UUID)");
                stmt.execute("INSERT INTO nullable_ext VALUES "
                        + "(1, DATE '1996-02-12', TIME '01:01:01', TIMESTAMP '2026-06-10 12:00:00', '" + u1 + "')");
                stmt.execute("INSERT INTO nullable_ext VALUES (2, NULL, NULL, NULL, NULL)");
                stmt.execute("INSERT INTO nullable_ext VALUES "
                        + "(3, DATE '2026-06-10', TIME '00:00:00', TIMESTAMP '1970-01-01 00:00:00', '" + u1 + "')");
            }
            Path vortex = tmp.resolve("nullable_ext.vortex");

            // When
            JdbcImporter.importQuery(conn, "SELECT * FROM nullable_ext ORDER BY id", vortex);

            // Then — schema declares nullable=true on every ext column; data round-trips
            // with row 2 marked invalid in each MaskedArray
            try (VortexReader reader = VortexReader.open(vortex,
                    io.github.dfa1.vortex.reader.ReadRegistry.loadAll())) {
                DType.Struct schema = (DType.Struct) reader.dtype();
                assertThat(((DType.Extension) schema.fieldTypes().get(1)).nullable()).isTrue();
                assertThat(((DType.Extension) schema.fieldTypes().get(2)).nullable()).isTrue();
                assertThat(((DType.Extension) schema.fieldTypes().get(3)).nullable()).isTrue();
                assertThat(((DType.Extension) schema.fieldTypes().get(4)).nullable()).isTrue();

                try (ScanIterator iter = reader.scan(ScanOptions.all())) {
                    assertThat(iter.hasNext()).isTrue();
                    try (Chunk chunk = iter.next()) {
                        assertThat(chunk.rowCount()).isEqualTo(3);

                        io.github.dfa1.vortex.core.array.Array dCol = chunk.column("D");
                        io.github.dfa1.vortex.core.array.Array tCol = chunk.column("T");
                        io.github.dfa1.vortex.core.array.Array tsCol = chunk.column("TS");
                        io.github.dfa1.vortex.core.array.Array uCol = chunk.column("U");
                        assertThat(dCol).isInstanceOf(io.github.dfa1.vortex.core.array.MaskedArray.class);
                        assertThat(tCol).isInstanceOf(io.github.dfa1.vortex.core.array.MaskedArray.class);
                        assertThat(tsCol).isInstanceOf(io.github.dfa1.vortex.core.array.MaskedArray.class);
                        assertThat(uCol).isInstanceOf(io.github.dfa1.vortex.core.array.MaskedArray.class);

                        io.github.dfa1.vortex.core.array.MaskedArray dMasked =
                                (io.github.dfa1.vortex.core.array.MaskedArray) dCol;
                        assertThat(dMasked.isValid(0)).isTrue();
                        assertThat(dMasked.isValid(1)).isFalse();
                        assertThat(dMasked.isValid(2)).isTrue();
                        assertThat(io.github.dfa1.vortex.reader.extension.DateExtensionDecoder.INSTANCE.decode(dMasked, 0))
                                .isEqualTo(java.time.LocalDate.of(1996, 2, 12));
                        assertThat(io.github.dfa1.vortex.reader.extension.DateExtensionDecoder.INSTANCE.decode(dMasked, 2))
                                .isEqualTo(java.time.LocalDate.of(2026, 6, 10));

                        DType.Extension tDtype = (DType.Extension) schema.fieldTypes().get(2);
                        io.github.dfa1.vortex.core.array.MaskedArray tMasked =
                                (io.github.dfa1.vortex.core.array.MaskedArray) tCol;
                        assertThat(tMasked.isValid(1)).isFalse();
                        assertThat(io.github.dfa1.vortex.reader.extension.TimeExtensionDecoder.INSTANCE.decode(tDtype, tMasked, 0))
                                .isEqualTo(java.time.LocalTime.of(1, 1, 1));

                        DType.Extension tsDtype = (DType.Extension) schema.fieldTypes().get(3);
                        io.github.dfa1.vortex.core.array.MaskedArray tsMasked =
                                (io.github.dfa1.vortex.core.array.MaskedArray) tsCol;
                        assertThat(tsMasked.isValid(1)).isFalse();
                        assertThat(io.github.dfa1.vortex.reader.extension.TimestampExtensionDecoder.INSTANCE.instant(tsDtype, tsMasked, 0))
                                .isEqualTo(java.sql.Timestamp.valueOf("2026-06-10 12:00:00").toInstant());

                        io.github.dfa1.vortex.core.array.MaskedArray uMasked =
                                (io.github.dfa1.vortex.core.array.MaskedArray) uCol;
                        assertThat(uMasked.isValid(0)).isTrue();
                        assertThat(uMasked.isValid(1)).isFalse();
                        assertThat(uMasked.isValid(2)).isTrue();
                        assertThat(io.github.dfa1.vortex.reader.extension.UuidExtensionDecoder.INSTANCE.decode(uMasked, 0))
                                .isEqualTo(u1);

                        // And — decodeAll preserves nulls in the returned list at row 1
                        assertThat(io.github.dfa1.vortex.reader.extension.DateExtensionDecoder.INSTANCE.decodeAll(dMasked))
                                .containsExactly(
                                        java.time.LocalDate.of(1996, 2, 12),
                                        null,
                                        java.time.LocalDate.of(2026, 6, 10));
                        assertThat(io.github.dfa1.vortex.reader.extension.UuidExtensionDecoder.INSTANCE.decodeAll(uMasked))
                                .containsExactly(u1, null, u1);
                    }
                }
            }
        }

        @Test
        void roundTripsUuidColumnViaExtension(@TempDir Path tmp) throws Exception {
            // Given — H2 UUID column type. Driver returns java.util.UUID from rs.getObject.
            UUID u1 = UUID.fromString("12345678-1234-5678-9abc-def012345678");
            UUID u2 = UUID.fromString("00000000-0000-0000-0000-000000000001");
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE TABLE uuids (id BIGINT NOT NULL, u UUID NOT NULL)");
                stmt.execute("INSERT INTO uuids VALUES (1, '" + u1 + "')");
                stmt.execute("INSERT INTO uuids VALUES (2, '" + u2 + "')");
            }
            Path vortex = tmp.resolve("uuids.vortex");

            // When
            JdbcImporter.importQuery(conn, "SELECT * FROM uuids ORDER BY id", vortex);

            // Then — column maps to vortex.uuid extension; values round-trip exactly
            try (VortexReader reader = VortexReader.open(vortex,
                    io.github.dfa1.vortex.reader.ReadRegistry.loadAll())) {
                DType.Struct schema = (DType.Struct) reader.dtype();
                assertThat(schema.fieldTypes().get(1))
                        .isEqualTo(io.github.dfa1.vortex.writer.encode.UuidExtensionEncoder.INSTANCE.dtype(false));

                try (ScanIterator iter = reader.scan(ScanOptions.all())) {
                    assertThat(iter.hasNext()).isTrue();
                    try (Chunk chunk = iter.next()) {
                        assertThat(chunk.rowCount()).isEqualTo(2);
                        assertThat(io.github.dfa1.vortex.reader.extension.UuidExtensionDecoder.INSTANCE
                                .decodeAll(chunk.column("U")))
                                .containsExactly(u1, u2);
                    }
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
                    iter.forEachRemaining(chunk -> {
                        LongArray arr = chunk.column("N");
                        for (long r = 0; r < chunk.rowCount(); r++) {
                            collected.add(arr.getLong(r));
                        }
                    });
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
        void nullValuesPreserveValidityViaMaskedArray(@TempDir Path tmp) throws Exception {
            // Given — nullable BIGINT and VARCHAR columns with mixed NULL rows. The writer
            // must emit vortex.masked wrapping the storage so isValid() distinguishes a real
            // 0 / "" value from a SQL NULL.
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE TABLE nullable_cols (n BIGINT, s VARCHAR(50))");
                stmt.execute("INSERT INTO nullable_cols VALUES (NULL, NULL)");
                stmt.execute("INSERT INTO nullable_cols VALUES (0, '')");
                stmt.execute("INSERT INTO nullable_cols VALUES (42, 'hi')");
            }
            Path vortex = tmp.resolve("nullable.vortex");
            JdbcImportOptions options = JdbcImportOptions.defaults().withWriteOptions(WriteOptions.defaults());

            // When
            JdbcImporter.importQuery(conn, "SELECT * FROM nullable_cols ORDER BY n NULLS FIRST", vortex, options);

            // Then — both columns decode as MaskedArray; row 0 is null, rows 1 and 2 are valid
            try (VortexReader reader = VortexReader.open(vortex)) {
                try (ScanIterator iter = reader.scan(ScanOptions.all())) {
                    assertThat(iter.hasNext()).isTrue();
                    try (Chunk chunk = iter.next()) {
                        io.github.dfa1.vortex.core.array.Array nCol = chunk.column("N");
                        io.github.dfa1.vortex.core.array.Array sCol = chunk.column("S");
                        assertThat(nCol).isInstanceOf(io.github.dfa1.vortex.core.array.MaskedArray.class);
                        assertThat(sCol).isInstanceOf(io.github.dfa1.vortex.core.array.MaskedArray.class);

                        io.github.dfa1.vortex.core.array.MaskedArray nMasked =
                                (io.github.dfa1.vortex.core.array.MaskedArray) nCol;
                        assertThat(nMasked.isValid(0)).isFalse();
                        assertThat(nMasked.isValid(1)).isTrue();
                        assertThat(nMasked.isValid(2)).isTrue();
                        LongArray nInner = (LongArray) nMasked.inner();
                        assertThat(nInner.getLong(1)).isEqualTo(0L);
                        assertThat(nInner.getLong(2)).isEqualTo(42L);

                        io.github.dfa1.vortex.core.array.MaskedArray sMasked =
                                (io.github.dfa1.vortex.core.array.MaskedArray) sCol;
                        assertThat(sMasked.isValid(0)).isFalse();
                        assertThat(sMasked.isValid(1)).isTrue();
                        assertThat(sMasked.isValid(2)).isTrue();
                        VarBinArray sInner = (VarBinArray) sMasked.inner();
                        assertThat(sInner.getString(1)).isEmpty();
                        assertThat(sInner.getString(2)).isEqualTo("hi");
                    }
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
                    try (Chunk chunk = iter.next()) {
                        assertThat(chunk.rowCount()).isEqualTo(2);
                    }
                }
            }
        }
    }
}
