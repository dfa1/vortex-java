package io.github.dfa1.vortex.calcite;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import java.util.Properties;

/// Profiling harness (disabled in normal runs). Writes a 1M-row OHLC Vortex file once, then runs
/// the full-table `MIN/MAX/COUNT` SQL many times through Calcite so a CPU profile has enough
/// samples to show where the scan path spends its time.
///
/// Run under async-profiler:
/// ```
/// ./mvnw test -pl calcite -am -Dtest=CalciteDemo -Ddemo.profile=true \
///   -DargLine="-agentpath:/opt/homebrew/lib/libasyncProfiler.dylib=start,event=cpu,file=/tmp/calcite.collapsed,collapsed"
/// ```
class CalciteDemo {

    @Test
    @EnabledIfSystemProperty(named = "demo.profile", matches = "true")
    void profileFullScan() throws Exception {
        int rows = Integer.getInteger("demo.rows", 1_000_000);
        int iterations = Integer.getInteger("demo.iterations", 300);

        Path file = Files.createTempFile("ohlc-demo", ".vortex");
        try {
            OhlcGenerator.write(file, rows, 10_000);
            System.out.printf("wrote %,d rows -> %.1f MB%n", rows, Files.size(file) / 1048576.0);

            Properties info = new Properties();
            info.setProperty("lex", "JAVA");
            String sql = "select min(low) lo, max(high) hi, count(*) c from vtx.ohlc";

            try (Connection conn = DriverManager.getConnection("jdbc:calcite:", info)) {
                conn.unwrap(org.apache.calcite.jdbc.CalciteConnection.class).getRootSchema()
                        .add("vtx", new VortexSchema(Map.of("ohlc", file)));

                long t0 = System.nanoTime();
                long count = 0;
                for (int i = 0; i < iterations; i++) {
                    try (Statement st = conn.createStatement();
                         ResultSet rs = st.executeQuery(sql)) {
                        rs.next();
                        count = rs.getLong("c");
                    }
                }
                double ms = (System.nanoTime() - t0) / 1e6 / iterations;
                System.out.printf("full scan x%d: %.2f ms/query | count=%,d%n", iterations, ms, count);
                // A wrong count here means the profile below is timing a broken scan.
                assertThat(count).isEqualTo(rows);
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
