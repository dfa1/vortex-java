package io.github.dfa1.vortex.demo.fakedata;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/// Command-line entry point for generating a synthetic Vortex file. See [#printUsage] for the
/// full option/grammar reference.
public final class FakeDataGeneratorCli {

    private static final int DEFAULT_CHUNK_SIZE = 65_536;
    private static final int DEFAULT_CASCADING = 3;
    private static final long DEFAULT_SEED = 42L;

    private FakeDataGeneratorCli() {
    }

    /// @param args CLI arguments; run with no arguments to print usage
    public static void main(String[] args) {
        try {
            run(args);
        } catch (RuntimeException | IOException e) {
            System.err.println("error: " + e.getMessage());
            System.err.println();
            printUsage();
            System.exit(1);
        }
    }

    private static void run(String[] args) throws IOException {
        Integer rows = null;
        Path out = null;
        long seed = DEFAULT_SEED;
        int chunkSize = DEFAULT_CHUNK_SIZE;
        int cascading = DEFAULT_CASCADING;
        String sortBy = null;
        List<String> descriptors = new ArrayList<>();

        int i = 0;
        while (i < args.length) {
            String arg = args[i];
            switch (arg) {
                case "--rows" -> {
                    rows = Integer.parseInt(args[++i]);
                    i++;
                }
                case "--out" -> {
                    out = Path.of(args[++i]);
                    i++;
                }
                case "--seed" -> {
                    seed = Long.parseLong(args[++i]);
                    i++;
                }
                case "--chunk-size" -> {
                    chunkSize = Integer.parseInt(args[++i]);
                    i++;
                }
                case "--cascading" -> {
                    cascading = Integer.parseInt(args[++i]);
                    i++;
                }
                case "--sort-by" -> {
                    sortBy = args[++i];
                    i++;
                }
                default -> {
                    descriptors.add(arg);
                    i++;
                }
            }
        }

        if (rows == null || out == null || descriptors.isEmpty()) {
            throw new IllegalArgumentException("--rows, --out, and at least one column descriptor are required");
        }

        List<ColumnDescriptor> columns = descriptors.stream().map(DescriptorParser::parse).toList();
        FakeDataGenerator.generate(columns, rows, seed, sortBy, chunkSize, cascading, out);
        System.out.printf("Wrote %d rows (%d columns) to %s%n", rows, columns.size(), out);
    }

    private static void printUsage() {
        System.err.println("""
                Usage: vortex-fakedata-generator --rows N --out FILE [options] "name:type:generator(args)" [...]

                Options:
                  --rows N          number of rows to generate (required)
                  --out FILE        destination .vortex file (required)
                  --seed N          random seed (default 42)
                  --chunk-size N    rows per written chunk (default 65536)
                  --cascading N     write compression cascade depth (default 3)
                  --sort-by COLUMN  sort all rows by this column (ascending) before writing

                Column descriptor grammar: name:type:generator(args)
                  type:      i8 i16 i32 i64 u8 u16 u32 u64 f32 f64 utf8 bool
                  generator: series(start,step)   arithmetic progression (like SQL generate_series)
                             range(min,max)       uniform random
                             normal(mean,stddev)  gaussian random
                             enum(prefix,count)   random categorical label, cycling prefix0..prefix(count-1)
                             constant(value)      same value every row
                             bool()               random true/false

                Example:
                  vortex-fakedata-generator --rows 2000000 --out trades.vortex --sort-by symbol \\
                      "timestamp:i64:series(1700000000000,1000)" \\
                      "symbol:utf8:enum(SYM,30)" \\
                      "price:f64:range(50,150)" \\
                      "volume:i64:range(100,10000)"
                """);
    }
}
