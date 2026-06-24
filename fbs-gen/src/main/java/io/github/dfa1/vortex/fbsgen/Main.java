package io.github.dfa1.vortex.fbsgen;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/// CLI entry for the build-time `.fbs` to MemorySegment-native Java code generator.
/// Invoked from the `regenerate-sources` Maven profile via `exec-maven-plugin`.
@SuppressWarnings("java:S106") // build-time code-gen CLI: progress goes to stdout
public final class Main {

    private Main() {
    }

    /// Usage: `Main --out <dir> <schema.fbs> [<schema.fbs> ...]`.
    /// @param args command-line arguments
    /// @throws IOException on filesystem errors
    public static void main(String[] args) throws IOException {
        Path out = null;
        List<Path> schemas = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            if ("--out".equals(args[i])) {
                if (i + 1 >= args.length) {
                    usage();
                    return;
                }
                out = Path.of(args[++i]);
            } else {
                schemas.add(Path.of(args[i]));
            }
        }
        if (out == null || schemas.isEmpty()) {
            usage();
            return;
        }
        List<Ast.SchemaFile> files = new ArrayList<>(schemas.size());
        for (Path p : schemas) {
            files.add(new Parser(new Lexer(Files.readString(p)).tokenize()).parseFile());
        }
        new CodeGen(new TypeRegistry(files)).emit(out);
        System.out.println("fbsgen: wrote " + schemas.size() + " .fbs file(s) to " + out);
    }

    private static void usage() {
        System.err.println("usage: fbsgen --out <dir> <schema.fbs> [<schema.fbs> ...]");
        System.exit(2);
    }
}
