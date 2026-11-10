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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ForkJoinPool;

/// Size and speed oracle for the Raincloud corpus (issue #205): compares, per slug, vortex-java
/// re-importing the slug's Parquet sibling against two Rust writers of the same data:
///
/// - `rust`: Raincloud's `.vortex`, written by the Python `vortex-data` its release pins
/// - `jni`: that file rewritten by vortex-jni, the Rust version vortex-java mirrors (`vortex-jni.version`)
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
/// java --enable-native-access=ALL-UNNAMED --add-opens java.base/java.nio=ALL-UNNAMED \
///     --sun-misc-unsafe-memory-access=allow -cp "$(cat integration/target/test-classpath.txt)" \
///     scripts/RaincloudCompare.java [slug ...]
/// ```
///
/// With no slugs, compares every slug of the last manifest written by
/// `scripts/hydrate-raincloud-corpus.sh`. Prints one TSV row per slug: sizes in bytes, the
/// import time with default and compact write options (as `vortex import` and `vortex import
/// --compact`), vortex-jni's rewrite time, and the best of three full scans of the Rust file and
/// of Java's. vortex-jni writes the batches it scans from the Rust file, not the Parquet row
/// groups Java imports, so its chunk boundaries can differ slightly. `flag` is `BIGGER` when
/// Java's file is over 2% larger than vortex-jni's on a file over 1 MB.
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

    NativeLoader.loadJni();
    Session session = Session.create();
    BufferAllocator allocator = ArrowAllocation.rootAllocator();
    Path scratch = Files.createTempDirectory("raincloud-compare");
    try {
        IO.println("slug\tparquet\trust\tjni\tjava\tjava_compact\tjava_vs_jni\tjava_vs_rust"
                + "\timport_ms\tcompact_ms\tjni_ms\trust_read_ms\tjava_read_ms\tflag");
        for (String slug : slugs) {
            Path rust = outputs.resolve(slug).resolve("vortex").resolve(slug + ".vortex");
            Path parquet = outputs.resolve(slug).resolve("parquet").resolve(slug + ".parquet");
            if (!Files.exists(rust) || !Files.exists(parquet)) {
                System.err.println("skip " + slug + ": not hydrated under " + outputs
                        + " (run scripts/hydrate-raincloud-corpus.sh " + slug + ")");
                continue;
            }
            IO.println(compare(slug, parquet, rust, scratch, session, allocator));
        }
    } finally {
        Files.deleteIfExists(scratch);
    }
}

String compare(String slug, Path parquet, Path rust, Path scratch, Session session, BufferAllocator allocator)
        throws IOException {
    Path java = scratch.resolve(slug + ".vortex");
    Path compact = scratch.resolve(slug + ".compact.vortex");
    Path jni = scratch.resolve(slug + ".jni.vortex");
    try {
        Duration importTime = importParquet(parquet, java, false);
        Duration compactTime = importParquet(parquet, compact, true);
        Duration jniTime = jniRewrite(rust, jni, session, allocator);
        Duration rustRead = bestScan(rust);
        Duration javaRead = bestScan(java);
        long rustBytes = Files.size(rust);
        long jniBytes = Files.size(jni);
        long javaBytes = Files.size(java);
        double vsJni = (double) javaBytes / jniBytes - 1;
        double vsRust = (double) javaBytes / rustBytes - 1;
        String flag = "";
        if (vsJni > 0.02 && jniBytes > 1 << 20) {
            flag = "BIGGER";
        }
        return String.format(Locale.ROOT, "%s\t%d\t%d\t%d\t%d\t%d\t%+.1f%%\t%+.1f%%\t%d\t%d\t%d\t%d\t%d\t%s",
                slug, Files.size(parquet), rustBytes, jniBytes, javaBytes, Files.size(compact), vsJni * 100,
                vsRust * 100, importTime.toMillis(), compactTime.toMillis(), jniTime.toMillis(),
                rustRead.toMillis(), javaRead.toMillis(), flag);
    } finally {
        Files.deleteIfExists(java);
        Files.deleteIfExists(compact);
        Files.deleteIfExists(jni);
    }
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
