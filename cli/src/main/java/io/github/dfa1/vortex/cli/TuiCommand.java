package io.github.dfa1.vortex.cli;

import io.github.dfa1.vortex.cli.tui.IoWorker;
import io.github.dfa1.vortex.cli.tui.VortexInspectorTui;
import io.github.dfa1.vortex.inspect.InspectorTree;
import io.github.dfa1.vortex.io.VortexHandle;
import io.github.dfa1.vortex.io.VortexHttpReader;
import io.github.dfa1.vortex.io.VortexReader;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

final class TuiCommand {

    private TuiCommand() {
    }

    static int run(String[] args) {
        if (args.length != 2) {
            System.err.println("usage: tui <file.vortex | http(s)://url>");
            return ExitStatus.USAGE_ERROR;
        }
        try (IoWorker worker = new IoWorker("vortex-tui-io")) {
            VortexHandle handle = openOnWorker(worker, args[1]);
            if (handle == null) {
                return ExitStatus.FILE_NOT_FOUND;
            }
            try {
                VortexInspectorTui.show(handle, worker, progressBar(System.err));
            } finally {
                closeOnWorker(worker, handle);
            }
            return ExitStatus.OK;
        } catch (IOException | RuntimeException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            System.err.println("error: " + describe(e));
            if (System.getenv("VORTEX_DEBUG") != null) {
                e.printStackTrace(System.err);
            }
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

    private static InspectorTree.Progress progressBar(PrintStream out) {
        int width = 30;
        return (current, total) -> {
            if (total <= 0) {
                return;
            }
            int filled = (int) ((long) current * width / total);
            StringBuilder bar = new StringBuilder(width + 32);
            bar.append('\r').append("Loading metadata [");
            for (int i = 0; i < width; i++) {
                bar.append(i < filled ? '#' : '-');
            }
            bar.append("] ").append(current).append('/').append(total);
            if (current == total) {
                bar.append('\n');
            }
            out.print(bar);
            out.flush();
        };
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
