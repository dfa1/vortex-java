package io.github.dfa1.vortex.cli;

import io.github.dfa1.vortex.inspect.VortexInspector;
import io.github.dfa1.vortex.inspect.VortexInspectorTui;
import io.github.dfa1.vortex.io.VortexHandle;
import io.github.dfa1.vortex.io.VortexHttpReader;
import io.github.dfa1.vortex.io.VortexReader;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;

final class InspectCommand {

    private InspectCommand() {
    }

    static int run(String[] args) {
        boolean tui = false;
        String target = null;
        for (int i = 1; i < args.length; i++) {
            if ("--tui".equals(args[i])) {
                tui = true;
            } else if (target == null) {
                target = args[i];
            } else {
                return usage();
            }
        }
        if (target == null) {
            return usage();
        }
        try (VortexHandle handle = open(target)) {
            if (handle == null) {
                return ExitStatus.FILE_NOT_FOUND;
            }
            if (tui) {
                VortexInspectorTui.show(handle);
            } else {
                System.out.print(VortexInspector.inspect(handle));
            }
            return ExitStatus.OK;
        } catch (IOException e) {
            System.err.println("error: " + e.getMessage());
            return ExitStatus.ERROR;
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

    private static int usage() {
        System.err.println("usage: inspect [--tui] <file.vortex | http(s)://url>");
        return ExitStatus.USAGE_ERROR;
    }
}
