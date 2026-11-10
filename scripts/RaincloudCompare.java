import dev.vortex.api.DataSource;
import dev.vortex.api.Partition;
import dev.vortex.api.Scan;
import dev.vortex.api.Session;
import dev.vortex.arrow.ArrowAllocation;
import dev.vortex.jni.NativeLoader;
import io.github.dfa1.vortex.parquet.ImportOptions;
import io.github.dfa1.vortex.parquet.ParquetImporter;
import io.github.dfa1.vortex.reader.Chunk;
import io.github.dfa1.vortex.reader.ScanIterator;
import io.github.dfa1.vortex.reader.ScanOptions;
import io.github.dfa1.vortex.reader.VortexReader;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.ipc.ArrowReader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ForkJoinPool;

/// Size and speed oracle for the Raincloud corpus (issue #205): compares, per slug, vortex-java
/// importing the slug's Parquet sibling, with default and compact write options, against Rust
/// writing the same data:
///
/// - `raincloud`: Raincloud's `.vortex`, written by the Python `vortex-data` its release pins
/// - `jni`: that file rewritten by vortex-jni, the Rust version vortex-java mirrors (`vortex-jni.version`)
/// - `rust`, `rust_compact`: Python `vortex-data` at that same Rust version writing the Parquet
///   sibling with `VortexWriteOptions.default()` and `.compact()`, the baseline both comparisons use;
///   vortex-jni has no compact option (vortex-data/vortex#11374). Only with `VORTEX_PYTHON` set to
///   a Python that has it (`pip install pyarrow vortex-data==<vortex-jni.version>`), else `-`
///
/// `RaincloudConformanceIntegrationTest` proves the values match; this reports what they cost.
/// It never fails on a difference: size is information, not a reason to diverge from Rust
/// (AGENTS.md), so it flags and leaves the call to a human.
///
/// It needs vortex-jni and Arrow, so it runs with the `integration` module's test class path:
///
/// ```
/// ./mvnw -q -pl integration -am -DskipTests package dependency:build-classpath \
///     -Dmdep.includeScope=test -Dmdep.outputFile=target/test-classpath.txt
/// VORTEX_PYTHON=<venv>/bin/python java --enable-native-access=ALL-UNNAMED \
///     --add-opens java.base/java.nio=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow \
///     -cp "$(cat integration/target/test-classpath.txt)" scripts/RaincloudCompare.java [slug ...]
/// ```
///
/// With no slugs, compares every slug of the last manifest written by
/// `scripts/hydrate-raincloud-corpus.sh`. Prints one TSV row per slug: sizes in bytes, Java's
/// size against Rust's (`rust` and `rust_compact`), the import
/// time with default and compact write options (as `vortex import` and `vortex import --compact`),
/// vortex-jni's rewrite time, and the best of three full scans of Raincloud's file and of Java's.
/// `jni` is for reference only: vortex-jni writes the batches it scans from Raincloud's file, which
/// vary between runs, and its size with them (by 6% on `openpowerlifting`). `flag` names each Java file over 2% larger than its Rust
/// counterpart on a file over 1 MB: `BIGGER` for the default write, `BIGGER_COMPACT` for compact.
void main(String[] args) throws IOException {
    Path cacheRoot = Path.of(env("RAINCLOUD_HOME", System.getProperty("user.home") + "/.cache/raincloud"));
    Path manifest = Path.of(env("RAINCLOUD_CORPUS_MANIFEST", cacheRoot.resolve("corpus-manifest.tsv").toString()));
    // each hydrate run rewrites the manifest with its own slugs, so a slug hydrated earlier is
    // resolved from its raincloud output directory, not the manifest
    Path outputs = cacheRoot.resolve("outputs/v2");

    List<String> slugs = List.of(args);
    if (slugs.isEmpty()) {
        if (!Files.exists(manifest)) {
            fail("no manifest at " + manifest + "; run scripts/hydrate-raincloud-corpus.sh first");
        }
        slugs = Files.readAllLines(manifest).stream()
                .filter(line -> !line.isBlank())
                .map(line -> line.substring(0, line.indexOf('\t')))
                .toList();
    }
    if (slugs.isEmpty()) {
        fail("no slugs to compare (empty manifest?)");
    }

    String python = env("VORTEX_PYTHON", "");
    if (!python.isEmpty()) {
        String jniVersion = jniVersion();
        String rustVersion = pythonVortexVersion(python);
        if (!rustVersion.equals(jniVersion)) {
            System.err.println("warning: " + python + " has vortex-data " + rustVersion + ", vortex-jni is " + jniVersion);
        }
    }
    NativeLoader.loadJni();
    Session session = Session.create();
    BufferAllocator allocator = ArrowAllocation.rootAllocator();
    Path scratch = Files.createTempDirectory("raincloud-compare");
    try {
        IO.println("slug\tparquet\traincloud\tjni\trust\trust_compact\tjava\tjava_compact\tjava_vs_rust"
                + "\tcompact_vs_rust\timport_ms\tcompact_ms\tjni_ms\traincloud_read_ms\tjava_read_ms\tflag");
        for (String slug : slugs) {
            Path raincloud = outputs.resolve(slug).resolve("vortex").resolve(slug + ".vortex");
            Path parquet = outputs.resolve(slug).resolve("parquet").resolve(slug + ".parquet");
            if (!Files.exists(raincloud) || !Files.exists(parquet)) {
                System.err.println("skip " + slug + ": not hydrated under " + outputs
                        + " (run scripts/hydrate-raincloud-corpus.sh " + slug + ")");
                continue;
            }
            IO.println(compare(slug, parquet, raincloud, scratch, session, allocator, python));
        }
    } finally {
        Files.deleteIfExists(scratch);
    }
}

String compare(String slug, Path parquet, Path raincloud, Path scratch, Session session, BufferAllocator allocator,
               String python) throws IOException {
    Path java = scratch.resolve(slug + ".vortex");
    Path compact = scratch.resolve(slug + ".compact.vortex");
    Path jni = scratch.resolve(slug + ".jni.vortex");
    Path rust = scratch.resolve(slug + ".rust.vortex");
    Path rustCompact = scratch.resolve(slug + ".rust.compact.vortex");
    try {
        Duration importTime = importParquet(parquet, java, false);
        Duration compactTime = importParquet(parquet, compact, true);
        Duration jniTime = jniRewrite(raincloud, jni, session, allocator);
        boolean withRust = !python.isEmpty() && pythonWrite(python, parquet, rust, rustCompact);
        Duration raincloudRead = bestScan(raincloud);
        Duration javaRead = bestScan(java);
        long jniBytes = Files.size(jni);
        long javaBytes = Files.size(java);
        long compactBytes = Files.size(compact);
        // vortex-jni's rewrite is no baseline: its scan's batches vary between runs, and so its size
        long rustBytes = withRust ? Files.size(rust) : -1;
        long rustCompactBytes = withRust ? Files.size(rustCompact) : -1;
        double vsRust = withRust ? (double) javaBytes / rustBytes - 1 : Double.NaN;
        double compactVsRust = withRust ? (double) compactBytes / rustCompactBytes - 1 : Double.NaN;
        List<String> flags = new ArrayList<>();
        if (vsRust > 0.02 && rustBytes > 1 << 20) {
            flags.add("BIGGER");
        }
        if (compactVsRust > 0.02 && rustCompactBytes > 1 << 20) {
            flags.add("BIGGER_COMPACT");
        }
        return String.join("\t", slug, Long.toString(Files.size(parquet)), Long.toString(Files.size(raincloud)),
                Long.toString(jniBytes), withRust ? Long.toString(rustBytes) : "-",
                withRust ? Long.toString(rustCompactBytes) : "-", Long.toString(javaBytes), Long.toString(compactBytes),
                withRust ? percent(vsRust) : "-", withRust ? percent(compactVsRust) : "-", Long.toString(importTime.toMillis()),
                Long.toString(compactTime.toMillis()), Long.toString(jniTime.toMillis()),
                Long.toString(raincloudRead.toMillis()), Long.toString(javaRead.toMillis()), String.join(",", flags));
    } finally {
        Files.deleteIfExists(java);
        Files.deleteIfExists(compact);
        Files.deleteIfExists(jni);
        Files.deleteIfExists(rust);
        Files.deleteIfExists(rustCompact);
    }
}

String percent(double ratio) {
    return String.format(Locale.ROOT, "%+.1f%%", ratio * 100);
}

/// Imports as `vortex import [--compact]` does: default options on the common pool.
Duration importParquet(Path parquet, Path vortex, boolean compact) throws IOException {
    ImportOptions defaults = ImportOptions.defaults();
    ImportOptions options = defaults.withWriteOptions(
            defaults.writeOptions().withExecutor(ForkJoinPool.commonPool()).withCompact(compact));
    long start = System.nanoTime();
    ParquetImporter.importParquet(parquet, vortex, options);
    return Duration.ofNanos(System.nanoTime() - start);
}

/// Rewrites `source` with vortex-jni's default writer: every batch its scan yields, as Arrow.
Duration jniRewrite(Path source, Path target, Session session, BufferAllocator allocator) throws IOException {
    long start = System.nanoTime();
    DataSource ds = DataSource.open(session, source.toAbsolutePath().toUri().toString());
    try (dev.vortex.api.VortexWriter writer = dev.vortex.api.VortexWriter.builder(session,
            target.toAbsolutePath().toUri().toString(), ds.arrowSchema(allocator), allocator).build()) {
        Scan scan = ds.scan(dev.vortex.api.ScanOptions.builder().build());
        while (scan.hasNext()) {
            Partition partition = scan.next();
            try (ArrowReader reader = partition.scanArrow(allocator)) {
                while (reader.loadNextBatch()) {
                    writer.writeBatch(reader.getVectorSchemaRoot(), allocator);
                }
            }
        }
    }
    return Duration.ofNanos(System.nanoTime() - start);
}

/// Best of three full scans, every column decoded; the first run also warms the JIT up.
Duration bestScan(Path vortex) throws IOException {
    Duration best = null;
    for (int run = 0; run < 3; run++) {
        long start = System.nanoTime();
        long rows = 0;
        try (VortexReader reader = VortexReader.open(vortex);
             ScanIterator chunks = reader.scan(ScanOptions.all())) {
            while (chunks.hasNext()) {
                try (Chunk chunk = chunks.next()) {
                    // a chunk decodes a column the first time it is read: columns() reads them all
                    rows += chunk.rowCount() * chunk.columns().size();
                }
            }
        }
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
        if (rows < 0 || best == null || elapsed.compareTo(best) < 0) {
            best = elapsed;
        }
    }
    return best;
}

/// Writes `parquet` with Python `vortex-data`, default and compact options; `false` when it fails.
boolean pythonWrite(String python, Path parquet, Path target, Path compactTarget) throws IOException {
    String program = """
            import sys, pyarrow.parquet as pq, vortex.io as io
            table = pq.read_table(sys.argv[1])
            io.VortexWriteOptions.default().write(table, sys.argv[2])
            io.VortexWriteOptions.compact().write(table, sys.argv[3])
            """;
    Process process = new ProcessBuilder(python, "-I", "-c", program, parquet.toString(), target.toString(),
            compactTarget.toString()).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
    String error = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
    try {
        if (process.waitFor() != 0) {
            System.err.println("warning: " + python + " could not write " + parquet + ": " + error.strip());
            return false;
        }
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IOException(e);
    }
    return true;
}

/// The `vortex-data` version of `python`, or `?` when it has none.
String pythonVortexVersion(String python) throws IOException {
    Process process = new ProcessBuilder(python, "-I", "-c",
            "import importlib.metadata as m; print(m.version('vortex-data'))").start();
    String version = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
    if (version.isEmpty()) {
        return "?";
    }
    return version;
}

/// The vortex-jni version on the class path, read off its jar's file name.
String jniVersion() {
    for (String entry : System.getProperty("java.class.path").split(java.io.File.pathSeparator)) {
        String name = Path.of(entry).getFileName().toString();
        if (name.startsWith("vortex-jni-") && name.endsWith(".jar")) {
            return name.substring("vortex-jni-".length(), name.length() - ".jar".length());
        }
    }
    return "?";
}

String env(String name, String fallback) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) {
        return fallback;
    }
    return value;
}

void fail(String message) {
    System.err.println("error: " + message);
    System.exit(2);
}
