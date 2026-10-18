package io.github.dfa1.vortex.calcite;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.ExtensionId;
import io.github.dfa1.vortex.core.model.TimeDtype;
import io.github.dfa1.vortex.core.model.TimeUnit;
import io.github.dfa1.vortex.core.model.TimestampDtype;
import io.github.dfa1.vortex.writer.VortexWriter;
import io.github.dfa1.vortex.writer.WriteOptions;
import io.github.dfa1.vortex.writer.encode.DateExtensionEncoder;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// `MIN`/`MAX` of a date, time or timestamp column is answered from zone statistics, which hold the
/// raw storage integer: [Temporal] converts it as the scan converts each row. Every query must equal
/// the same aggregate over the column plus a zero interval, an expression the rule leaves to the scan.
class TemporalPushDownTest {

    @TempDir
    static Path tmp;
    private static Path file;

    /// Two chunks; the timestamps are microseconds, one before the epoch with a fractional
    /// millisecond, so flooring (not truncating toward zero) is what makes the answers agree.
    @BeforeAll
    static void writeFile() throws Exception {
        file = tmp.resolve("temporal.vortex");
        DType.Struct schema = DType.structBuilder()
                .field(ColumnName.of("d"), DateExtensionEncoder.INSTANCE.dtype(false))
                .field(ColumnName.of("t"), TimeDtype.of(TimeUnit.Milliseconds, false))
                .field(ColumnName.of("ts"), TimestampDtype.of(TimeUnit.Microseconds, null, false))
                .build();
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var writer = VortexWriter.create(ch, schema, WriteOptions.defaults())) {
            writer.writeChunk(Map.of(
                    ColumnName.of("d"), List.of(LocalDate.of(2024, 2, 29), LocalDate.of(1969, 7, 20)),
                    ColumnName.of("t"), List.of(LocalTime.of(13, 45, 30, 123_000_000), LocalTime.of(0, 0, 1)),
                    ColumnName.of("ts"), List.of(Instant.parse("1969-12-31T23:59:59.250500Z"),
                            Instant.parse("2020-01-01T00:00:00.335Z"))));
            writer.writeChunk(Map.of(
                    ColumnName.of("d"), List.of(LocalDate.of(2001, 9, 9)),
                    ColumnName.of("t"), List.of(LocalTime.of(23, 59, 59, 999_000_000)),
                    ColumnName.of("ts"), List.of(Instant.parse("2038-01-19T03:14:07.999Z"))));
        }
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "min(d), max(d)   | min(d + interval '0' day), max(d + interval '0' day)",
            "min(t), max(t)   | min(t + interval '0' second), max(t + interval '0' second)",
            "min(ts), max(ts) | min(ts + interval '0' second), max(ts + interval '0' second)"})
    void minMax_answeredFromStatsEqualsTheScan(String aggregates, String oracle) throws Exception {
        // Given
        String sql = "select " + aggregates + " from vtx.t";

        try (Connection conn = VortexCalcite.connect("vtx", Map.of("t", file))) {
            // When
            String plan = single(conn, "explain plan for " + sql);
            String result = row(conn, sql);

            // Then
            assertThat(plan).contains("Values").doesNotContain("TableScan");
            assertThat(result).isEqualTo(row(conn, "select " + oracle + " from vtx.t"));
        }
    }

    @Test
    void convert_floorsToTheUnitCalciteUses() {
        // Given
        Temporal timestamp = new Temporal(ExtensionId.WellKnown.VORTEX_TIMESTAMP, TimeUnit.Microseconds);
        Temporal date = new Temporal(ExtensionId.WellKnown.VORTEX_DATE, TimeUnit.Milliseconds);

        // When / Then — before the epoch, a partial millisecond or day floors to the earlier one
        assertThat(timestamp.convert(-1)).isEqualTo(-1L);
        assertThat(timestamp.convert(1_999)).isEqualTo(1L);
        assertThat(date.convert(-1)).isEqualTo(-1);
        assertThat(date.convert(86_399_999)).isEqualTo(0);
    }

    @Test
    void convert_timeOutsideADay_throwsVortexException() {
        // Given — malformed input: a time of day is in [0, 24h)
        Temporal time = new Temporal(ExtensionId.WellKnown.VORTEX_TIME, TimeUnit.Seconds);

        // When / Then
        assertThatThrownBy(() -> time.convert(86_400)).isInstanceOf(VortexException.class);
        assertThatThrownBy(() -> time.convert(-1)).isInstanceOf(VortexException.class);
    }

    private static String single(Connection conn, String sql) throws Exception {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static String row(Connection conn, String sql) throws Exception {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getString(1) + "|" + rs.getString(2);
        }
    }
}
