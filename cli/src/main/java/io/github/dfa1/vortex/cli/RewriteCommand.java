package io.github.dfa1.vortex.cli;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.Edition;
import io.github.dfa1.vortex.core.model.Editions;
import io.github.dfa1.vortex.inspect.ByteSize;
import io.github.dfa1.vortex.reader.VortexHandle;
import io.github.dfa1.vortex.writer.WriteOptions;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.Collectors;

@SuppressWarnings("java:S106") // CLI command: stdout is the intended output channel
final class RewriteCommand {

    private static final String USAGE = "usage: rewrite [--compact] [--no-zone-maps] [--no-global-dict]"
            + " [--edition <id>] <file.vortex | http(s)://url> <out.vortex>";

    private RewriteCommand() {
    }

    static int run(String[] args) {
        WriteOptions options = WriteOptions.defaults().withExecutor(ForkJoinPool.commonPool());
        List<String> positional = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--compact" -> options = options.withCompact(true);
                case "--no-zone-maps" -> options = options.withZoneMaps(false);
                case "--no-global-dict" -> options = options.withGlobalDict(false);
                case "--edition" -> {
                    Optional<Edition> edition = i + 1 < args.length ? edition(args[i + 1]) : Optional.empty();
                    if (edition.isEmpty()) {
                        return unknownEdition();
                    }
                    options = options.withEdition(edition.get());
                    i++;
                }
                case String flag when flag.startsWith("--edition=") -> {
                    Optional<Edition> edition = edition(flag.substring("--edition=".length()));
                    if (edition.isEmpty()) {
                        return unknownEdition();
                    }
                    options = options.withEdition(edition.get());
                }
                default -> positional.add(args[i]);
            }
        }
        if (positional.size() != 2) {
            System.err.println(USAGE);
            return ExitStatus.USAGE_ERROR;
        }
        Path output = Path.of(positional.get(1));
        if (Files.exists(output)) {
            System.err.println("refusing to overwrite: " + output);
            return ExitStatus.USAGE_ERROR;
        }
        try (VortexHandle handle = CliHandles.openTarget(positional.get(0))) {
            if (handle == null) {
                return ExitStatus.FILE_NOT_FOUND;
            }
            long rows = Rewriter.rewrite(handle, output, options, ProgressBar::render);
            ProgressBar.clear();
            System.out.printf("written: %s  (%,d rows, %s)%n", output, rows, ByteSize.format(Files.size(output)));
            return ExitStatus.OK;
        } catch (FileAlreadyExistsException e) {
            // another process created the output after the check above: it is theirs, so leave it
            ProgressBar.clear();
            System.err.println("refusing to overwrite: " + output);
            return ExitStatus.ERROR;
        } catch (IOException | RuntimeException e) {
            ProgressBar.clear();
            System.err.println("error: " + CliHandles.describe(e));
            if (!(e instanceof VortexException)) {
                e.printStackTrace(System.err);
            }
            deleteQuietly(output);
            return ExitStatus.ERROR;
        }
    }

    private static int unknownEdition() {
        System.err.println("--edition needs one of: " + Editions.ALL.stream()
                .map(e -> e.id().toString()).collect(Collectors.joining(", ")));
        return ExitStatus.USAGE_ERROR;
    }

    private static Optional<Edition> edition(String id) {
        return Editions.ALL.stream().filter(e -> e.id().toString().equals(id)).findFirst();
    }

    /// A failed rewrite leaves a partial file, which a retry would then refuse to overwrite.
    private static void deleteQuietly(Path output) {
        try {
            Files.deleteIfExists(output);
        } catch (IOException _) {
            // best effort: the original error is the one worth reporting
        }
    }
}
