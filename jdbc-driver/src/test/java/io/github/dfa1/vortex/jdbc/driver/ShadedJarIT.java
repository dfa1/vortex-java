package io.github.dfa1.vortex.jdbc.driver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Properties;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// Exercises the shaded `-all` jar the way an IDE does: alone, with none of this module's own
/// classpath. A plain unit test cannot catch what shading loses (a clobbered `java.sql.Driver`
/// service file, leftover signature files that make the JVM reject the jar), so this runs after
/// `package`, under failsafe. The jar is loaded through a classloader whose parent is the JDK
/// platform loader, so no test dependency (Calcite, the Vortex modules) can leak in.
class ShadedJarIT {

    @TempDir
    Path tmp;

    @Test
    void shadedJar_mergesDriverEntriesAndCarriesNoSignatures() throws Exception {
        // Given
        Path jar = shadedJar();

        // When
        String services;
        long signatures;
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            services = new String(jarFile.getInputStream(jarFile.getEntry("META-INF/services/java.sql.Driver"))
                    .readAllBytes(), StandardCharsets.UTF_8);
            signatures = jarFile.stream().filter(e -> e.getName().matches("META-INF/.*\\.(SF|RSA|DSA)")).count();
        }

        // Then ServicesResourceTransformer kept ours and Calcite's; a stale signature would make the JVM
        // reject the whole jar with a SecurityException
        assertThat(services).contains(VortexDriver.class.getName(), "org.apache.calcite.jdbc.Driver");
        assertThat(signatures).isZero();
    }

    @Test
    void shadedJar_runsSqlWithNothingElseOnTheClasspath() throws Exception {
        // Given a Vortex file and only the shaded jar on an isolated classloader
        Path dir = Files.createDirectory(tmp.resolve("data"));
        Fixtures.write(dir.resolve("ohlc.vortex"), 500);
        URL[] urls = {shadedJar().toUri().toURL()};
        try (URLClassLoader isolated = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader())) {
            Driver driver = (Driver) isolated.loadClass(VortexDriver.class.getName())
                    .getDeclaredConstructor().newInstance();

            // When a query runs through it
            long result;
            try (Connection conn = driver.connect("jdbc:vortex:" + dir, new Properties());
                 Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("select count(*) from vtx.ohlc")) {
                rs.next();
                result = rs.getLong(1);
            }

            // Then every dependency the query needed came from the jar
            assertThat(result).isEqualTo(500);
        }
    }

    private static Path shadedJar() throws IOException {
        try (Stream<Path> files = Files.list(Path.of("target"))) {
            return files.filter(p -> p.getFileName().toString().endsWith("-all.jar")).findFirst()
                    .orElseThrow(() -> new AssertionError("no -all.jar in target/: run `verify`, not `test`"));
        }
    }
}
