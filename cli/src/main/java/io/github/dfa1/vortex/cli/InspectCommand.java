package io.github.dfa1.vortex.cli;

import io.github.dfa1.vortex.inspect.VortexInspector;
import io.github.dfa1.vortex.reader.VortexHandle;
import io.github.dfa1.vortex.reader.VortexHttpReader;
import io.github.dfa1.vortex.reader.VortexReader;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;

final class InspectCommand {

    private InspectCommand() {
    }

    static int run(String[] args) {
        if (args.length != 2) {
            System.err.println("usage: inspect <file.vortex | http(s)://url>");
            return ExitStatus.USAGE_ERROR;
        }
        try (VortexHandle handle = open(args[1])) {
            if (handle == null) {
                return ExitStatus.FILE_NOT_FOUND;
            }
            System.out.print(VortexInspector.inspect(handle));
            return ExitStatus.OK;
        } catch (IOException | RuntimeException e) {
            System.err.println("error: " + describe(e));
            e.printStackTrace(System.err);
            return ExitStatus.ERROR;
        }
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
}
