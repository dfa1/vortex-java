package io.github.dfa1.vortex.cli;

import java.io.PrintStream;

/// Entry point for the Vortex command-line tool.
///
/// Exit codes: 0 = success, 1 = usage error, 2 = file not found, 3 = decode error.
public final class VortexCli {

    private VortexCli() {
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            printUsage(System.err);
            System.exit(1);
        }
        int exit = switch (args[0]) {
            case "inspect" -> InspectCommand.run(args);
            case "export"  -> ExportCommand.run(args);
            case "import"  -> ImportCommand.run(args);
            case "schema"  -> SchemaCommand.run(args);
            case "count"   -> CountCommand.run(args);
            case "select"  -> SelectCommand.run(args);
            default -> {
                System.err.println("unknown subcommand: " + args[0]);
                printUsage(System.err);
                yield 1;
            }
        };
        System.exit(exit);
    }

    static void printUsage(PrintStream out) {
        out.println("Usage: java -jar vortex.jar <subcommand> [args]");
        out.println("  inspect <file.vortex>               print file structure");
        out.println("  export  <file.vortex>               write CSV to stdout");
        out.println("  import  <file.csv> [out.vortex]     convert CSV to Vortex");
        out.println("  schema  <file.vortex>               print dtype (machine-readable)");
        out.println("  count   <file.vortex>               print row count");
        out.println("  select  <file.vortex> <col> [...]   project columns to CSV on stdout");
    }
}
