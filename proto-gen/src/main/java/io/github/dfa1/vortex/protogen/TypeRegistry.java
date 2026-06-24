package io.github.dfa1.vortex.protogen;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/// Resolves type references across multiple parsed `.proto` files.
/// References to user-declared messages/enums may be written as short names (`PType`)
/// or as fully-qualified names (`vortex.dtype.PType`) — both must resolve to the same type.
public final class TypeRegistry {

    private final Map<String, ResolvedType> byFqn = new HashMap<>();

    /// Builds a registry covering every message and enum across `files`.
    /// Each top-level message gets registered under its qualified name `<package>.<MessageName>`
    /// (or just `<MessageName>` when the file has no package).
    /// Nested messages and enums are registered under `<parentFqn>.<NestedName>`.
    ///
    /// @param files parsed proto files
    public TypeRegistry(List<Ast.ProtoFile> files) {
        // Pre-seed google.protobuf.NullValue — only well-known type referenced by vortex schemas.
        // Tracked under both its qualified FQN and its short name for unambiguous resolution.
        String wellKnownJavaPkg = files.stream()
                .map(Ast.ProtoFile::javaPackage)
                .filter(s -> !s.isEmpty())
                .findFirst()
                .orElse("io.github.dfa1.vortex.proto");
        Ast.EnumDecl nullValueDecl = new Ast.EnumDecl(
                "NullValue", List.of(new Ast.EnumValue("NULL_VALUE", 0)));
        ResolvedType.Enum nullValue = new ResolvedType.Enum(nullValueDecl, "google.protobuf.NullValue", wellKnownJavaPkg);
        byFqn.put("google.protobuf.NullValue", nullValue);

        for (Ast.ProtoFile f : files) {
            String pkgPrefix = f.protoPackage().isEmpty() ? "" : f.protoPackage() + ".";
            for (Ast.TopDecl decl : f.decls()) {
                indexTopDecl(decl, pkgPrefix, f.javaPackage());
            }
        }
    }

    private void indexTopDecl(Ast.TopDecl decl, String pkgPrefix, String javaPackage) {
        switch (decl) {
            case Ast.MessageDecl m -> {
                String fqn = pkgPrefix + m.name();
                byFqn.put(fqn, new ResolvedType.Message(m, fqn, javaPackage));
                indexNested(m, fqn, javaPackage);
            }
            case Ast.EnumDecl e -> {
                String fqn = pkgPrefix + e.name();
                byFqn.put(fqn, new ResolvedType.Enum(e, fqn, javaPackage));
            }
        }
    }

    private void indexNested(Ast.MessageDecl parent, String parentFqn, String javaPackage) {
        for (Ast.MessageMember mem : parent.members()) {
            switch (mem) {
                case Ast.MessageDecl m -> {
                    String fqn = parentFqn + "." + m.name();
                    byFqn.put(fqn, new ResolvedType.Message(m, fqn, javaPackage));
                    indexNested(m, fqn, javaPackage);
                }
                case Ast.EnumDecl e -> {
                    String fqn = parentFqn + "." + e.name();
                    byFqn.put(fqn, new ResolvedType.Enum(e, fqn, javaPackage));
                }
                case Ast.FieldDecl _ -> { /* not a type declaration */ }
                case Ast.OneOfDecl _ -> { /* not a type declaration */ }
            }
        }
    }

    /// Resolves a textual [Ast.Ref#typeName()] to its concrete type, searching first
    /// for an exact qualified match, then attempting to match under `searchPackage`
    /// (the package of the file containing the reference).
    ///
    /// @param ref            the reference to resolve
    /// @param searchPackage  package of the containing file (for unqualified lookups)
    /// @return resolved type, never `null`
    public ResolvedType resolve(Ast.Ref ref, String searchPackage) {
        String name = ref.typeName();
        ResolvedType hit = byFqn.get(name);
        if (hit != null) {
            return hit;
        }
        if (!searchPackage.isEmpty()) {
            hit = byFqn.get(searchPackage + "." + name);
            if (hit != null) {
                return hit;
            }
        }
        // Suffix fallback: any FQN ending in ".<name>". Multiple matches must error
        // — otherwise resolution would silently depend on HashMap iteration order.
        String suffix = "." + name;
        List<Map.Entry<String, ResolvedType>> matches = new ArrayList<>();
        for (Map.Entry<String, ResolvedType> e : byFqn.entrySet()) {
            if (e.getKey().endsWith(suffix)) {
                matches.add(e);
            }
        }
        if (matches.size() == 1) {
            return matches.get(0).getValue();
        }
        if (matches.size() > 1) {
            List<String> fqns = new ArrayList<>(matches.size());
            for (Map.Entry<String, ResolvedType> e : matches) {
                fqns.add(e.getKey());
            }
            fqns.sort(String::compareTo);
            throw new ProtoParseException("ambiguous type reference: '" + name + "' matches " + fqns);
        }
        throw new ProtoParseException("unresolved type reference: '" + name + "'");
    }

    /// Snapshot of all registered types, for the generator to iterate.
    /// @return iterable of all entries
    public Iterable<ResolvedType> all() {
        return byFqn.values();
    }

    /// A resolved type — either a message or an enum — carrying enough context for code emission.
    public sealed interface ResolvedType {

        /// Prefix applied to every emitted Java class name so that the generated proto
        /// records (`DType`, `Bool`, `List`, …) do not collide with project or JDK types
        /// of the same name on the consumer classpath.
        String JAVA_NAME_PREFIX = "Proto";

        /// @return Java package for the emitted class
        String javaPackage();

        /// @return Java simple class name
        String javaName();

        /// Extracts the last dot-separated segment of a fully-qualified proto name.
        ///
        /// @param fqn fully-qualified proto name
        /// @return the trailing segment (the proto type's own name)
        private static String simpleName(String fqn) {
            int dot = fqn.lastIndexOf('.');
            return dot >= 0 ? fqn.substring(dot + 1) : fqn;
        }

        /// A resolved message type.
        ///
        /// @param decl         AST node
        /// @param fqn          fully-qualified proto name
        /// @param javaPackage  Java package
        record Message(Ast.MessageDecl decl, String fqn, String javaPackage) implements ResolvedType {
            @Override
            public String javaName() {
                return JAVA_NAME_PREFIX + simpleName(fqn);
            }
        }

        /// A resolved enum type.
        ///
        /// @param decl         AST node
        /// @param fqn          fully-qualified proto name
        /// @param javaPackage  Java package
        record Enum(Ast.EnumDecl decl, String fqn, String javaPackage) implements ResolvedType {
            @Override
            public String javaName() {
                return JAVA_NAME_PREFIX + simpleName(fqn);
            }
        }
    }
}
