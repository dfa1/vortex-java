package io.github.dfa1.vortex.fbsgen;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/// Resolves type references across the parsed `.fbs` files and exposes layout facts
/// (scalar widths, struct sizes/alignment) the generator needs.
///
/// All vortex schemas share a single namespace, and type names are unique across
/// files, so references resolve by simple name (a leading namespace qualifier, if
/// written, is stripped to its last segment).
public final class TypeRegistry {

    private final String namespace;
    private final Map<String, Ast.Decl> byName = new HashMap<>();
    private final List<Ast.Decl> decls = new ArrayList<>();

    /// Builds a registry covering every declaration across `files`.
    ///
    /// @param files parsed schema files
    public TypeRegistry(List<Ast.SchemaFile> files) {
        String ns = "";
        for (Ast.SchemaFile f : files) {
            if (!f.namespace().isEmpty()) {
                ns = f.namespace();
            }
            for (Ast.Decl d : f.decls()) {
                byName.put(d.name(), d);
                decls.add(d);
            }
        }
        this.namespace = ns;
    }

    /// @return the common Java package (derived from the schema namespace)
    public String javaPackage() {
        return namespace;
    }

    /// @return all declarations across all files, in parse order
    public List<Ast.Decl> all() {
        return List.copyOf(decls);
    }

    /// Resolves a (possibly namespace-qualified) type reference.
    ///
    /// @param ref the textual reference
    /// @return the declaration it names
    public Ast.Decl resolve(Ast.Ref ref) {
        String name = simpleName(ref.typeName());
        Ast.Decl d = byName.get(name);
        if (d == null) {
            throw new FbsParseException("unresolved type reference: '" + ref.typeName() + "'");
        }
        return d;
    }

    private static String simpleName(String qualified) {
        int dot = qualified.lastIndexOf('.');
        return dot >= 0 ? qualified.substring(dot + 1) : qualified;
    }

    /// @param scalar a scalar type
    /// @return its wire size in bytes (a `string` is a 4-byte offset)
    public static int sizeOf(Ast.Scalar scalar) {
        return switch (scalar) {
            case BOOL, INT8, UINT8 -> 1;
            case INT16, UINT16 -> 2;
            case INT32, UINT32, FLOAT32, STRING -> 4;
            case INT64, UINT64, FLOAT64 -> 8;
        };
    }

    /// Computes the inline byte offset of each field within a struct plus the struct's
    /// total padded size, following FlatBuffers struct layout (each field aligned to its
    /// own size; struct size padded to the maximum field alignment).
    ///
    /// @param struct the struct declaration
    /// @return a record carrying per-field offsets (parallel to the field list) and total size
    public StructLayout layoutOf(Ast.StructDecl struct) {
        int[] offsets = new int[struct.fields().size()];
        int pos = 0;
        int maxAlign = 1;
        for (int i = 0; i < struct.fields().size(); i++) {
            int size = fieldScalarSize(struct.fields().get(i));
            pos = align(pos, size);
            offsets[i] = pos;
            pos += size;
            maxAlign = Math.max(maxAlign, size);
        }
        int total = align(pos, maxAlign);
        return new StructLayout(offsets, total, maxAlign);
    }

    /// Resolves the scalar backing a struct field — a direct scalar or an enum's
    /// underlying type. Nested struct fields are not used by vortex and are rejected.
    private int fieldScalarSize(Ast.Field field) {
        Ast.FieldType t = field.type();
        if (t instanceof Ast.Scalar s) {
            return sizeOf(s);
        }
        if (t instanceof Ast.Ref ref && resolve(ref) instanceof Ast.EnumDecl e) {
            return sizeOf(e.underlying());
        }
        throw new FbsParseException("unsupported struct field type for '" + field.name() + "'");
    }

    private static int align(int pos, int size) {
        return (pos + size - 1) / size * size;
    }

    /// Inline layout of a struct.
    ///
    /// @param fieldOffsets byte offset of each field, parallel to the struct's field list
    /// @param size         total padded struct size in bytes
    /// @param alignment    struct alignment (the maximum field alignment)
    public record StructLayout(int[] fieldOffsets, int size, int alignment) {
    }
}
