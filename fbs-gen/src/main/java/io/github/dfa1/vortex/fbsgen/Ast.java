package io.github.dfa1.vortex.fbsgen;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/// Container for the `.fbs` AST node records.
/// Nested types are kept here so consumers import a single class.
public final class Ast {

    private Ast() {
    }

    /// A parsed `.fbs` schema file.
    ///
    /// @param namespace   value of the `namespace` declaration (dotted), or empty
    /// @param includes    list of `include "..."` file paths (relative to include root)
    /// @param decls       declared tables, structs, enums and unions, in source order
    /// @param rootTypes   names listed in `root_type` declarations
    public record SchemaFile(String namespace, List<String> includes, List<Decl> decls, List<String> rootTypes) {
    }

    /// A top-level declaration in a `.fbs` file.
    public sealed interface Decl permits TableDecl, StructDecl, EnumDecl, UnionDecl {

        /// @return the declared simple name
        String name();
    }

    /// A `table Foo { ... `} — heap-allocated, vtable-indexed, fields optional.
    ///
    /// @param name    simple name
    /// @param fields  field declarations in source order
    public record TableDecl(String name, List<Field> fields) implements Decl {
    }

    /// A `struct Foo { ... `} — fixed-size, inline, no vtable; every field is present.
    ///
    /// @param name    simple name
    /// @param fields  field declarations in source order
    public record StructDecl(String name, List<Field> fields) implements Decl {
    }

    /// An `enum Foo: underlying { ... `} declaration.
    ///
    /// @param name        simple name
    /// @param underlying  the underlying integer scalar (e.g. `uint8`)
    /// @param values      enum values in source order
    public record EnumDecl(String name, Scalar underlying, List<EnumVal> values) implements Decl {
    }

    /// A single enum value.
    ///
    /// @param name    constant name
    /// @param number  explicit wire value, or empty for auto-increment
    public record EnumVal(String name, OptionalInt number) {
    }

    /// A `union Foo { ... `} declaration. On the wire a union is a discriminator byte
    /// (`0` = NONE) plus an offset to the selected member table.
    ///
    /// @param name     simple name
    /// @param members  member types in source order
    public record UnionDecl(String name, List<UnionMember> members) implements Decl {
    }

    /// A single union member.
    ///
    /// @param typeName  referenced table type name
    /// @param number    explicit discriminator value, or empty for auto-increment (from 1)
    public record UnionMember(String typeName, OptionalInt number) {
    }

    /// A field declaration inside a table or struct.
    ///
    /// @param name          field name as written
    /// @param type          field type
    /// @param defaultValue  literal default as written (`null`, `0`, `true`, ...), or empty
    /// @param required      whether the field carries the `(required)` attribute
    public record Field(String name, FieldType type, Optional<String> defaultValue, boolean required) {
    }

    /// Field type variants — a scalar, a vector, or a reference to a declared type.
    public sealed interface FieldType permits Scalar, Vector, Ref {
    }

    /// FlatBuffers built-in scalar types. Schema aliases (`byte`, `ubyte`, `int`, ...)
    /// are normalized to these canonical, width-explicit constants by [Parser].
    public enum Scalar implements FieldType {
        BOOL,
        INT8,
        UINT8,
        INT16,
        UINT16,
        INT32,
        UINT32,
        INT64,
        UINT64,
        FLOAT32,
        FLOAT64,
        STRING
    }

    /// A `[element]` vector type.
    ///
    /// @param element  element type (scalar or reference; nested vectors are not used by vortex)
    public record Vector(FieldType element) implements FieldType {
    }

    /// Reference to a declared table, struct, enum or union. Resolved post-parse.
    ///
    /// @param typeName  the textual type reference as written
    public record Ref(String typeName) implements FieldType {
    }
}
