package io.github.dfa1.vortex.cli;

import io.github.dfa1.vortex.inspect.VortexInspector;
import io.github.dfa1.vortex.reader.VortexHandle;

import java.io.IOException;

@SuppressWarnings("java:S106") // CLI command: stdout is the intended output channel
final class InspectCommand {

    private InspectCommand() {
    }

    static int run(String[] args) {
        if (args.length != 2) {
            System.err.println("usage: inspect <file.vortex | http(s)://url>");
            return ExitStatus.USAGE_ERROR;
        }
        try (VortexHandle handle = CliHandles.openTarget(args[1])) {
            if (handle == null) {
                return ExitStatus.FILE_NOT_FOUND;
            }
            System.out.print(VortexInspector.inspect(handle));
            return ExitStatus.OK;
        } catch (IOException | RuntimeException e) {
            System.err.println("error: " + CliHandles.describe(e));
            e.printStackTrace(System.err);
            return ExitStatus.ERROR;
        }
    }
}
