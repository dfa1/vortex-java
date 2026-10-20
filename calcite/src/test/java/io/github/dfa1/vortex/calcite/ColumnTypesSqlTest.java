package io.github.dfa1.vortex.calcite;

import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.TimeDtype;
import io.github.dfa1.vortex.core.model.TimeUnit;
import io.github.dfa1.vortex.core.model.TimestampDtype;
import io.github.dfa1.vortex.writer.VortexWriter;
import io.github.dfa1.vortex.writer.WriteOptions;
import io.github.dfa1.vortex.writer.encode.DateExtensionEncoder;
import io.github.dfa1.vortex.writer.encode.ListData;
import io.github.dfa1.vortex.writer.encode.UuidExtensionEncoder;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Column types beyond numbers, strings and booleans. Before, `getRowType` threw on the first
/// decimal, binary, temporal, uuid or nested column, so a file with a single timestamp could not be
/// queried at all, not even its other columns.
class ColumnTypesSqlTest {

    private static final LocalDate DATE = LocalDate.of(2024, 2, 29);
    private static final LocalTime TIME = LocalTime.of(13, 45, 30, 123_000_000);
    // pre-epoch on purpose: millisecond truncation must floor, not round toward zero
    private static final Instant INSTANT = Instant.parse("1969-12-31T23:59:59.250Z");
    private static final UUID ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");

    @TempDir
    static Path tmp;
    private static Path file;

    @BeforeAll
    static void write() throws Exception {
        file = tmp.resolve("types.vortex");
        DType.Struct schema = DType.structBuilder()
                .field(ColumnName.of("id"), DType.I32)
                .field(ColumnName.of("amount"), DType.decimal(10, 2))
                .field(ColumnName.of("blob"), DType.BINARY)
                .field(ColumnName.of("day"), DateExtensionEncoder.INSTANCE.dtype(false))
                .field(ColumnName.of("at"), TimeDtype.of(TimeUnit.Milliseconds, false))
                .field(ColumnName.of("wall"), TimestampDtype.of(TimeUnit.Microseconds, null, false))
                .field(ColumnName.of("instant"), TimestampDtype.of(TimeUnit.Microseconds, ZoneId.of("UTC"), false))
                .field(ColumnName.of("uuid"), UuidExtensionEncoder.INSTANCE.dtype(false))
                .field(ColumnName.of("tags"), new DType.List(DType.I64, false))
                .build();
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             VortexWriter writer = VortexWriter.create(ch, schema, WriteOptions.defaults())) {
            writer.writeChunk(Map.of(
                    ColumnName.of("id"), new int[]{1},
                    ColumnName.of("amount"), new BigDecimal[]{new BigDecimal("12.34")},
                    ColumnName.of("blob"), new byte[][]{{1, 2, 3}},
                    ColumnName.of("day"), List.of(DATE),
                    ColumnName.of("at"), List.of(TIME),
                    ColumnName.of("wall"), List.of(INSTANT),
                    ColumnName.of("instant"), List.of(INSTANT),
                    ColumnName.of("uuid"), List.of(ID),
                    ColumnName.of("tags"), new ListData(new long[]{7, 8}, new long[]{0, 2}, 1)));
        }
    }

    @Test
    void everyScalarType_readsThroughSql() throws Exception {
        // Given
        String sql = "select amount, blob, \"day\", at, wall, \"instant\", \"uuid\" from vtx.t";

        // When
        try (Connection conn = VortexCalcite.connect("vtx", Map.of("t", file));
             Statement st = conn.createStatement();
             ResultSet result = st.executeQuery(sql)) {

            // Then
            assertThat(result.next()).isTrue();
            assertThat(result.getBigDecimal("amount")).isEqualByComparingTo("12.34");
            assertThat(result.getBytes("blob")).containsExactly(1, 2, 3);
            assertThat(result.getDate("day").toLocalDate()).isEqualTo(DATE);
            assertThat(result.getTime("at").toLocalTime()).isEqualTo(TIME.withNano(0));
            // TIME is declared with millisecond precision, the precision values carry
            assertThat(result.getString("at")).isEqualTo("13:45:30.123");
            // TIMESTAMP is a wall clock: the stored value read as UTC, with no zone shift
            assertThat(result.getTimestamp("wall").toLocalDateTime())
                    .isEqualTo(LocalDateTime.ofInstant(INSTANT, ZoneOffset.UTC));
            // a zoned timestamp too: the instant as a UTC wall clock, the same for every JVM zone
            assertThat(result.getTimestamp("instant").toLocalDateTime())
                    .isEqualTo(LocalDateTime.ofInstant(INSTANT, ZoneOffset.UTC));
            assertThat(result.getObject("uuid")).isEqualTo(ID);
            assertThat(result.next()).isFalse();
        }
    }

    @Test
    void nestedColumn_doesNotBlockTheOtherColumns() throws Exception {
        // Given a table with a list column the adapter cannot read yet

        // When
        try (Connection conn = VortexCalcite.connect("vtx", Map.of("t", file));
             Statement st = conn.createStatement();
             ResultSet result = st.executeQuery("select id from vtx.t")) {

            // Then
            assertThat(result.next()).isTrue();
            assertThat(result.getInt("id")).isEqualTo(1);
        }
    }

    @Test
    void nestedColumn_failsLoudWhenSelected() throws Exception {
        // Given
        try (Connection conn = VortexCalcite.connect("vtx", Map.of("t", file));
             Statement st = conn.createStatement()) {

            // When / Then — Calcite fetches the first row while executing, so the query itself fails
            assertThatThrownBy(() -> st.executeQuery("select tags from vtx.t"))
                    .isInstanceOf(SQLException.class).hasStackTraceContaining("cannot read a List");
        }
    }
}
