package io.github.dfa1.vortex.cli;

import io.github.dfa1.vortex.core.error.VortexException;
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
            InspectorTree tree = InspectorTree.build(handle, (done, total) -> ProgressBar.render(done, total, "segments"));
            ProgressBar.clear();
            System.out.print(html
                    ? HtmlReport.render(tree, lastSegment(target))
                    : VortexInspector.render(tree));
            return ExitStatus.OK;
        } catch (IOException | RuntimeException e) {
            System.err.println("error: " + CliHandles.describe(e));
            // a VortexException is an expected failure (bad file, HTTP error) already fully described
            // above; only an unexpected error needs its stack
            if (!(e instanceof VortexException)) {
                e.printStackTrace(System.err);
            }
            return ExitStatus.ERROR;
        }
    }

    /// The part of a path or URL after its last `/` - the report's heading is a file name, not a
    /// wall of directories. Works for both target forms, which [java.nio.file.Path] would not.
    private static String lastSegment(String target) {
        return target.substring(target.lastIndexOf('/') + 1);
    }
}
