package io.github.dfa1.vortex.cli;

import io.github.dfa1.vortex.csv.CsvImporter;
import io.github.dfa1.vortex.inspect.ByteSize;
import io.github.dfa1.vortex.csv.ImportOptions;
import io.github.dfa1.vortex.parquet.ParquetImporter;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@SuppressWarnings("java:S106")
final class ImportCommand {

    /// `outputTarget` is the raw second positional argument, or `null` when omitted — kept
    /// unresolved here because deriving a default depends on whether `inputTarget` turns out to
    /// be a local path or a URL, decided in [#run].
    private record ParsedArgs(String inputTarget, String outputTarget, Character delimiter) {
    }

    private ImportCommand() {
    }

    static int run(String[] args) {
        ParsedArgs parsedArgs;
        try {
            parsedArgs = parseArgs(args);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.err.println("usage: import [--delimiter <char>] <file.csv|file.parquet|url> [out.vortex]");
            return ExitStatus.USAGE_ERROR;
        }
        String target = parsedArgs.inputTarget();
        boolean remote = target.startsWith("http://") || target.startsWith("https://");
        try {
            if (remote) {
                return runRemote(target, parsedArgs.outputTarget());
            }
            Path inputPath = Path.of(target);
            if (!Files.exists(inputPath)) {
                System.err.println("file not found: " + inputPath);
                return ExitStatus.FILE_NOT_FOUND;
            }
            String name = inputPath.getFileName().toString();
            Path outputPath = parsedArgs.outputTarget() != null
                    ? Path.of(parsedArgs.outputTarget())
                    : inputPath.resolveSibling(vortexName(name));
            if (name.endsWith(".parquet")) {
                return runParquet(inputPath, outputPath);
            } else {
                return runCsv(inputPath, outputPath, parsedArgs.delimiter());
            }
        } catch (IOException e) {
            ProgressBar.clear();
            System.err.println("error: " + e.getMessage());
            return ExitStatus.ERROR;
        }
    }

    /// Handles an `http(s)://` source: Parquet import only (CSV import from a URL isn't
    /// supported yet — CSV has no schema of its own, and type inference over a remote stream
    /// needs a design of its own).
    private static int runRemote(String parquetUrl, String outputTarget) throws IOException {
        if (!parquetUrl.endsWith(".parquet")) {
            System.err.println("only Parquet import is supported from a URL");
            return ExitStatus.USAGE_ERROR;
        }
        Path vortexPath = outputTarget != null
                ? Path.of(outputTarget)
                : Path.of(vortexName(lastPathSegment(parquetUrl)));
        io.github.dfa1.vortex.parquet.ImportOptions options =
                io.github.dfa1.vortex.parquet.ImportOptions.defaults()
                        .withProgressListener(ImportCommand::renderProgress);
        ParquetImporter.importParquet(URI.create(parquetUrl), vortexPath, options);
        ProgressBar.clear();
        System.out.printf("written: %s  (%s)%n", vortexPath, ByteSize.format(Files.size(vortexPath)));
        return ExitStatus.OK;
    }

    private static ParsedArgs parseArgs(String[] args) {
        if (args.length < 2) {
            throw new IllegalArgumentException("missing import arguments");
        }
        List<String> positional = new ArrayList<>();
        Character delimiter = null;
        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            if ("--delimiter".equals(arg)) {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("missing value for --delimiter");
                }
                String value = args[++i];
                if (value.length() != 1) {
                    throw new IllegalArgumentException("--delimiter must be exactly one character");
                }
                delimiter = value.charAt(0);
                continue;
            }
            positional.add(arg);
        }
        if (positional.size() < 1 || positional.size() > 2) {
            throw new IllegalArgumentException("expected input path and optional output path");
        }
        String outputTarget = positional.size() == 2 ? positional.get(1) : null;
        return new ParsedArgs(positional.getFirst(), outputTarget, delimiter);
    }

    private static int runCsv(Path csvPath, Path vortexPath, Character delimiter) throws IOException {
        ImportOptions options = ImportOptions.defaults()
                                        .withProgressListener(ImportCommand::renderProgress);
        if (delimiter != null) {
            options = options.withDelimiter(delimiter);
        }
        CsvImporter.importCsv(csvPath, vortexPath, options);
        ProgressBar.clear();
        printResult(csvPath, vortexPath, options.writeOptions().allowedCascading());
        return ExitStatus.OK;
    }

    private static int runParquet(Path parquetPath, Path vortexPath) throws IOException {
        io.github.dfa1.vortex.parquet.ImportOptions options =
                io.github.dfa1.vortex.parquet.ImportOptions.defaults()
                        .withProgressListener(ImportCommand::renderProgress);
        ParquetImporter.importParquet(parquetPath, vortexPath, options);
        ProgressBar.clear();
        printResult(parquetPath, vortexPath, options.writeOptions().allowedCascading());
        return ExitStatus.OK;
    }

    private static void printResult(Path inputPath, Path vortexPath, int cascadingDepth) throws IOException {
        long inputBytes = Files.size(inputPath);
        long vortexBytes = Files.size(vortexPath);
        double ratio = (double) vortexBytes / inputBytes;
        String sizeChange = ratio <= 1.0
                                    ? String.format("%.1f%% smaller", (1.0 - ratio) * 100)
                                    : String.format("%.1f%% larger", (ratio - 1.0) * 100);
        String cascadingInfo = cascadingDepth > 0
                                       ? String.format(", cascading depth %d", cascadingDepth)
                                       : "";
        System.out.printf("written: %s  (%s → %s, %s%s)%n",
                vortexPath, ByteSize.format(inputBytes), ByteSize.format(vortexBytes),
                sizeChange, cascadingInfo);
    }

    /// Progress callback for imports. An indeterminate `total` (`< 0`, e.g. a streamed source with
    /// no known row count) shows just the running row count; otherwise delegates to the shared bar.
    private static void renderProgress(long done, long total) {
        if (total < 0) {
            System.err.printf("\r  imported %,d rows", done);
            System.err.flush();
        } else {
            ProgressBar.render(done, total);
        }
    }

    /// Strips a known `.csv`/`.parquet` suffix from `inputFileName` and appends `.vortex`.
    private static String vortexName(String inputFileName) {
        String name = inputFileName;
        if (name.endsWith(".csv")) {
            name = name.substring(0, name.length() - 4);
        } else if (name.endsWith(".parquet")) {
            name = name.substring(0, name.length() - 8);
        }
        return name + ".vortex";
    }

    /// The last `/`-separated segment of a URL's path, used as the file name a downloaded
    /// source is named after (mirroring what a browser would save the URL as).
    private static String lastPathSegment(String url) {
        String path = URI.create(url).getPath();
        int slash = path.lastIndexOf('/');
        String name = slash < 0 ? path : path.substring(slash + 1);
        return name.isEmpty() ? "output.parquet" : name;
    }
}
