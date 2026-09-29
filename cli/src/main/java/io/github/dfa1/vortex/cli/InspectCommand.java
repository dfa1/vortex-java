package io.github.dfa1.vortex.cli;

import io.github.dfa1.vortex.inspect.HtmlReport;
import io.github.dfa1.vortex.inspect.InspectorTree;
import io.github.dfa1.vortex.inspect.VortexInspector;
import io.github.dfa1.vortex.reader.VortexHandle;

import java.io.IOException;

@SuppressWarnings("java:S106") // CLI command: stdout is the intended output channel
final class InspectCommand {

    private InspectCommand() {
    }

    static int run(String[] args) {
        boolean html = args.length == 3 && "--html".equals(args[1]);
        if (args.length != 2 && !html) {
            System.err.println("usage: inspect [--html] <file.vortex | http(s)://url>");
            return ExitStatus.USAGE_ERROR;
        }
        String target = args[args.length - 1];
        try (VortexHandle handle = CliHandles.openTarget(target)) {
            if (handle == null) {
                return ExitStatus.FILE_NOT_FOUND;
            }
            System.out.print(html
                    ? HtmlReport.render(InspectorTree.build(handle), lastSegment(target))
                    : VortexInspector.inspect(handle));
            return ExitStatus.OK;
        } catch (IOException | RuntimeException e) {
            System.err.println("error: " + CliHandles.describe(e));
            e.printStackTrace(System.err);
            return ExitStatus.ERROR;
        }
    }

    /// The part of a path or URL after its last `/` - the report's heading is a file name, not a
    /// wall of directories. Works for both target forms, which [java.nio.file.Path] would not.
    private static String lastSegment(String target) {
        return target.substring(target.lastIndexOf('/') + 1);
    }
}
