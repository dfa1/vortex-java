package io.github.dfa1.vortex.calcite;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/// De-risk gate: proves Apache Calcite's Enumerable convention (runtime Java codegen
/// compiled by Janino) works on this project's JDK 25 target before any adapter is built.
///
/// The `VALUES ... WHERE` query forces Calcite to generate and Janino-compile an
/// Enumerable program. If Janino cannot emit JDK 25 class files this test fails at
/// execution time, which is the signal to fall back to Calcite's interpreter convention.
class CalciteSmokeTest {

    @Test
    void calciteEnumerableCodegenRunsOnJdk25() throws Exception {
        // Given a Calcite JDBC connection (lex/parser defaults are enough for a literal query)
        Properties info = new Properties();
        info.setProperty("lex", "JAVA");

        // When a query that forces Enumerable codegen + Janino compilation is executed
        int sum = 0;
        try (Connection conn = DriverManager.getConnection("jdbc:calcite:", info);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "select id from (values (1), (2), (3)) as t(id) where id > 1")) {
            while (rs.next()) {
                sum += rs.getInt("id");
            }
        }

        // Then the rows survive the generated+compiled pipeline (2 + 3)
        assertThat(sum).isEqualTo(5);
    }
}
