package io.github.dfa1.vortex.performance;

import io.github.dfa1.vortex.calcite.VortexSchema;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.reader.Chunk;
import io.github.dfa1.vortex.reader.ScanOptions;
import io.github.dfa1.vortex.reader.VortexReader;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.VarBinArray;
import io.github.dfa1.vortex.reader.array.VarBinDictArray;
import io.github.dfa1.vortex.reader.array.VarBinSlicedArray;
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
import org.openjdk.jmh.annotations.Param;
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
/// Fixture: 4M rows, `sym` drawn from 8 tickers (the size and cardinality of the #550 profile that
/// found both issues) and an I64 `val`. A real file runs instead with `-p file=/path/to/data.vortex`
/// plus `-p column=`, `-p value=` and `-p agg=` naming its dictionary column, a value to filter on
/// and a numeric column to sum, e.g. the Raincloud `sf-salaries` table (`scripts/hydrate-raincloud-corpus.sh`):
/// `-p "column=Organization Group" -p "value=Public Protection" -p "agg=Total Compensation"`. `@Setup` checks the column decodes
/// to a `VarBinDictArray` (or a slice of one, as a dictionary spanning many chunks reads), so a writer
/// or decoder change cannot turn this into a benchmark of some other representation.
///
/// - [#readStrings] isolates the array accessor: a bare reader scan calling `getString` per row.
/// - [#sqlScan], [#sqlGroupBy], [#sqlFilter] and [#sqlFilterSum] run the same column through
///   Calcite, where the accessor cost competes with Calcite's own per-row overhead. The two filters
///   take different paths: rows through the enumerator, the sum through the fused aggregate.
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

    private static final int TOTAL_ROWS = 4_000_000;
    private static final int BATCH_SIZE = 50_000;
    private static final String[] TICKERS = {"AAPL", "MSFT", "NVDA", "AMZN", "META", "GOOGL", "TSLA", "AVGO"};

    /// A `.vortex` file to read; empty generates the synthetic fixture.
    @Param("")
    public String file;

    /// The dictionary-encoded string column.
    @Param("sym")
    public String column;

    /// The value the filters select.
    @Param("NVDA")
    public String value;

    /// The numeric column the filtered sum adds up.
    @Param("val")
    public String agg;

    private Path path;
    private boolean generated;
    private ColumnName dictColumn;
    private Connection connection;

    @Setup(Level.Trial)
    public void setup() throws IOException, SQLException {
        generated = file.isEmpty();
        path = generated ? Files.createTempFile("dict-string-bench", ".vortex") : Path.of(file);
        if (generated) {
            writeFixture(path);
        }
        dictColumn = ColumnName.of(column);
        try (VortexReader reader = VortexReader.open(path);
             var iter = reader.scan(ScanOptions.columns(dictColumn));
             Chunk c = iter.next()) {
            Array data = c.column(dictColumn);
            data = data instanceof MaskedArray m ? m.inner() : data;
            data = data instanceof VarBinSlicedArray s ? s.inner() : data;
            if (!(data instanceof VarBinDictArray)) {
                throw new VortexException("expected " + column + " to decode to VarBinDictArray, got "
                        + data.getClass().getSimpleName());
            }
        }
        Properties info = new Properties();
        info.setProperty("lex", "JAVA");
        connection = DriverManager.getConnection("jdbc:calcite:", info);
        connection.unwrap(CalciteConnection.class).getRootSchema()
                .add("vtx", new VortexSchema(Map.of("data", path)));
    }

    @TearDown(Level.Trial)
    public void cleanup() throws IOException, SQLException {
        connection.close();
        if (generated) {
            Files.deleteIfExists(path);
        }
    }

    @Benchmark
    public void readStrings(Blackhole bh) throws IOException {
        try (VortexReader reader = VortexReader.open(path);
             var iter = reader.scan(ScanOptions.columns(dictColumn))) {
            while (iter.hasNext()) {
                try (Chunk c = iter.next()) {
                    Array data = c.column(dictColumn);
                    VarBinArray strings = (VarBinArray) (data instanceof MaskedArray m ? m.inner() : data);
                    long n = strings.length();
                    for (long i = 0; i < n; i++) {
                        bh.consume(strings.getString(i));
                    }
                }
            }
        }
    }

    @Benchmark
    public void sqlScan(Blackhole bh) throws SQLException {
        query("select " + quote(column) + " from vtx.data", bh);
    }

    @Benchmark
    public void sqlGroupBy(Blackhole bh) throws SQLException {
        query("select " + quote(column) + ", count(*) from vtx.data group by " + quote(column), bh);
    }

    @Benchmark
    public void sqlFilter(Blackhole bh) throws SQLException {
        query("select " + quote(agg) + " from vtx.data where " + quote(column) + " = '" + value + "'", bh);
    }

    @Benchmark
    public void sqlFilterSum(Blackhole bh) throws SQLException {
        query("select sum(" + quote(agg) + ") from vtx.data where " + quote(column) + " = '" + value + "'", bh);
    }

    /// Quotes an identifier for Calcite's JAVA lexer, so real files' column names may hold spaces.
    private static String quote(String identifier) {
        return "`" + identifier + "`";
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

    /// Writes the synthetic fixture under the default `sym` / `val` column names.
    private static void writeFixture(Path file) throws IOException {
        ColumnName sym = ColumnName.of("sym");
        ColumnName val = ColumnName.of("val");
        DType.Struct schema = DType.structBuilder()
                .field(sym, DType.UTF8)
                .field(val, DType.I64)
                .build();
        Random rng = new Random(42L);
        try (FileChannel ch = FileChannel.open(file,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
             VortexWriter writer = VortexWriter.create(ch, schema, WriteOptions.defaults())) {
            for (int b = 0; b < TOTAL_ROWS / BATCH_SIZE; b++) {
                String[] syms = new String[BATCH_SIZE];
                long[] vals = new long[BATCH_SIZE];
                for (int i = 0; i < BATCH_SIZE; i++) {
                    syms[i] = TICKERS[rng.nextInt(TICKERS.length)];
                    vals[i] = rng.nextInt(1_000);
                }
                writer.writeChunk(Map.of(sym, syms, val, vals));
            }
        }
    }
}
