package io.github.dfa1.vortex.protogen;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/// Walks a {@link TypeRegistry} and emits one {@code .java} file per message or enum.
///
/// Output:
/// - Each enum becomes a Java {@code enum} with bounds-checked {@code fromValue(int)}.
/// - Each message becomes an immutable Java {@code record} with a static
///   {@code decode(MemorySegment, long, long)} factory and an instance {@code encode()} method.
/// - All generated classes live in the same Java package, derived from the
///   {@code java_package} option of the first parsed file.
public final class CodeGen {

    private static final String PROTO_PKG = "io.github.dfa1.vortex.proto";

    /// Java reserved words + literals that cannot be used as identifiers.
    /// Field/parameter names that collide get a trailing underscore.
    private static final java.util.Set<String> JAVA_RESERVED = java.util.Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
            "class", "const", "continue", "default", "do", "double", "else", "enum",
            "extends", "final", "finally", "float", "for", "goto", "if", "implements",
            "import", "instanceof", "int", "interface", "long", "native", "new", "package",
            "private", "protected", "public", "return", "short", "static", "strictfp",
            "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try",
            "void", "volatile", "while", "true", "false", "null", "yield", "var", "record",
            "sealed", "permits", "non-sealed");

    static String safeName(String raw) {
        return JAVA_RESERVED.contains(raw) ? raw + "_" : raw;
    }

    private final TypeRegistry registry;

    /// @param registry resolved type registry
    public CodeGen(TypeRegistry registry) {
        this.registry = registry;
    }

    /// Generates one Java source file per type into {@code outDir}.
    ///
    /// @param outDir output directory (created if absent)
    /// @throws IOException on filesystem errors
    public void emit(Path outDir) throws IOException {
        Files.createDirectories(outDir);
        for (TypeRegistry.ResolvedType t : registry.all()) {
            // Skip google.protobuf.NullValue — already emitted as part of the well-known pre-seed.
            // Emit it once: the first time we see it.
            String src = switch (t) {
                case TypeRegistry.ResolvedType.Message m -> emitMessage(m);
                case TypeRegistry.ResolvedType.Enum e -> emitEnum(e);
            };
            Path target = outDir.resolve(t.javaName() + ".java");
            Files.writeString(target, src);
        }
    }

    private String emitEnum(TypeRegistry.ResolvedType.Enum e) {
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(PROTO_PKG).append(";\n\n");
        sb.append("/// Generated from proto3 enum {@code ").append(e.fqn()).append("}.\n");
        sb.append("/// Do not edit by hand — regenerate via {@code ./mvnw generate-sources -pl core -P regenerate-sources}.\n");
        sb.append("public enum ").append(e.javaName()).append(" {\n");
        List<Ast.EnumValue> values = e.decl().values();
        for (int i = 0; i < values.size(); i++) {
            Ast.EnumValue v = values.get(i);
            sb.append("    ").append(v.name()).append("(").append(v.number()).append(")");
            sb.append(i == values.size() - 1 ? ";\n\n" : ",\n");
        }
        sb.append("    private final int value;\n\n");
        sb.append("    ").append(e.javaName()).append("(int value) {\n");
        sb.append("        this.value = value;\n");
        sb.append("    }\n\n");
        sb.append("    /// @return the wire-format integer value\n");
        sb.append("    public int value() {\n");
        sb.append("        return value;\n");
        sb.append("    }\n\n");
        sb.append("    /// Resolves a wire-format integer back to its enum constant.\n");
        sb.append("    /// @param value wire-format integer\n");
        sb.append("    /// @return matching enum constant\n");
        sb.append("    /// @throws IllegalArgumentException if no constant matches\n");
        sb.append("    public static ").append(e.javaName()).append(" fromValue(int value) {\n");
        sb.append("        for (").append(e.javaName()).append(" v : values()) {\n");
        sb.append("            if (v.value == value) {\n");
        sb.append("                return v;\n");
        sb.append("            }\n");
        sb.append("        }\n");
        sb.append("        throw new IllegalArgumentException(\"unknown ").append(e.javaName()).append(" value: \" + value);\n");
        sb.append("    }\n");
        sb.append("}\n");
        return sb.toString();
    }

    private String emitMessage(TypeRegistry.ResolvedType.Message m) {
        List<Field> fields = flattenFields(m.decl());
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(PROTO_PKG).append(";\n\n");
        sb.append("import java.io.IOException;\n");
        sb.append("import java.lang.foreign.MemorySegment;\n\n");
        sb.append("/// Generated from proto3 message {@code ").append(m.fqn()).append("}.\n");
        sb.append("/// Do not edit by hand — regenerate via {@code ./mvnw generate-sources -pl core -P regenerate-sources}.\n");
        for (Field f : fields) {
            sb.append("/// @param ").append(f.name).append(" field tag ").append(f.number).append("\n");
        }
        sb.append("public record ").append(m.javaName()).append("(\n");
        for (int i = 0; i < fields.size(); i++) {
            Field f = fields.get(i);
            sb.append("        ").append(f.javaType).append(" ").append(f.name);
            sb.append(i == fields.size() - 1 ? "\n" : ",\n");
        }
        if (fields.isEmpty()) {
            // empty record
        }
        sb.append(") {\n\n");
        emitDecode(sb, m, fields);
        sb.append("\n");
        emitEncode(sb, fields);
        emitOneOfFactories(sb, m, fields);
        sb.append("}\n");
        return sb.toString();
    }

    /// Emits one static factory per oneof member, returning a record with that member set and all other components {@code null}.
    /// Avoids verbose constructor calls at consumer sites (e.g. {@code ScalarValue.ofInt64Value(123L)}
    /// instead of {@code new ScalarValue(null, null, 123L, null, null, null, null, null, null, null, null)}).
    private void emitOneOfFactories(StringBuilder sb, TypeRegistry.ResolvedType.Message m, List<Field> fields) {
        for (Ast.MessageMember mem : m.decl().members()) {
            if (!(mem instanceof Ast.OneOfDecl oneOf)) {
                continue;
            }
            for (Ast.FieldDecl decl : oneOf.fields()) {
                String safeFieldName = safeName(decl.name());
                int pos = -1;
                for (int i = 0; i < fields.size(); i++) {
                    if (fields.get(i).name.equals(safeFieldName)) {
                        pos = i;
                        break;
                    }
                }
                if (pos < 0) {
                    continue;
                }
                Field target = fields.get(pos);
                String factoryName = "of" + pascalCase(decl.name());
                sb.append("\n    /// Factory for oneof case {@code ").append(decl.name()).append("} (field tag ").append(decl.number()).append(").\n");
                sb.append("    /// @param value the value to set\n");
                sb.append("    /// @return a record with only the {@code ").append(decl.name()).append("} component set\n");
                sb.append("    public static ").append(m.javaName()).append(" ").append(factoryName)
                        .append("(").append(target.javaType).append(" value) {\n");
                sb.append("        return new ").append(m.javaName()).append("(");
                for (int i = 0; i < fields.size(); i++) {
                    sb.append(i == pos ? "value" : "null");
                    if (i < fields.size() - 1) {
                        sb.append(", ");
                    }
                }
                sb.append(");\n");
                sb.append("    }\n");
            }
        }
    }

    private static String pascalCase(String snake) {
        StringBuilder out = new StringBuilder(snake.length());
        boolean upper = true;
        for (int i = 0; i < snake.length(); i++) {
            char c = snake.charAt(i);
            if (c == '_') {
                upper = true;
            } else if (upper) {
                out.append(Character.toUpperCase(c));
                upper = false;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private void emitDecode(StringBuilder sb, TypeRegistry.ResolvedType.Message m, List<Field> fields) {
        sb.append("    /// Decodes a {@code ").append(m.fqn()).append("} from a slice of a memory segment.\n");
        sb.append("    /// @param __seg backing segment\n");
        sb.append("    /// @param __off start offset in bytes\n");
        sb.append("    /// @param __len payload length in bytes\n");
        sb.append("    /// @return decoded record\n");
        sb.append("    /// @throws IOException if the slice is malformed or truncated\n");
        sb.append("    public static ").append(m.javaName()).append(" decode(MemorySegment __seg, long __off, long __len) throws IOException {\n");
        sb.append("        ProtoReader r = new ProtoReader(__seg, __off, __len);\n");
        for (Field f : fields) {
            sb.append("        ").append(f.javaType).append(" ").append(f.name).append(" = ").append(f.initExpr).append(";\n");
        }
        sb.append("        while (r.hasMore()) {\n");
        sb.append("            int tag = r.readVarint32();\n");
        sb.append("            switch (tag >>> 3) {\n");
        for (Field f : fields) {
            sb.append("                case ").append(f.number).append(" -> {\n");
            f.emitDecode(sb, "                    ");
            sb.append("                }\n");
        }
        sb.append("                default -> r.skipField(tag & 7);\n");
        sb.append("            }\n");
        sb.append("        }\n");
        sb.append("        return new ").append(m.javaName()).append("(");
        for (int i = 0; i < fields.size(); i++) {
            sb.append(fields.get(i).name);
            if (i < fields.size() - 1) {
                sb.append(", ");
            }
        }
        sb.append(");\n");
        sb.append("    }\n");
    }

    private void emitEncode(StringBuilder sb, List<Field> fields) {
        sb.append("    /// Encodes this record to a proto3-wire-format byte array.\n");
        sb.append("    /// @return encoded bytes\n");
        sb.append("    public byte[] encode() {\n");
        sb.append("        ProtoWriter w = new ProtoWriter();\n");
        for (Field f : fields) {
            f.emitEncode(sb, "        ");
        }
        sb.append("        return w.toByteArray();\n");
        sb.append("    }\n");
    }

    /// Flattens a message's members (including oneof fields) into a single field list.
    /// Nested messages and enums are emitted separately via the registry walk.
    private List<Field> flattenFields(Ast.MessageDecl msg) {
        List<Field> out = new ArrayList<>();
        for (Ast.MessageMember mem : msg.members()) {
            switch (mem) {
                case Ast.FieldDecl f -> out.add(toField(f));
                case Ast.OneOfDecl o -> {
                    for (Ast.FieldDecl f : o.fields()) {
                        // OneOf members are always presence-tracked → treat as OPTIONAL.
                        out.add(toField(new Ast.FieldDecl(Ast.Rule.OPTIONAL, f.type(), f.name(), f.number(), f.packed())));
                    }
                }
                case Ast.MessageDecl ignored -> { /* nested, emitted separately */ }
                case Ast.EnumDecl ignored -> { /* nested, emitted separately */ }
            }
        }
        return out;
    }

    private Field toField(Ast.FieldDecl decl) {
        return Field.from(decl, registry);
    }

    /// Internal per-field model used by the emitter. Encapsulates Java type, init value,
    /// and emit logic for both decode and encode paths.
    private static final class Field {
        final String name;
        final int number;
        final String javaType;
        final String initExpr;
        private final Emitter emitter;

        private Field(String name, int number, String javaType, String initExpr, Emitter emitter) {
            this.name = name;
            this.number = number;
            this.javaType = javaType;
            this.initExpr = initExpr;
            this.emitter = emitter;
        }

        void emitDecode(StringBuilder sb, String indent) {
            emitter.emitDecode(sb, indent, this);
        }

        void emitEncode(StringBuilder sb, String indent) {
            emitter.emitEncode(sb, indent, this);
        }

        static Field from(Ast.FieldDecl decl, TypeRegistry reg) {
            return switch (decl.type()) {
                case Ast.Scalar s -> ofScalar(decl, s);
                case Ast.Ref ref -> ofRef(decl, ref, reg);
            };
        }

        private static Field ofScalar(Ast.FieldDecl decl, Ast.Scalar s) {
            String prim = switch (s) {
                case UINT32, INT32 -> "int";
                case UINT64, SINT64 -> "long";
                case BOOL -> "boolean";
                case FLOAT -> "float";
                case DOUBLE -> "double";
                case STRING -> "String";
                case BYTES -> "byte[]";
            };
            String boxed = switch (s) {
                case UINT32, INT32 -> "Integer";
                case UINT64, SINT64 -> "Long";
                case BOOL -> "Boolean";
                case FLOAT -> "Float";
                case DOUBLE -> "Double";
                case STRING -> "String";
                case BYTES -> "byte[]";
            };
            String name = safeName(decl.name());
            return switch (decl.rule()) {
                case SINGLE -> new Field(name, decl.number(), prim, defaultPrim(s),
                        new ScalarSingleEmitter(s));
                case OPTIONAL -> new Field(name, decl.number(), boxed, "null",
                        new ScalarOptionalEmitter(s));
                case REPEATED -> new Field(name, decl.number(), "java.util.List<" + boxed + ">", "new java.util.ArrayList<>()",
                        new ScalarRepeatedEmitter(s));
            };
        }

        private static Field ofRef(Ast.FieldDecl decl, Ast.Ref ref, TypeRegistry reg) {
            TypeRegistry.ResolvedType t = reg.resolve(ref, "");
            String javaName = t.javaName();
            String name = safeName(decl.name());
            return switch (t) {
                case TypeRegistry.ResolvedType.Enum e -> switch (decl.rule()) {
                    case SINGLE -> new Field(name, decl.number(), javaName,
                            javaName + ".fromValue(0)",
                            new EnumSingleEmitter(javaName));
                    case OPTIONAL -> new Field(name, decl.number(), javaName, "null",
                            new EnumOptionalEmitter(javaName));
                    case REPEATED -> new Field(name, decl.number(), "java.util.List<" + javaName + ">",
                            "new java.util.ArrayList<>()", new EnumRepeatedEmitter(javaName));
                };
                case TypeRegistry.ResolvedType.Message msg -> switch (decl.rule()) {
                    // Singular and Optional message refs are both nullable (proto3 message presence semantics).
                    case SINGLE, OPTIONAL -> new Field(name, decl.number(), javaName, "null",
                            new MessageOptionalEmitter(javaName));
                    case REPEATED -> new Field(name, decl.number(), "java.util.List<" + javaName + ">",
                            "new java.util.ArrayList<>()", new MessageRepeatedEmitter(javaName));
                };
            };
        }

        private static String defaultPrim(Ast.Scalar s) {
            return switch (s) {
                case BOOL -> "false";
                case STRING -> "\"\"";
                case BYTES -> "new byte[0]";
                case FLOAT -> "0f";
                case DOUBLE -> "0d";
                default -> "0";
            };
        }
    }

    private interface Emitter {
        void emitDecode(StringBuilder sb, String indent, Field f);

        void emitEncode(StringBuilder sb, String indent, Field f);
    }

    // ------------------------------------------------------------------
    // Scalar emitters
    // ------------------------------------------------------------------

    private static String readExpr(Ast.Scalar s) {
        return switch (s) {
            case UINT32, INT32 -> "r.readVarint32()";
            case UINT64 -> "r.readVarint64()";
            case SINT64 -> "r.readSint64()";
            case BOOL -> "r.readBool()";
            case FLOAT -> "r.readFloat()";
            case DOUBLE -> "r.readDouble()";
            case STRING -> "r.readString()";
            case BYTES -> "r.readBytes()";
        };
    }

    private static int wireType(Ast.Scalar s) {
        return switch (s) {
            case UINT32, UINT64, SINT64, INT32, BOOL -> 0;
            case FLOAT -> 5;
            case DOUBLE -> 1;
            case STRING, BYTES -> 2;
        };
    }

    private static String writeStmt(Ast.Scalar s, String value) {
        return switch (s) {
            case UINT32, INT32 -> "w.writeVarint32(" + value + ");";
            case UINT64 -> "w.writeVarint64(" + value + ");";
            case SINT64 -> "w.writeSint64(" + value + ");";
            case BOOL -> "w.writeBool(" + value + ");";
            case FLOAT -> "w.writeFloat(" + value + ");";
            case DOUBLE -> "w.writeDouble(" + value + ");";
            case STRING -> "w.writeString(" + value + ");";
            case BYTES -> "w.writeBytes(" + value + ");";
        };
    }

    private static String defaultCheckNonZero(Ast.Scalar s, String var) {
        return switch (s) {
            case BOOL -> var;
            case STRING -> "!" + var + ".isEmpty()";
            case BYTES -> var + ".length != 0";
            case FLOAT -> "Float.floatToRawIntBits(" + var + ") != 0";
            case DOUBLE -> "Double.doubleToRawLongBits(" + var + ") != 0L";
            case UINT32, INT32 -> var + " != 0";
            case UINT64, SINT64 -> var + " != 0L";
        };
    }

    private record ScalarSingleEmitter(Ast.Scalar s) implements Emitter {
        @Override
        public void emitDecode(StringBuilder sb, String indent, Field f) {
            sb.append(indent).append(f.name).append(" = ").append(readExpr(s)).append(";\n");
        }

        @Override
        public void emitEncode(StringBuilder sb, String indent, Field f) {
            sb.append(indent).append("if (").append(defaultCheckNonZero(s, f.name)).append(") {\n");
            sb.append(indent).append("    w.writeTag(").append(f.number).append(", ").append(wireType(s)).append(");\n");
            sb.append(indent).append("    ").append(writeStmt(s, f.name)).append("\n");
            sb.append(indent).append("}\n");
        }
    }

    private record ScalarOptionalEmitter(Ast.Scalar s) implements Emitter {
        @Override
        public void emitDecode(StringBuilder sb, String indent, Field f) {
            sb.append(indent).append(f.name).append(" = ").append(readExpr(s)).append(";\n");
        }

        @Override
        public void emitEncode(StringBuilder sb, String indent, Field f) {
            sb.append(indent).append("if (").append(f.name).append(" != null) {\n");
            sb.append(indent).append("    w.writeTag(").append(f.number).append(", ").append(wireType(s)).append(");\n");
            sb.append(indent).append("    ").append(writeStmt(s, f.name)).append("\n");
            sb.append(indent).append("}\n");
        }
    }

    private record ScalarRepeatedEmitter(Ast.Scalar s) implements Emitter {
        @Override
        public void emitDecode(StringBuilder sb, String indent, Field f) {
            int wt = wireType(s);
            // Packed primitives may arrive as either LEN-wrapped packed or unpacked tag-per-element.
            if (wt != 2) {
                sb.append(indent).append("int wt = tag & 7;\n");
                sb.append(indent).append("if (wt == 2) {\n");
                sb.append(indent).append("    int len = r.readVarint32();\n");
                sb.append(indent).append("    java.util.List<").append(boxedName(s)).append("> __target = ").append(f.name).append(";\n");
                sb.append(indent).append("    r.readPacked(len, reader -> __target.add(").append(readExprOnReader(s, "reader")).append("));\n");
                sb.append(indent).append("} else {\n");
                sb.append(indent).append("    ").append(f.name).append(".add(").append(readExpr(s)).append(");\n");
                sb.append(indent).append("}\n");
            } else {
                sb.append(indent).append(f.name).append(".add(").append(readExpr(s)).append(");\n");
            }
        }

        @Override
        public void emitEncode(StringBuilder sb, String indent, Field f) {
            int wt = wireType(s);
            if (wt != 2) {
                // Pack primitives into a single LEN region.
                sb.append(indent).append("if (!").append(f.name).append(".isEmpty()) {\n");
                sb.append(indent).append("    ProtoWriter packed = new ProtoWriter();\n");
                sb.append(indent).append("    for (").append(boxedName(s)).append(" __v : ").append(f.name).append(") {\n");
                sb.append(indent).append("        ").append(writeStmtOnWriter(s, "packed", "__v")).append("\n");
                sb.append(indent).append("    }\n");
                sb.append(indent).append("    byte[] __bytes = packed.toByteArray();\n");
                sb.append(indent).append("    w.writeTag(").append(f.number).append(", 2);\n");
                sb.append(indent).append("    w.writeEmbedded(__bytes);\n");
                sb.append(indent).append("}\n");
            } else {
                // LEN-type repeated: tag-per-element.
                sb.append(indent).append("for (").append(boxedName(s)).append(" __v : ").append(f.name).append(") {\n");
                sb.append(indent).append("    w.writeTag(").append(f.number).append(", 2);\n");
                sb.append(indent).append("    ").append(writeStmt(s, "__v")).append("\n");
                sb.append(indent).append("}\n");
            }
        }
    }

    private static String boxedName(Ast.Scalar s) {
        return switch (s) {
            case UINT32, INT32 -> "Integer";
            case UINT64, SINT64 -> "Long";
            case BOOL -> "Boolean";
            case FLOAT -> "Float";
            case DOUBLE -> "Double";
            case STRING -> "String";
            case BYTES -> "byte[]";
        };
    }

    private static String readExprOnReader(Ast.Scalar s, String reader) {
        return switch (s) {
            case UINT32, INT32 -> reader + ".readVarint32()";
            case UINT64 -> reader + ".readVarint64()";
            case SINT64 -> reader + ".readSint64()";
            case BOOL -> reader + ".readBool()";
            case FLOAT -> reader + ".readFloat()";
            case DOUBLE -> reader + ".readDouble()";
            case STRING -> reader + ".readString()";
            case BYTES -> reader + ".readBytes()";
        };
    }

    private static String writeStmtOnWriter(Ast.Scalar s, String writer, String value) {
        return switch (s) {
            case UINT32, INT32 -> writer + ".writeVarint32(" + value + ");";
            case UINT64 -> writer + ".writeVarint64(" + value + ");";
            case SINT64 -> writer + ".writeSint64(" + value + ");";
            case BOOL -> writer + ".writeBool(" + value + ");";
            case FLOAT -> writer + ".writeFloat(" + value + ");";
            case DOUBLE -> writer + ".writeDouble(" + value + ");";
            case STRING -> writer + ".writeString(" + value + ");";
            case BYTES -> writer + ".writeBytes(" + value + ");";
        };
    }

    // ------------------------------------------------------------------
    // Enum emitters (wire type = VARINT)
    // ------------------------------------------------------------------

    private record EnumSingleEmitter(String javaName) implements Emitter {
        @Override
        public void emitDecode(StringBuilder sb, String indent, Field f) {
            sb.append(indent).append(f.name).append(" = ").append(javaName).append(".fromValue(r.readVarint32());\n");
        }

        @Override
        public void emitEncode(StringBuilder sb, String indent, Field f) {
            sb.append(indent).append("if (").append(f.name).append(".value() != 0) {\n");
            sb.append(indent).append("    w.writeTag(").append(f.number).append(", 0);\n");
            sb.append(indent).append("    w.writeVarint32(").append(f.name).append(".value());\n");
            sb.append(indent).append("}\n");
        }
    }

    private record EnumOptionalEmitter(String javaName) implements Emitter {
        @Override
        public void emitDecode(StringBuilder sb, String indent, Field f) {
            sb.append(indent).append(f.name).append(" = ").append(javaName).append(".fromValue(r.readVarint32());\n");
        }

        @Override
        public void emitEncode(StringBuilder sb, String indent, Field f) {
            sb.append(indent).append("if (").append(f.name).append(" != null) {\n");
            sb.append(indent).append("    w.writeTag(").append(f.number).append(", 0);\n");
            sb.append(indent).append("    w.writeVarint32(").append(f.name).append(".value());\n");
            sb.append(indent).append("}\n");
        }
    }

    private record EnumRepeatedEmitter(String javaName) implements Emitter {
        @Override
        public void emitDecode(StringBuilder sb, String indent, Field f) {
            sb.append(indent).append("int wt = tag & 7;\n");
            sb.append(indent).append("if (wt == 2) {\n");
            sb.append(indent).append("    int len = r.readVarint32();\n");
            sb.append(indent).append("    java.util.List<").append(javaName).append("> __target = ").append(f.name).append(";\n");
            sb.append(indent).append("    r.readPacked(len, reader -> __target.add(").append(javaName).append(".fromValue(reader.readVarint32())));\n");
            sb.append(indent).append("} else {\n");
            sb.append(indent).append("    ").append(f.name).append(".add(").append(javaName).append(".fromValue(r.readVarint32()));\n");
            sb.append(indent).append("}\n");
        }

        @Override
        public void emitEncode(StringBuilder sb, String indent, Field f) {
            sb.append(indent).append("if (!").append(f.name).append(".isEmpty()) {\n");
            sb.append(indent).append("    ProtoWriter packed = new ProtoWriter();\n");
            sb.append(indent).append("    for (").append(javaName).append(" __v : ").append(f.name).append(") {\n");
            sb.append(indent).append("        packed.writeVarint32(__v.value());\n");
            sb.append(indent).append("    }\n");
            sb.append(indent).append("    w.writeTag(").append(f.number).append(", 2);\n");
            sb.append(indent).append("    w.writeEmbedded(packed.toByteArray());\n");
            sb.append(indent).append("}\n");
        }
    }

    // ------------------------------------------------------------------
    // Message emitters (wire type = LEN; nested message decode + encode)
    // ------------------------------------------------------------------

    private record MessageOptionalEmitter(String javaName) implements Emitter {
        @Override
        public void emitDecode(StringBuilder sb, String indent, Field f) {
            sb.append(indent).append("MemorySegment __slice = r.readLenDelimSegment();\n");
            sb.append(indent).append(f.name).append(" = ").append(javaName).append(".decode(__slice, 0, __slice.byteSize());\n");
        }

        @Override
        public void emitEncode(StringBuilder sb, String indent, Field f) {
            sb.append(indent).append("if (").append(f.name).append(" != null) {\n");
            sb.append(indent).append("    w.writeTag(").append(f.number).append(", 2);\n");
            sb.append(indent).append("    w.writeEmbedded(").append(f.name).append(".encode());\n");
            sb.append(indent).append("}\n");
        }
    }

    private record MessageRepeatedEmitter(String javaName) implements Emitter {
        @Override
        public void emitDecode(StringBuilder sb, String indent, Field f) {
            sb.append(indent).append("MemorySegment __slice = r.readLenDelimSegment();\n");
            sb.append(indent).append(f.name).append(".add(").append(javaName).append(".decode(__slice, 0, __slice.byteSize()));\n");
        }

        @Override
        public void emitEncode(StringBuilder sb, String indent, Field f) {
            sb.append(indent).append("for (").append(javaName).append(" __v : ").append(f.name).append(") {\n");
            sb.append(indent).append("    w.writeTag(").append(f.number).append(", 2);\n");
            sb.append(indent).append("    w.writeEmbedded(__v.encode());\n");
            sb.append(indent).append("}\n");
        }
    }
}
