package io.github.dfa1.vortex.performance;

import io.github.dfa1.vortex.calcite.VortexSchema;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.reader.Chunk;
import io.github.dfa1.vortex.reader.ScanOptions;
import io.github.dfa1.vortex.reader.VortexReader;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.VarBinArray;
import io.github.dfa1.vortex.reader.array.VarBinDictArray;
import io.github.dfa1.vortex.writer.VortexWriter;
import io.github.dfa1.vortex.writer.WriteOptions;
import org.apache.calcite.jdbc.CalciteConnection;
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
import org.openjdk.jmh.infra.Blackhole;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/// Reads of a low-cardinality, dictionary-encoded Utf8 column: the projection cost behind #551
/// (one string decode per row instead of per dictionary entry) and the filter cost behind #552
/// (a string comparison per row instead of per dictionary entry).
///
/// Fixture: 1M rows, `sym` drawn from 8 tickers (the cardinality of the #550 profile that found
/// both issues) and an I64 `val`. `@Setup` checks `sym` decodes to a `VarBinDictArray`, so a writer
/// or decoder change cannot turn this into a benchmark of some other representation.
///
/// - [#readStrings] isolates the array accessor: a bare reader scan calling `getString` per row.
/// - [#sqlScan], [#sqlGroupBy] and [#sqlFilter] run the same column through Calcite, where the
///   accessor cost competes with Calcite's own per-row overhead.
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 3)
@Measurement(iterations = 5, time = 3)
@Fork(value = 1, jvmArgsAppend = {
        "--add-opens", "java.base/java.nio=ALL-UNNAMED",
        "--enable-native-access=ALL-UNNAMED",
        "--sun-misc-unsafe-memory-access=allow"
})
public class DictStringBenchmark {

    private static final int TOTAL_ROWS = 1_000_000;
    private static final int BATCH_SIZE = 50_000;
    private static final ColumnName SYM = ColumnName.of("sym");
    private static final ColumnName VAL = ColumnName.of("val");
    private static final String[] TICKERS = {"AAPL", "MSFT", "NVDA", "AMZN", "META", "GOOGL", "TSLA", "AVGO"};

    private Path file;
    private Connection connection;

    @Setup(Level.Trial)
    public void setup() throws IOException, SQLException {
        file = Files.createTempFile("dict-string-bench", ".vortex");
        writeFixture(file);
        try (VortexReader reader = VortexReader.open(file);
             var iter = reader.scan(ScanOptions.columns(SYM));
             Chunk c = iter.next()) {
            Array sym = c.column(SYM);
            if (!(sym instanceof VarBinDictArray)) {
                throw new VortexException("expected sym to decode to VarBinDictArray, got "
                        + sym.getClass().getSimpleName());
            }
        }
        Properties info = new Properties();
        info.setProperty("lex", "JAVA");
        connection = DriverManager.getConnection("jdbc:calcite:", info);
        connection.unwrap(CalciteConnection.class).getRootSchema()
                .add("vtx", new VortexSchema(Map.of("data", file)));
    }

    @TearDown(Level.Trial)
    public void cleanup() throws IOException, SQLException {
        connection.close();
        Files.deleteIfExists(file);
    }

    @Benchmark
    public void readStrings(Blackhole bh) throws IOException {
        try (VortexReader reader = VortexReader.open(file);
             var iter = reader.scan(ScanOptions.columns(SYM))) {
            while (iter.hasNext()) {
                try (Chunk c = iter.next()) {
                    VarBinArray sym = c.column(SYM);
                    long n = sym.length();
                    for (long i = 0; i < n; i++) {
                        bh.consume(sym.getString(i));
                    }
                }
            }
        }
    }

    @Benchmark
    public void sqlScan(Blackhole bh) throws SQLException {
        query("select sym from vtx.data", bh);
    }

    @Benchmark
    public void sqlGroupBy(Blackhole bh) throws SQLException {
        query("select sym, count(*) from vtx.data group by sym", bh);
    }

    @Benchmark
    public void sqlFilter(Blackhole bh) throws SQLException {
        query("select val from vtx.data where sym = 'NVDA'", bh);
    }

    private void query(String sql, Blackhole bh) throws SQLException {
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            int columns = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                for (int c = 1; c <= columns; c++) {
                    bh.consume(rs.getObject(c));
                }
            }
        }
    }

    private static void writeFixture(Path file) throws IOException {
        DType.Struct schema = DType.structBuilder()
                .field(SYM, DType.UTF8)
                .field(VAL, DType.I64)
                .build();
        Random rng = new Random(42L);
        try (FileChannel ch = FileChannel.open(file,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
             VortexWriter writer = VortexWriter.create(ch, schema, WriteOptions.defaults())) {
            for (int b = 0; b < TOTAL_ROWS / BATCH_SIZE; b++) {
                String[] sym = new String[BATCH_SIZE];
                long[] val = new long[BATCH_SIZE];
                for (int i = 0; i < BATCH_SIZE; i++) {
                    sym[i] = TICKERS[rng.nextInt(TICKERS.length)];
                    val[i] = rng.nextInt(1_000);
                }
                writer.writeChunk(Map.of(SYM, sym, VAL, val));
            }
        }
    }
}
