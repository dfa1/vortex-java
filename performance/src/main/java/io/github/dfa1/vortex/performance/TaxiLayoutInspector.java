package io.github.dfa1.vortex.performance;

import dev.hardwood.InputFile;
import dev.hardwood.metadata.RepetitionType;
import dev.hardwood.reader.ParquetFileReader;
import dev.hardwood.reader.RowReader;
import dev.hardwood.schema.ColumnSchema;
import dev.vortex.api.Session;
import dev.vortex.arrow.ArrowAllocation;
import dev.vortex.jni.NativeLoader;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.inspect.VortexInspector;
import io.github.dfa1.vortex.reader.VortexReader;
import io.github.dfa1.vortex.parquet.ImportOptions;
import io.github.dfa1.vortex.parquet.ParquetImporter;
import io.github.dfa1.vortex.writer.WriteOptions;
import org.apache.arrow.c.ArrowArray;
import org.apache.arrow.c.ArrowSchema;
import org.apache.arrow.c.Data;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.TimeStampMicroVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.FloatingPointPrecision;
import org.apache.arrow.vector.types.TimeUnit;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;

/// Compare JNI (Rust) vs Java Vortex encoding for the NYC Yellow Taxi 2024-01 dataset.
///
/// Writes the same Parquet file using both writers, then prints `inspect` output
/// so encoding choices can be compared side-by-side.
///
/// Run: java -cp performance/target/benchmarks.jar \
///        --enable-native-access=ALL-UNNAMED \
///        --add-opens java.base/java.nio=ALL-UNNAMED \
///        io.github.dfa1.vortex.performance.TaxiLayoutInspector
public final class TaxiLayoutInspector {

    private static final String PARQUET_URL =
            "https://d37ci6vzurychx.cloudfront.net/trip-data/yellow_tripdata_2024-01.parquet";
    private static final Path CACHE_PATH =
            Path.of(System.getProperty("java.io.tmpdir"), "yellow_tripdata_2024-01.parquet");
    private static final int BATCH_SIZE = 65_536;
    private static final Session SESSION = Session.create();
    private static final ArrowType F64 =
            new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE);
    private static final ArrowType I32 = new ArrowType.Int(32, true);
    private static final ArrowType I64 = new ArrowType.Int(64, true);
    private static final ArrowType TS_MICROS =
            new ArrowType.Timestamp(TimeUnit.MICROSECOND, null);
    // Arrow schema matching all 19 nullable taxi columns
    private static final Schema ARROW_SCHEMA = new Schema(List.of(
            Field.nullable("VendorID", I32),
            Field.nullable("tpep_pickup_datetime", TS_MICROS),
            Field.nullable("tpep_dropoff_datetime", TS_MICROS),
            Field.nullable("passenger_count", I64),
            Field.nullable("trip_distance", F64),
            Field.nullable("RatecodeID", I64),
            Field.nullable("store_and_fwd_flag", ArrowType.Utf8.INSTANCE),
            Field.nullable("PULocationID", I32),
            Field.nullable("DOLocationID", I32),
            Field.nullable("payment_type", I64),
            Field.nullable("fare_amount", F64),
            Field.nullable("extra", F64),
            Field.nullable("mta_tax", F64),
            Field.nullable("tip_amount", F64),
            Field.nullable("tolls_amount", F64),
            Field.nullable("improvement_surcharge", F64),
            Field.nullable("total_amount", F64),
            Field.nullable("congestion_surcharge", F64),
            Field.nullable("Airport_fee", F64)
    ));

    static {
        NativeLoader.loadJni();
    }

    private TaxiLayoutInspector() {
    }

    public static void main(String[] args) throws Exception {
        Path parquetFile = ensureCached();
        System.out.printf("Parquet:      %6.1f MB%n", mb(parquetFile));

        Path jniVortex = Files.createTempFile("taxi-jni", ".vortex");
        Path javaVortex = Files.createTempFile("taxi-java", ".vortex");
        Path javaZstdVortex = Files.createTempFile("taxi-java-zstd", ".vortex");

        try {
            System.out.print("Writing JNI Vortex (Rust encoder)...");
            writeJni(parquetFile, jniVortex);
            System.out.printf("  %6.1f MB%n", mb(jniVortex));

            System.out.print("Writing Java Vortex (cascading depth 3)...");
            ParquetImporter.importParquet(parquetFile, javaVortex);
            System.out.printf(" %6.1f MB%n", mb(javaVortex));

            System.out.print("Writing Java Vortex (cascading depth 3 + Zstd)...");
            ImportOptions zstdOpts = ImportOptions.defaults()
                    .withWriteOptions(WriteOptions.cascading(3).withZstd(true));
            ParquetImporter.importParquet(parquetFile, javaZstdVortex, zstdOpts);
            System.out.printf(" %6.1f MB%n", mb(javaZstdVortex));

            System.out.println("\n══════════════════════════════════════════════════════");
            System.out.println("  JNI / Rust writer");
            System.out.println("══════════════════════════════════════════════════════");
            inspect(jniVortex);

            System.out.println("\n══════════════════════════════════════════════════════");
            System.out.println("  Java writer (no Zstd)");
            System.out.println("══════════════════════════════════════════════════════");
            inspect(javaVortex);

            System.out.println("\n══════════════════════════════════════════════════════");
            System.out.println("  Java writer (with Zstd)");
            System.out.println("══════════════════════════════════════════════════════");
            inspect(javaZstdVortex);
        } finally {
            Files.deleteIfExists(jniVortex);
            Files.deleteIfExists(javaVortex);
            Files.deleteIfExists(javaZstdVortex);
        }
    }

    private static void inspect(Path path) throws IOException {
        try (VortexReader r = VortexReader.open(path, ReadRegistry.loadAll())) {
            System.out.println(VortexInspector.inspect(r));
        }
    }

    private static void writeJni(Path parquetFile, Path vortexFile) throws Exception {
        String uri = vortexFile.toAbsolutePath().toUri().toString();
        BufferAllocator allocator = ArrowAllocation.rootAllocator();

        try (dev.vortex.api.VortexWriter writer = dev.vortex.api.VortexWriter.create(
                SESSION, uri, ARROW_SCHEMA, new HashMap<>(), allocator);
             ParquetFileReader parquet = ParquetFileReader.open(InputFile.of(parquetFile));
             RowReader rows = parquet.buildRowReader().build()) {

            List<ColumnSchema> cols = parquet.getFileSchema().getColumns();
            boolean[] optional = new boolean[cols.size()];
            for (int i = 0; i < cols.size(); i++) {
                optional[i] = cols.get(i).repetitionType() == RepetitionType.OPTIONAL;
            }

            // column name arrays for fast lookup
            String[] names = cols.stream().map(ColumnSchema::name).toArray(String[]::new);

            try (VectorSchemaRoot root = VectorSchemaRoot.create(ARROW_SCHEMA, allocator)) {
                int pos = 0;
                allocateVectors(root, BATCH_SIZE);

                while (rows.hasNext()) {
                    rows.next();
                    fillRow(rows, root, names, optional, pos);
                    pos++;
                    if (pos == BATCH_SIZE) {
                        flushBatch(writer, allocator, root, pos);
                        allocateVectors(root, BATCH_SIZE);
                        pos = 0;
                    }
                }
                if (pos > 0) {
                    flushBatch(writer, allocator, root, pos);
                }
            }
        }
    }

    private static void allocateVectors(VectorSchemaRoot root, int capacity) {
        for (var vec : root.getFieldVectors()) {
            vec.allocateNew();
        }
    }

    private static void fillRow(RowReader rows, VectorSchemaRoot root,
            String[] names, boolean[] optional, int pos) {
        for (int c = 0; c < names.length; c++) {
            String name = names[c];
            boolean isNull = optional[c] && rows.isNull(name);
            switch (root.getVector(name)) {
                case IntVector v -> {
                    if (isNull) {
                        v.setNull(pos);
                    } else {
                        v.setSafe(pos, rows.getInt(name));
                    }
                }
                case BigIntVector v -> {
                    if (isNull) {
                        v.setNull(pos);
                    } else {
                        v.setSafe(pos, rows.getLong(name));
                    }
                }
                case TimeStampMicroVector v -> {
                    if (isNull) {
                        v.setNull(pos);
                    } else {
                        v.setSafe(pos, rows.getLong(name));
                    }
                }
                case Float8Vector v -> {
                    if (isNull) {
                        v.setNull(pos);
                    } else {
                        v.setSafe(pos, rows.getDouble(name));
                    }
                }
                case VarCharVector v -> {
                    if (isNull) {
                        v.setNull(pos);
                    } else {
                        byte[] bytes = rows.getString(name).getBytes(StandardCharsets.UTF_8);
                        v.setSafe(pos, bytes);
                    }
                }
                default -> throw new UnsupportedOperationException(
                        "unexpected vector type: " + root.getVector(name).getClass().getSimpleName());
            }
        }
    }

    private static void flushBatch(dev.vortex.api.VortexWriter writer,
            BufferAllocator allocator,
            VectorSchemaRoot root, int rowCount) throws IOException {
        root.setRowCount(rowCount);
        try (ArrowArray arr = ArrowArray.allocateNew(allocator);
             ArrowSchema schema = ArrowSchema.allocateNew(allocator)) {
            Data.exportVectorSchemaRoot(allocator, root, null, arr, schema);
            writer.writeBatch(arr.memoryAddress(), schema.memoryAddress());
        }
    }

    private static Path ensureCached() throws Exception {
        if (Files.exists(CACHE_PATH)) {
            System.out.printf("cache hit: %s%n", CACHE_PATH);
            return CACHE_PATH;
        }
        System.out.printf("downloading %s...%n", PARQUET_URL);
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(PARQUET_URL)).build();
        HttpResponse<InputStream> resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() != 200) {
            throw new IOException("HTTP " + resp.statusCode());
        }
        try (InputStream in = resp.body()) {
            Files.copy(in, CACHE_PATH);
        }
        return CACHE_PATH;
    }

    private static double mb(Path path) throws IOException {
        return Files.size(path) / 1_048_576.0;
    }
}
