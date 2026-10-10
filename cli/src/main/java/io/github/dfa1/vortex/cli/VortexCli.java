package io.github.dfa1.vortex.cli;

import java.io.PrintStream;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

/// Entry point for the Vortex command-line tool.
///
/// Exit codes: see [ExitStatus].
@SuppressWarnings("java:S106") // CLI entry point: stdout is the intended output channel
public final class VortexCli {

    static {
        Logger.getLogger("dev.hardwood").setLevel(Level.WARNING);
    }

    private VortexCli() {
    }

    /// Global flag, accepted anywhere on the command line: prints how long the operation took.
    private static final String TIMING_FLAG = "--timing";

    public static void main(String[] args) {
        System.exit(execute(args));
    }

    /// Runs the command and returns its exit status. With `--timing` it then prints the elapsed time to
    /// standard error, so data on standard output stays clean in a pipeline. The clock starts here, once
    /// the JVM is up, so it measures the command and not the runtime's start-up.
    static int execute(String[] args) {
        long start = System.nanoTime();
        boolean timing = Arrays.asList(args).contains(TIMING_FLAG);
        String[] commandArgs = Arrays.stream(args).filter(arg -> !TIMING_FLAG.equals(arg)).toArray(String[]::new);
        int exit = dispatch(commandArgs);
        if (timing) {
            Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
            System.err.printf(Locale.ROOT, "elapsed: %.1f ms%n", elapsed.toNanos() / 1_000_000.0);
        }
        return exit;
    }

    private static int dispatch(String[] args) {
        if (args.length == 0) {
            printUsage(System.err);
            return ExitStatus.USAGE_ERROR;
        }
        return switch (args[0]) {
            case "inspect" -> InspectCommand.run(args);
            case "tui" -> TuiCommand.run(args);
            case "view" -> ViewCommand.run(args);
            case "export" -> ExportCommand.run(args);
            case "import" -> ImportCommand.run(args);
            case "schema" -> SchemaCommand.run(args);
            case "count" -> CountCommand.run(args);
            case "select" -> SelectCommand.run(args);
            case "stats" -> StatsCommand.run(args);
            case "filter" -> FilterCommand.run(args);
            default -> {
                System.err.println("unknown subcommand: " + args[0]);
                printUsage(System.err);
                yield ExitStatus.USAGE_ERROR;
            }
        };
    }

    static void printUsage(PrintStream out) {
        out.println("Usage: java -jar vortex-cli-<version>-all.jar <subcommand> [args] [--timing]");
        out.println("  inspect [--html] <file|url>         print file structure; --html writes an HTML report");
        out.println("  tui     <file|url>                  open interactive inspector; url is http(s)://");
        out.println("  view    <file|url>                  open scrollable data grid; url is http(s)://");
        out.println("  export  <file.vortex> [out.csv|out.parquet|-]  write CSV or Parquet; default is <name>.csv, `-` for stdout");
        out.println("  import  [--delimiter <char>] <file.csv|file.parquet|url> [out.vortex|out.parquet]");
        out.println("                                       convert CSV or Parquet (local or url) to Vortex or Parquet");
        out.println("  schema  <file.vortex>               print dtype (machine-readable)");
        out.println("  count   <file.vortex>               print row count");
        out.println("  select  <file.vortex> <col> [...] [--where <expr>]  project columns to CSV on stdout, optionally filtered");
        out.println("  stats   <file.vortex>               print per-column min/max statistics");
        out.println("  filter  <file.vortex> <expr>        filter rows to CSV (e.g. \"price >= 100\")");
        out.println("Any subcommand also takes --timing: print the elapsed time to stderr, JVM start-up excluded.");
    }
}
