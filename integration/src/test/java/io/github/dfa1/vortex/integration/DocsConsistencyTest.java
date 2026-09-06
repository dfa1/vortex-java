package io.github.dfa1.vortex.integration;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// The docs compiler: machine-checks the code-shaped claims in the LIVING documentation against
/// the actual codebase, so prose cannot silently drift from reality (the 2026-07-04 audit found
/// phantom APIs, dead service-file paths, and pre-refactor package FQNs — each one a claim this
/// test would have failed).
///
/// Living docs: `CLAUDE.md`, `README.md`, `docs/*.md`. Historical records are exempt by policy:
/// `adr/` and `CHANGELOG.md` describe the past and must not be rewritten to match the present.
///
/// Checks:
/// - every `io.github.dfa1.vortex...` FQN resolves to a real class (nested classes via `$`
///   fallback) or, for package-only FQNs, a real source directory,
/// - every `META-INF/services/<fqn>` mention names a service file that actually exists,
/// - every relative markdown link resolves to an existing file,
/// - every backticked `UpperCase.method(...)` claim whose receiver is a project class names a
///   method that exists on it (receivers outside the project must be on the known-external
///   allowlist — extend it consciously, in the diff, when citing a new external API).
///
/// This test reads a handful of local markdown files — milliseconds, no network, deterministic.
/// It lives in `integration` because every module is on the classpath here, so `Class.forName`
/// can see the whole project.
class DocsConsistencyTest {

    private static final Pattern FQN = Pattern.compile(
            "io\\.github\\.dfa1\\.vortex(?:\\.[a-z][a-z0-9]*+)*+(?:\\.[A-Z][A-Za-z0-9_]*+)*+");
    private static final Pattern SERVICE_FILE = Pattern.compile(
            "META-INF/services/(io\\.github\\.dfa1\\.vortex[A-Za-z0-9_.]+)");
    private static final Pattern MD_LINK = Pattern.compile(
            "]\\((?!https?:|#|mailto:)([^)#\\s]+)(?:#[^)\\s]*)?\\)");
    private static final Pattern METHOD_CLAIM = Pattern.compile(
            "`([A-Z][A-Za-z0-9_]*+)\\.([a-z][A-Za-z0-9_]*+)\\(");

    /// External receivers legitimately cited in docs. Additions belong in the same commit as
    /// the doc citing the new external API — a conscious, reviewable decision.
    private static final Set<String> KNOWN_EXTERNAL = Set.of(
            "String", "Map", "List", "Set", "Files", "Path", "Paths", "Optional", "Duration",
            "Instant", "LocalDate", "LocalTime", "UUID", "BigDecimal", "Arrays", "Collections",
            "ServiceLoader", "DriverManager", "Class", "Object", "Thread", "LockSupport",
            "System", "MemorySegment", "Arena", "ValueLayout", "FileChannel", "ByteBuffer",
            "Runtime", "Math", "Integer", "Long", "Double",
            // Hardwood parquet API, cited in the benchmark comparison (explanation.md) and the
            // ParquetExporter/ExportOptions reference (reference.md)
            "ColumnReader", "RowReader", "WriterConfig");

    private static Path repoRoot;
    private static List<Path> livingDocs;
    private static Map<String, List<String>> simpleNameToFqns;

    @BeforeAll
    static void locateRepo() throws IOException {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("CLAUDE.md"))) {
            dir = dir.getParent();
        }
        assertThat(dir).as("repo root (directory containing CLAUDE.md)").isNotNull();
        repoRoot = dir;

        livingDocs = new ArrayList<>();
        livingDocs.add(repoRoot.resolve("CLAUDE.md"));
        Path readme = repoRoot.resolve("README.md");
        if (Files.exists(readme)) {
            livingDocs.add(readme);
        }
        try (Stream<Path> docs = Files.list(repoRoot.resolve("docs"))) {
            docs.filter(p -> p.toString().endsWith(".md")).sorted().forEach(livingDocs::add);
        }

        simpleNameToFqns = indexProjectClasses();
    }

    @Test
    void fqnClaims_resolveToRealClassesOrPackages() throws IOException {
        // Given every io.github.dfa1.vortex FQN mentioned in the living docs
        var violations = new ArrayList<String>();

        // When each is resolved against the classpath (class) or source tree (package)
        for (Path doc : livingDocs) {
            String text = Files.readString(doc);
            Matcher m = FQN.matcher(text);
            while (m.find()) {
                String fqn = m.group();
                boolean isClassLike = Character.isUpperCase(fqn.charAt(fqn.lastIndexOf('.') + 1));
                if (isClassLike ? !resolvesAsClass(fqn) : !resolvesAsPackage(fqn)) {
                    violations.add(doc.getFileName() + ": " + fqn);
                }
            }
        }

        // Then every claim names something that exists
        assertThat(violations).as("stale FQNs in living docs").isEmpty();
    }

    @Test
    void serviceFileClaims_nameExistingServiceFiles() throws IOException {
        // Given every META-INF/services mention in the living docs
        var violations = new ArrayList<String>();

        // When each is checked against the modules' resource directories
        for (Path doc : livingDocs) {
            Matcher m = SERVICE_FILE.matcher(Files.readString(doc));
            while (m.find()) {
                String serviceFqn = m.group(1);
                if (findInAnyModule("src/main/resources/META-INF/services/" + serviceFqn).isEmpty()) {
                    violations.add(doc.getFileName() + ": META-INF/services/" + serviceFqn);
                }
            }
        }

        // Then no doc instructs registration through a service file that does not exist
        assertThat(violations).as("phantom service files in living docs").isEmpty();
    }

    @Test
    void relativeLinks_resolveToExistingFiles() throws IOException {
        // Given every relative markdown link in the living docs
        var violations = new ArrayList<String>();

        // When each is resolved against the linking document's directory
        for (Path doc : livingDocs) {
            Matcher m = MD_LINK.matcher(Files.readString(doc));
            while (m.find()) {
                Path target = doc.getParent().resolve(m.group(1)).normalize();
                if (!Files.exists(target)) {
                    violations.add(doc.getFileName() + ": ](" + m.group(1) + ")");
                }
            }
        }

        // Then no link rots when files move (the docs/adr → adr/ move rewrote ~40 by hand)
        assertThat(violations).as("broken relative links in living docs").isEmpty();
    }

    @Test
    void methodClaims_onProjectClasses_nameRealMethods() throws IOException {
        // Given every backticked `UpperCase.method(...)` claim in the living docs
        var violations = new ArrayList<String>();

        // When receivers are resolved: project classes must carry the named method; unknown
        // receivers must be consciously allowlisted external APIs
        for (Path doc : livingDocs) {
            Matcher m = METHOD_CLAIM.matcher(Files.readString(doc));
            while (m.find()) {
                String receiver = m.group(1);
                String method = m.group(2);
                List<String> fqns = simpleNameToFqns.get(receiver);
                if (fqns == null) {
                    if (!KNOWN_EXTERNAL.contains(receiver)) {
                        violations.add(doc.getFileName() + ": `" + receiver + "." + method
                                + "(...)` — receiver is neither a project class nor allowlisted");
                    }
                    continue;
                }
                boolean found = fqns.stream().anyMatch(fqn -> hasMethod(fqn, method));
                if (!found) {
                    violations.add(doc.getFileName() + ": `" + receiver + "." + method
                            + "(...)` — no such method on " + fqns);
                }
            }
        }

        // Then no doc cites an API that was renamed or removed
        assertThat(violations).as("stale method claims in living docs").isEmpty();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static boolean resolvesAsClass(String fqn) {
        // Progressively convert trailing dots to $ for nested classes (Chunk.Column,
        // EncodingId.WellKnown, DType.Struct).
        String candidate = fqn;
        while (true) {
            try {
                Class.forName(candidate, false, DocsConsistencyTest.class.getClassLoader());
                return true;
            } catch (ClassNotFoundException _) {
                int lastDot = candidate.lastIndexOf('.');
                if (lastDot < 0 || !Character.isUpperCase(candidate.charAt(lastDot + 1))) {
                    // also accept a class-like segment that is actually a package prefix mention
                    return false;
                }
                candidate = candidate.substring(0, lastDot) + "$" + candidate.substring(lastDot + 1);
                // Only the boundary between the outermost class and its nesting may vary; walk
                // one $ at a time from the right.
                if (!candidate.contains(".")) {
                    return false;
                }
            }
        }
    }

    private static boolean resolvesAsPackage(String fqn) {
        return !findInAnyModule("src/main/java/" + fqn.replace('.', '/')).isEmpty()
                || !findInAnyModule("src/test/java/" + fqn.replace('.', '/')).isEmpty();
    }

    private static List<Path> findInAnyModule(String relative) {
        try (Stream<Path> modules = Files.list(repoRoot)) {
            return modules
                    .filter(Files::isDirectory)
                    .map(module -> module.resolve(relative))
                    .filter(Files::exists)
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Map<String, List<String>> indexProjectClasses() throws IOException {
        var index = new HashMap<String, List<String>>();
        try (Stream<Path> modules = Files.list(repoRoot)) {
            for (Path module : modules.filter(Files::isDirectory).toList()) {
                Path src = module.resolve("src/main/java");
                if (!Files.exists(src)) {
                    continue;
                }
                try (Stream<Path> sources = Files.walk(src)) {
                    sources.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                        String simple = p.getFileName().toString().replace(".java", "");
                        String fqn = src.relativize(p).toString()
                                .replace(".java", "").replace('/', '.');
                        index.computeIfAbsent(simple, k -> new ArrayList<>()).add(fqn);
                    });
                }
            }
        }
        return index;
    }

    private static boolean hasMethod(String fqn, String method) {
        try {
            Class<?> cls = Class.forName(fqn, false, DocsConsistencyTest.class.getClassLoader());
            // Walk the hierarchy plus nested classes: docs cite builder methods as
            // `ReadRegistry.builder()...register(...)`, where register lives on the nested Builder.
            var toVisit = new ArrayList<Class<?>>();
            var seen = new HashSet<Class<?>>();
            toVisit.add(cls);
            while (!toVisit.isEmpty()) {
                Class<?> c = toVisit.removeLast();
                if (!seen.add(c)) {
                    continue;
                }
                for (var m : c.getDeclaredMethods()) {
                    if (m.getName().equals(method)) {
                        return true;
                    }
                }
                if (c.getSuperclass() != null) {
                    toVisit.add(c.getSuperclass());
                }
                toVisit.addAll(List.of(c.getInterfaces()));
                toVisit.addAll(List.of(c.getDeclaredClasses()));
            }
            return false;
        } catch (ClassNotFoundException | LinkageError _) {
            return false;
        }
    }
}
