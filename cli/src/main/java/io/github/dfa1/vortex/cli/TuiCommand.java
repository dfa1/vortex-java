package io.github.dfa1.vortex.cli;

import io.github.dfa1.vortex.cli.tui.IoWorker;
import io.github.dfa1.vortex.cli.tui.VortexInspectorTui;
import io.github.dfa1.vortex.reader.VortexHandle;

import java.io.IOException;
import java.util.Optional;

@SuppressWarnings("java:S106") // CLI command: stdout is the intended output channel
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
                VortexInspectorTui.show(handle, worker);
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
}
