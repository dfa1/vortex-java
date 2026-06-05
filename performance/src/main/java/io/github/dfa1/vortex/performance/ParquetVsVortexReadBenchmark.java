package io.github.dfa1.vortex.performance;

import dev.hardwood.InputFile;
import dev.hardwood.reader.ParquetFileReader;
import dev.hardwood.reader.RowReader;
import dev.hardwood.schema.ColumnProjection;
import io.github.dfa1.vortex.core.array.DoubleArray;
import io.github.dfa1.vortex.core.array.IntArray;
import io.github.dfa1.vortex.encoding.EncodingRegistry;
import io.github.dfa1.vortex.io.VortexReader;
import io.github.dfa1.vortex.parquet.ParquetImporter;
import io.github.dfa1.vortex.scan.ScanResult;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/// Benchmark: Hardwood Parquet read vs Java Vortex read on the same real-world dataset.
///
/// Dataset: NYC Yellow Taxi 2024-01 (~3M rows), three columns:
///   trip_distance (DOUBLE), fare_amount (DOUBLE), PULocationID (INT32)
///
/// Setup downloads the Parquet file once per trial (cached in /tmp), converts it to
/// Vortex with column projection, then both benchmarks scan and sum trip_distance.
///
/// Override the Parquet source with: -Dbench.parquet=/path/to/file.parquet
///
/// Run: java -jar performance/target/benchmarks.jar ParquetVsVortexReadBenchmark
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 3)
@Measurement(iterations = 5, time = 5)
@Fork(value = 1, jvmArgsAppend = {
        "--add-opens", "java.base/java.nio=ALL-UNNAMED",
        "--enable-native-access=ALL-UNNAMED",
        "--sun-misc-unsafe-memory-access=allow"
})
public class ParquetVsVortexReadBenchmark {

    private static final String PARQUET_URL =
            "https://d37ci6vzurychx.cloudfront.net/trip-data/yellow_tripdata_2024-01.parquet";
    private static final Path CACHE_PATH =
            Path.of(System.getProperty("java.io.tmpdir"), "yellow_tripdata_2024-01.parquet");
    private static final Object SETUP_LOCK = new Object();
    private static Path sharedParquetFile;
    private static Path sharedVortexFile;
    private static int refCount;

    private Path parquetFile;
    private Path vortexFile;
    private EncodingRegistry registry;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        registry = EncodingRegistry.loadAll();
        synchronized (SETUP_LOCK) {
            if (sharedParquetFile == null) {
                String override = System.getProperty("bench.parquet");
                if (override != null && !override.isEmpty()) {
                    sharedParquetFile = Path.of(override);
                    System.out.printf("[ParquetVsVortexReadBenchmark] using override: %s%n", sharedParquetFile);
                } else {
                    sharedParquetFile = downloadIfAbsent(CACHE_PATH, PARQUET_URL);
                    System.out.printf("[ParquetVsVortexReadBenchmark] parquet: %s (%.1f MB)%n",
                            sharedParquetFile, Files.size(sharedParquetFile) / 1_048_576.0);
                }
                sharedVortexFile = Files.createTempFile("taxi-bench", ".vortex");
                System.out.print("[ParquetVsVortexReadBenchmark] converting to vortex (all columns)...");
                ParquetImporter.importParquet(sharedParquetFile, sharedVortexFile);
                System.out.printf(" done (%.1f MB)%n", Files.size(sharedVortexFile) / 1_048_576.0);
            }
            refCount++;
            parquetFile = sharedParquetFile;
            vortexFile = sharedVortexFile;
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() throws IOException {
        synchronized (SETUP_LOCK) {
            refCount--;
            if (refCount == 0) {
                Files.deleteIfExists(sharedVortexFile);
                sharedVortexFile = null;
                sharedParquetFile = null;
            }
        }
    }

    /// Hardwood: scan all rows, sum trip_distance (single column).
    @Benchmark
    public double parquetRead() throws IOException {
        double sum = 0.0;
        ColumnProjection projection = ColumnProjection.columns("trip_distance");
        try (ParquetFileReader reader = ParquetFileReader.open(InputFile.of(parquetFile));
             RowReader rows = reader.buildRowReader().projection(projection).build()) {
            while (rows.hasNext()) {
                rows.next();
                sum += rows.getDouble("trip_distance");
            }
        }
        return sum;
    }

    /// Hardwood: scan all rows, sum fare_amount + PULocationID (two columns).
    @Benchmark
    public double parquetReadMultiColumn() throws IOException {
        double fareSum = 0.0;
        long idSum = 0L;
        ColumnProjection projection = ColumnProjection.columns("fare_amount", "PULocationID");
        try (ParquetFileReader reader = ParquetFileReader.open(InputFile.of(parquetFile));
             RowReader rows = reader.buildRowReader().projection(projection).build()) {
            while (rows.hasNext()) {
                rows.next();
                fareSum += rows.getDouble("fare_amount");
                idSum += rows.getInt("PULocationID");
            }
        }
        return fareSum + idSum;
    }

    /// Vortex: scan all rows, sum trip_distance via fold.
    @Benchmark
    public double vortexRead() throws IOException {
        double sum = 0.0;
        try (VortexReader vr = VortexReader.open(vortexFile, registry)) {
            var iter = vr.scan(io.github.dfa1.vortex.scan.ScanOptions.columns("trip_distance"));
            while (iter.hasNext()) {
                ScanResult r = iter.next();
                DoubleArray col = r.column("trip_distance");
                sum += col.fold(0.0, Double::sum);
            }
        }
        return sum;
    }

    /// Vortex: scan fare_amount + PULocationID, sum both (exercises multi-column projection).
    @Benchmark
    public double vortexReadMultiColumn() throws IOException {
        double fareSum = 0.0;
        long idSum = 0L;
        try (VortexReader vr = VortexReader.open(vortexFile, registry)) {
            var iter = vr.scan(io.github.dfa1.vortex.scan.ScanOptions.columns("fare_amount", "PULocationID"));
            while (iter.hasNext()) {
                ScanResult r = iter.next();
                DoubleArray fare = r.column("fare_amount");
                IntArray loc = r.column("PULocationID");
                fareSum += fare.fold(0.0, Double::sum);
                idSum += loc.fold(0, Integer::sum);
            }
        }
        return fareSum + idSum;
    }

    private static Path downloadIfAbsent(Path dest, String url) throws Exception {
        if (Files.exists(dest)) {
            System.out.printf("[ParquetVsVortexReadBenchmark] cache hit: %s%n", dest);
            return dest;
        }
        System.out.printf("[ParquetVsVortexReadBenchmark] downloading %s...%n", url);
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " for " + url);
        }
        try (InputStream in = response.body()) {
            Files.copy(in, dest);
        }
        return dest;
    }
}
