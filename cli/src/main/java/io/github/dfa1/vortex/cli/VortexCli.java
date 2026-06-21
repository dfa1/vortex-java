package io.github.dfa1.vortex.cli;

import java.io.PrintStream;
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

    public static void main(String[] args) {
        if (args.length == 0) {
            printUsage(System.err);
            System.exit(ExitStatus.USAGE_ERROR);
        }
        int exit = switch (args[0]) {
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
        System.exit(exit);
    }

    static void printUsage(PrintStream out) {
        out.println("Usage: java -jar vortex-cli-<version>-all.jar <subcommand> [args]");
        out.println("  inspect <file|url>                  print file structure; url is http(s)://");
        out.println("  tui     <file|url>                  open interactive inspector; url is http(s)://");
        out.println("  view    <file|url>                  open scrollable data grid; url is http(s)://");
        out.println("  export  <file.vortex> [out.csv|-]   write CSV; default output is <name>.csv, `-` for stdout");
        out.println("  import  <file.csv|file.parquet> [out.vortex]  convert CSV or Parquet to Vortex");
        out.println("  schema  <file.vortex>               print dtype (machine-readable)");
        out.println("  count   <file.vortex>               print row count");
        out.println("  select  <file.vortex> <col> [...]   project columns to CSV on stdout");
        out.println("  stats   <file.vortex>               print per-column min/max statistics");
        out.println("  filter  <file.vortex> <expr>        filter rows to CSV (e.g. \"price >= 100\")");
    }
}
