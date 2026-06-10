package io.github.dfa1.vortex.protogen;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/// CLI entry for the build-time {@code .proto} to Java code generator.
/// Invoked from the {@code regenerate-sources} Maven profile via {@code exec-maven-plugin}.
public final class Main {

    private Main() {
    }

    /// Usage: {@code Main --out <dir> <proto> [<proto> ...]}.
    /// @param args command-line arguments
    /// @throws IOException on filesystem errors
    public static void main(String[] args) throws IOException {
        Path out = null;
        List<Path> protos = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            if ("--out".equals(args[i])) {
                if (i + 1 >= args.length) {
                    System.err.println("usage: protogen --out <dir> <proto> [<proto> ...]");
                    System.exit(2);
                    return;
                }
                out = Path.of(args[++i]);
            } else {
                protos.add(Path.of(args[i]));
            }
        }
        if (out == null || protos.isEmpty()) {
            System.err.println("usage: protogen --out <dir> <proto> [<proto> ...]");
            System.exit(2);
            return;
        }
        List<Ast.ProtoFile> files = new ArrayList<>(protos.size());
        for (Path p : protos) {
            String src = Files.readString(p);
            files.add(new Parser(new Lexer(src).tokenize()).parseFile());
        }
        new CodeGen(new TypeRegistry(files)).emit(out);
        System.out.println("protogen: wrote " + protos.size() + " .proto file(s) to " + out);
    }
}
