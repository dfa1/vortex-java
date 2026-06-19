package io.github.dfa1.vortex.cli;

import io.github.dfa1.vortex.cli.tui.IoWorker;
import io.github.dfa1.vortex.cli.tui.LazyGridSource;
import io.github.dfa1.vortex.cli.tui.VortexGridTui;
import io.github.dfa1.vortex.reader.VortexHandle;

import java.io.IOException;
import java.util.Optional;

@SuppressWarnings("java:S106")
final class ViewCommand {

    private ViewCommand() {
    }

    static int run(String[] args) {
        if (args.length != 2) {
            System.err.println("usage: view <file.vortex | http(s)://url>");
            return ExitStatus.USAGE_ERROR;
        }
        try (IoWorker worker = new IoWorker("vortex-view-io")) {
            System.err.print("Opening file... ");
            System.err.flush();
            long tOpen = System.nanoTime();
            Optional<VortexHandle> opened = CliHandles.openOnWorker(worker, args[1]);
            if (opened.isEmpty()) {
                return ExitStatus.FILE_NOT_FOUND;
            }
            VortexHandle handle = opened.get();
            try {
                System.err.println("done (" + (System.nanoTime() - tOpen) / 1_000_000L + " ms)");

                System.err.print("Indexing chunks... ");
                System.err.flush();
                long tIdx = System.nanoTime();
                try (LazyGridSource source = LazyGridSource.open(handle, worker)) {
                    long ms = (System.nanoTime() - tIdx) / 1_000_000L;
                    System.err.println("done — " + source.totalRows() + " rows × "
                            + source.columns().size() + " cols (" + ms + " ms)");
                    VortexGridTui.show(args[1], source);
                }
                return ExitStatus.OK;
            } finally {
                CliHandles.closeOnWorker(worker, handle);
            }
        } catch (IOException | RuntimeException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            System.err.println("error: " + CliHandles.describe(e));
            return ExitStatus.ERROR;
        }
    }
}
