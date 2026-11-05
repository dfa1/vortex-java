package io.github.dfa1.vortex.calcite;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// SQL over `fixtures/primitives.vortex`, the file of #665: every primitive column holds its
/// minimum, zero-ish and maximum, so the U64 column reaches 2^64 - 1.
class PrimitivesFixtureTest {

    private static final BigDecimal U64_HALF = new BigDecimal("9223372036854775808");
    private static final BigDecimal U64_MAX = new BigDecimal("18446744073709551615");

    @Test
    void select_readsEveryPrimitiveColumnAtItsBoundaries() throws Exception {
        // Given
        Path file = fixture();

        // When
        try (Connection conn = VortexCalcite.connect("vtx", Map.of("t", file));
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("select u8, u16, u32, u64, i32, i64 from vtx.t")) {
            // Then
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt("u8")).isZero();
            assertThat(rs.getBigDecimal("u64")).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(rs.getInt("i32")).isEqualTo(Integer.MIN_VALUE);
            assertThat(rs.getLong("i64")).isEqualTo(Long.MIN_VALUE);

            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt("u8")).isEqualTo(128);
            assertThat(rs.getInt("u16")).isEqualTo(32_768);
            assertThat(rs.getLong("u32")).isEqualTo(2_147_483_648L);
            assertThat(rs.getBigDecimal("u64")).isEqualByComparingTo(U64_HALF);

            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt("u8")).isEqualTo(255);
            assertThat(rs.getInt("u16")).isEqualTo(65_535);
            assertThat(rs.getLong("u32")).isEqualTo(4_294_967_295L);
            assertThat(rs.getBigDecimal("u64")).isEqualByComparingTo(U64_MAX);
            assertThat(rs.getInt("i32")).isEqualTo(Integer.MAX_VALUE);
            assertThat(rs.getLong("i64")).isEqualTo(Long.MAX_VALUE);

            assertThat(rs.next()).isFalse();
        }
    }

    @Test
    void aggregate_overU64_isExactPastTheSignedRange() throws Exception {
        // Given
        Path file = fixture();

        // When
        try (Connection conn = VortexCalcite.connect("vtx", Map.of("t", file));
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("select min(u64), max(u64), sum(u64) from vtx.t")) {
            // Then
            assertThat(rs.next()).isTrue();
            assertThat(rs.getBigDecimal(1)).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(rs.getBigDecimal(2)).isEqualByComparingTo(U64_MAX);
            assertThat(rs.getBigDecimal(3)).isEqualByComparingTo(U64_HALF.add(U64_MAX));
        }
    }

    private Path fixture() throws Exception {
        return Path.of(getClass().getResource("/fixtures/primitives.vortex").toURI());
    }
}
