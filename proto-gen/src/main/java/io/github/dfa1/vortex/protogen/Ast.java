package io.github.dfa1.vortex.protogen;

import java.util.List;
import java.util.Optional;

/// Container for the `.proto` AST node records.
/// Nested types are kept here so consumers import a single class.
public final class Ast {

    private Ast() {
    }

    /// A parsed `.proto` source file.
    ///
    /// @param protoPackage  value of the `package` declaration, or empty
    /// @param javaPackage   value of `option java_package = "..."`, or empty
    /// @param imports       list of imported file paths (relative to include root)
    /// @param decls         top-level messages and enums declared in this file
    public record ProtoFile(String protoPackage, String javaPackage, List<String> imports, List<TopDecl> decls) {
    }

    /// Top-level declaration: either a message or an enum.
    public sealed interface TopDecl permits MessageDecl, EnumDecl {
    }

    /// A `message Foo { ... `} declaration.
    ///
    /// @param name     simple name (not qualified)
    /// @param members  fields, oneofs, nested messages, nested enums
    public record MessageDecl(String name, List<MessageMember> members) implements TopDecl, MessageMember {
    }

    /// A member of a {@link MessageDecl}.
    public sealed interface MessageMember permits FieldDecl, OneOfDecl, MessageDecl, EnumDecl {
    }

    /// A single field declaration inside a message or oneof.
    ///
    /// @param rule    singular / optional / repeated
    /// @param type    field type (scalar or ref)
    /// @param name    field name as written in the `.proto`
    /// @param number  field tag number
    /// @param packed  whether a repeated field has explicit `[packed=...]` option (`Optional.empty()` = default)
    public record FieldDecl(Rule rule, FieldType type, String name, int number, Optional<Boolean> packed) implements MessageMember {
    }

    /// A `oneof name { ... `} block. Member fields share a discriminator on the wire.
    ///
    /// @param name    oneof name
    /// @param fields  oneof member fields
    public record OneOfDecl(String name, List<FieldDecl> fields) implements MessageMember {
    }

    /// An `enum Foo { ... `} declaration.
    ///
    /// @param name    enum name
    /// @param values  enum values
    public record EnumDecl(String name, List<EnumValue> values) implements TopDecl, MessageMember {
    }

    /// An entry of an enum block.
    ///
    /// @param name    Java constant name
    /// @param number  wire value
    public record EnumValue(String name, int number) {
    }

    /// Field presence rule.
    public enum Rule { SINGLE, OPTIONAL, REPEATED }

    /// Field type variants — either a built-in scalar or a reference to a user-declared message/enum.
    public sealed interface FieldType permits Scalar, Ref {
    }

    /// Built-in proto3 scalar field types used by vortex.
    public enum Scalar implements FieldType {
        UINT32, UINT64, SINT64, INT32, BOOL, FLOAT, DOUBLE, STRING, BYTES
    }

    /// Reference to a user-declared type (message or enum). Resolved post-parse against all loaded files.
    ///
    /// @param typeName  the textual type reference as written (`"PType"`, `"vortex.dtype.DType"`, etc.)
    public record Ref(String typeName) implements FieldType {
    }
}
