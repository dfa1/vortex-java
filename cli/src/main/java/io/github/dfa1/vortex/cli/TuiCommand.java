package io.github.dfa1.vortex.cli;

import io.github.dfa1.vortex.cli.tui.IoWorker;
import io.github.dfa1.vortex.cli.tui.VortexInspectorTui;
import io.github.dfa1.vortex.inspect.InspectorTree;
import io.github.dfa1.vortex.reader.VortexHandle;

import java.io.IOException;
import java.io.PrintStream;
import java.util.Optional;

final class TuiCommand {

    private TuiCommand() {
    }

    static int run(String[] args) {
        if (args.length != 2) {
            System.err.println("usage: tui <file.vortex | http(s)://url>");
            return ExitStatus.USAGE_ERROR;
        }
        try (IoWorker worker = new IoWorker("vortex-tui-io")) {
            Optional<VortexHandle> opened = CliHandles.openOnWorker(worker, args[1]);
            if (opened.isEmpty()) {
                return ExitStatus.FILE_NOT_FOUND;
            }
            VortexHandle handle = opened.get();
            try {
                VortexInspectorTui.show(handle, worker, progressBar(System.err));
            } finally {
                CliHandles.closeOnWorker(worker, handle);
            }
            return ExitStatus.OK;
        } catch (IOException | RuntimeException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            System.err.println("error: " + CliHandles.describe(e));
            e.printStackTrace(System.err);
            return ExitStatus.ERROR;
        }
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
}
