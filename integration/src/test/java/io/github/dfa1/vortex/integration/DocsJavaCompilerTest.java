package io.github.dfa1.vortex.integration;

import org.junit.jupiter.api.Test;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// The docs compiler, tier 2: actually compiles the ```java code blocks in `docs/tutorial.md`
/// and `docs/how-to.md` against the real classpath, so a stale import or a renamed/removed API
/// fails the build instead of the next reader (the tutorial's imports drifted, unnoticed, for
/// months before this test existed).
///
/// `tutorial.md` is one continuous walkthrough, so its blocks are concatenated in file order
/// into a single method body, sharing variables the way the narrative does (`schema`, `outPath`
/// declared in one block, used in a later one). `how-to.md` is a set of independent recipes that
/// happen to reuse conventional names across unrelated sections (two different sections each
/// declare their own `ScanOptions opts`), so each of its blocks compiles in its own isolated
/// scope instead — the same default rustdoc uses for markdown code blocks, and for the same
/// reason: unrelated snippets sharing one scope collide on names by coincidence, not because the
/// docs are wrong. Recipes also freely reuse types (`VortexReader`, `Path`, ...) that an earlier
/// recipe already imported without repeating the import, so every block compiles against the
/// union of imports declared anywhere in the file, plus [#HOW_TO_PRELUDE_IMPORTS] for the handful
/// of types no recipe ever imports explicitly.
///
/// Other living docs (`CLAUDE.md`, `README.md`, `compatibility.md`, `explanation.md`) are exempt:
/// their snippets are deliberately illustrative fragments (undeclared context variables, `...`
/// elisions) that were never meant to compile standalone. [DocsConsistencyTest] already checks
/// their code-shaped claims (FQNs, method names, links) without requiring full compilation.
class DocsJavaCompilerTest {

    /// Types every `how-to.md` recipe assumes are already imported from following the guide
    /// top to bottom, but which no block ever imports explicitly.
    private static final List<String> HOW_TO_PRELUDE_IMPORTS = List.of(
            "import io.github.dfa1.vortex.reader.VortexReader;",
            "import io.github.dfa1.vortex.reader.ScanOptions;",
            "import io.github.dfa1.vortex.reader.RowFilter;",
            "import io.github.dfa1.vortex.writer.VortexWriter;",
            "import io.github.dfa1.vortex.writer.WriteOptions;",
            "import java.nio.file.Path;",
            "import java.nio.file.StandardOpenOption;",
            "import java.nio.channels.FileChannel;",
            "import java.util.List;",
            "import java.util.Map;",
            "import java.net.URI;");

    @Test
    void tutorialMd_compilesAsOneWalkthrough() throws IOException {
        // Given every ```java block in tutorial.md, in file order
        List<Block> blocks = extractJavaBlocks(repoRoot().resolve("docs/tutorial.md"));
        assertThat(blocks).as("java blocks found in tutorial.md").isNotEmpty();

        // When concatenated into one method body sharing the narrative's variables
        Set<String> imports = new LinkedHashSet<>();
        List<String> body = new ArrayList<>();
        for (Block block : blocks) {
            imports.addAll(block.importLines());
            body.addAll(block.bodyLines());
        }
        String className = "TutorialWalkthrough";
        JavaFileObject source = sourceFile(className, buildSource(className, imports, body));

        // Then it compiles against the real classpath
        List<String> errors = compileErrors(List.of(source)).stream()
                .map(d -> "tutorial.md: " + d.getMessage(null))
                .toList();
        assertThat(errors).as("compile errors in docs/tutorial.md").isEmpty();
    }

    @Test
    void howToMd_blocksCompileIndependently() throws IOException {
        // Given every ```java block in how-to.md, each an independent recipe
        List<Block> blocks = extractJavaBlocks(repoRoot().resolve("docs/how-to.md"));
        assertThat(blocks).as("java blocks found in how-to.md").isNotEmpty();

        Set<String> fileWideImports = new LinkedHashSet<>(HOW_TO_PRELUDE_IMPORTS);
        for (Block block : blocks) {
            fileWideImports.addAll(block.importLines());
        }

        // When each block is compiled in its own scope, so unrelated recipes reusing the same
        // local-variable names (two sections both declare `ScanOptions opts`) do not collide
        List<JavaFileObject> sources = new ArrayList<>();
        Map<String, Block> classNameToBlock = new HashMap<>();
        for (int i = 0; i < blocks.size(); i++) {
            Block block = blocks.get(i);
            String className = "HowToBlock" + i;
            classNameToBlock.put(className, block);
            sources.add(sourceFile(className, buildSource(className, fileWideImports, block.bodyLines())));
        }

        // Then every recipe compiles against the real classpath
        List<String> errors = compileErrors(sources).stream()
                .map(d -> {
                    Block block = classNameToBlock.get(simpleClassNameOf(d.getSource()));
                    String location = block == null
                            ? "how-to.md"
                            : "how-to.md:" + block.startLine() + " (\"" + block.heading() + "\")";
                    return location + ": " + d.getMessage(null);
                })
                .toList();
        assertThat(errors).as("compile errors in docs/how-to.md").isEmpty();
    }

    // ── extraction ────────────────────────────────────────────────────────────

    /// One ```java fenced block: the markdown heading it appears under, its start line (for
    /// pointing a human at the source), and its content split into import lines vs. body lines.
    private record Block(String heading, int startLine, List<String> importLines, List<String> bodyLines) {
    }

    private static List<Block> extractJavaBlocks(Path doc) throws IOException {
        List<String> lines = Files.readAllLines(doc);
        List<Block> blocks = new ArrayList<>();
        String heading = "(preamble)";
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.startsWith("## ")) {
                heading = line.substring("## ".length()).trim();
                continue;
            }
            if (!line.equals("```java")) {
                continue;
            }
            int startLine = i + 1;
            List<String> importLines = new ArrayList<>();
            List<String> bodyLines = new ArrayList<>();
            int j = i + 1;
            while (j < lines.size() && !lines.get(j).equals("```")) {
                String content = lines.get(j);
                if (content.trim().startsWith("import ")) {
                    importLines.add(content.trim());
                } else {
                    bodyLines.add(content);
                }
                j++;
            }
            blocks.add(new Block(heading, startLine, importLines, bodyLines));
            i = j;
        }
        return blocks;
    }

    // ── synthetic source assembly ────────────────────────────────────────────

    private static String buildSource(String className, Set<String> imports, List<String> body) {
        StringBuilder source = new StringBuilder();
        source.append("package io.github.dfa1.vortex.integration.docscompiler;\n");
        for (String importLine : imports) {
            source.append(importLine).append('\n');
        }
        source.append("final class ").append(className).append(" {\n");
        source.append("    static void run() throws Exception {\n");
        for (String bodyLine : body) {
            source.append(bodyLine).append('\n');
        }
        source.append("    }\n}\n");
        return source.toString();
    }

    private static JavaFileObject sourceFile(String className, String content) {
        URI uri = URI.create("string:///io/github/dfa1/vortex/integration/docscompiler/" + className + ".java");
        return new SimpleJavaFileObject(uri, JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return content;
            }
        };
    }

    private static String simpleClassNameOf(JavaFileObject source) {
        String path = source.toUri().getPath();
        String fileName = path.substring(path.lastIndexOf('/') + 1);
        return fileName.substring(0, fileName.length() - ".java".length());
    }

    // ── compilation ───────────────────────────────────────────────────────────

    private static List<Diagnostic<? extends JavaFileObject>> compileErrors(List<JavaFileObject> sources)
            throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("system Java compiler (this test needs a JDK, not just a JRE)").isNotNull();

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        Path outDir = Files.createTempDirectory("docs-java-compiler");
        try (StandardJavaFileManager fileManager =
                     compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            fileManager.setLocation(StandardLocation.CLASS_OUTPUT, List.of(outDir.toFile()));
            List<String> options = List.of(
                    "--release", "25",
                    "-classpath", System.getProperty("java.class.path"),
                    "-proc:none");
            compiler.getTask(null, fileManager, diagnostics, options, null, sources).call();
        } finally {
            deleteRecursively(outDir);
        }

        return diagnostics.getDiagnostics().stream()
                .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                .toList();
    }

    private static void deleteRecursively(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }

    // ── repo location ─────────────────────────────────────────────────────────

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("CLAUDE.md"))) {
            dir = dir.getParent();
        }
        assertThat(dir).as("repo root (directory with CLAUDE.md)").isNotNull();
        return dir;
    }
}
