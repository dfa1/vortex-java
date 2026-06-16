package io.github.dfa1.vortex.cli;

import io.github.dfa1.vortex.cli.tui.IoWorker;
import io.github.dfa1.vortex.cli.tui.LazyGridSource;
import io.github.dfa1.vortex.cli.tui.VortexGridTui;
import io.github.dfa1.vortex.reader.VortexHandle;
import io.github.dfa1.vortex.reader.VortexHttpReader;
import io.github.dfa1.vortex.reader.VortexReader;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

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
            VortexHandle handle = openOnWorker(worker, args[1]);
            if (handle == null) {
                return ExitStatus.FILE_NOT_FOUND;
            }
            System.err.println("done (" + (System.nanoTime() - tOpen) / 1_000_000L + " ms)");

            System.err.print("Indexing chunks... ");
            System.err.flush();
            long tIdx = System.nanoTime();
            try (LazyGridSource source = LazyGridSource.open(handle, worker)) {
                long ms = (System.nanoTime() - tIdx) / 1_000_000L;
                System.err.println("done — " + source.totalRows() + " rows × "
                        + source.columns().size() + " cols (" + ms + " ms)");
                VortexGridTui.show(args[1], source);
            } finally {
                closeOnWorker(worker, handle);
            }
            return ExitStatus.OK;
        } catch (IOException | RuntimeException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            System.err.println("error: " + describe(e));
            return ExitStatus.ERROR;
        }
    }

    private static VortexHandle openOnWorker(IoWorker worker, String target)
            throws InterruptedException, IOException {
        AtomicReference<VortexHandle> handle = new AtomicReference<>();
        AtomicReference<IOException> failure = new AtomicReference<>();
        worker.runAndAwait(() -> {
            try {
                handle.set(open(target));
            } catch (IOException e) {
                failure.set(e);
            }
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return handle.get();
    }

    private static void closeOnWorker(IoWorker worker, VortexHandle handle) {
        try {
            worker.runAndAwait(handle::close);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static VortexHandle open(String target) throws IOException {
        if (target.startsWith("http://") || target.startsWith("https://")) {
            try {
                return VortexHttpReader.open(new URI(target));
            } catch (URISyntaxException e) {
                System.err.println("invalid URL: " + target);
                return null;
            }
        }
        Path path = Path.of(target);
        if (!Files.exists(path)) {
            System.err.println("file not found: " + path);
            return null;
        }
        return VortexReader.open(path);
    }

    private static String describe(Throwable t) {
        StringBuilder sb = new StringBuilder();
        Throwable cur = t;
        while (cur != null) {
            if (!sb.isEmpty()) {
                sb.append(" -> ");
            }
            sb.append(cur.getClass().getSimpleName());
            if (cur.getMessage() != null) {
                sb.append(": ").append(cur.getMessage());
            }
            cur = cur.getCause();
        }
        return sb.toString();
    }
}
