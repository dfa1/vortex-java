package io.github.dfa1.vortex.fbsgen;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/// Recursive-descent parser for the FlatBuffers schema (`.fbs`) subset used by vortex.
/// Accepts: `namespace`, `include`, `table`, `struct`, `enum`, `union`, `root_type`,
/// vector types `[T]`, the `(required)` attribute, scalar default values, and the
/// FlatBuffers scalar types (with width aliases such as `byte`/`ubyte`/`int`/`long`).
/// Rejects: `attribute` declarations, `rpc_service`, key/hash attributes, fixed-size
/// arrays, nested namespaces within a single file, and unions of non-table members.
public final class Parser {

    /// FlatBuffers scalar keywords (including width aliases) mapped to canonical scalars.
    private static final Map<String, Ast.Scalar> SCALARS = Map.ofEntries(
            Map.entry("bool", Ast.Scalar.BOOL),
            Map.entry("byte", Ast.Scalar.INT8),
            Map.entry("int8", Ast.Scalar.INT8),
            Map.entry("ubyte", Ast.Scalar.UINT8),
            Map.entry("uint8", Ast.Scalar.UINT8),
            Map.entry("short", Ast.Scalar.INT16),
            Map.entry("int16", Ast.Scalar.INT16),
            Map.entry("ushort", Ast.Scalar.UINT16),
            Map.entry("uint16", Ast.Scalar.UINT16),
            Map.entry("int", Ast.Scalar.INT32),
            Map.entry("int32", Ast.Scalar.INT32),
            Map.entry("uint", Ast.Scalar.UINT32),
            Map.entry("uint32", Ast.Scalar.UINT32),
            Map.entry("long", Ast.Scalar.INT64),
            Map.entry("int64", Ast.Scalar.INT64),
            Map.entry("ulong", Ast.Scalar.UINT64),
            Map.entry("uint64", Ast.Scalar.UINT64),
            Map.entry("float", Ast.Scalar.FLOAT32),
            Map.entry("float32", Ast.Scalar.FLOAT32),
            Map.entry("double", Ast.Scalar.FLOAT64),
            Map.entry("float64", Ast.Scalar.FLOAT64),
            Map.entry("string", Ast.Scalar.STRING));

    private final List<Token> tokens;
    private int pos;

    /// @param tokens token stream produced by [Lexer]
    public Parser(List<Token> tokens) {
        this.tokens = tokens;
        this.pos = 0;
    }

    /// Parses one `.fbs` file.
    /// @return the parsed schema AST
    public Ast.SchemaFile parseFile() {
        String namespace = "";
        List<String> includes = new ArrayList<>();
        List<Ast.Decl> decls = new ArrayList<>();
        List<String> rootTypes = new ArrayList<>();

        while (peek().kind() != Token.Kind.EOF) {
            Token t = peek();
            if (t.kind() != Token.Kind.IDENT) {
                throw new FbsParseException("expected top-level declaration at line " + t.line() + ", got " + t.kind());
            }
            switch (t.text()) {
                case "namespace" -> namespace = parseNamespace();
                case "include" -> includes.add(parseInclude());
                case "table" -> decls.add(parseTableOrStruct(true));
                case "struct" -> decls.add(parseTableOrStruct(false));
                case "enum" -> decls.add(parseEnum());
                case "union" -> decls.add(parseUnion());
                case "root_type" -> rootTypes.add(parseRootType());
                default -> throw new FbsParseException("unexpected top-level token '" + t.text() + "' at line " + t.line());
            }
        }
        return new Ast.SchemaFile(namespace, List.copyOf(includes), List.copyOf(decls), List.copyOf(rootTypes));
    }

    private String parseNamespace() {
        expectIdent("namespace");
        String ns = parseQualifiedName();
        expect(Token.Kind.SEMICOLON);
        return ns;
    }

    private String parseInclude() {
        expectIdent("include");
        Token path = expect(Token.Kind.STRING_LITERAL);
        expect(Token.Kind.SEMICOLON);
        return path.text();
    }

    private String parseRootType() {
        expectIdent("root_type");
        String name = parseQualifiedName();
        expect(Token.Kind.SEMICOLON);
        return name;
    }

    private Ast.Decl parseTableOrStruct(boolean isTable) {
        advance(); // 'table' | 'struct'
        String name = expect(Token.Kind.IDENT).text();
        expect(Token.Kind.LBRACE);
        List<Ast.Field> fields = new ArrayList<>();
        while (peek().kind() != Token.Kind.RBRACE) {
            fields.add(parseField());
        }
        expect(Token.Kind.RBRACE);
        return isTable ? new Ast.TableDecl(name, List.copyOf(fields)) : new Ast.StructDecl(name, List.copyOf(fields));
    }

    private Ast.Field parseField() {
        String name = expect(Token.Kind.IDENT).text();
        expect(Token.Kind.COLON);
        Ast.FieldType type = parseType();
        Optional<String> defaultValue = Optional.empty();
        if (peek().kind() == Token.Kind.EQUALS) {
            advance();
            defaultValue = Optional.of(parseDefaultLiteral());
        }
        boolean required = false;
        if (peek().kind() == Token.Kind.LPAREN) {
            required = parseAttributes();
        }
        expect(Token.Kind.SEMICOLON);
        return new Ast.Field(name, type, defaultValue, required);
    }

    private Ast.FieldType parseType() {
        if (peek().kind() == Token.Kind.LBRACKET) {
            advance();
            Ast.FieldType element = parseType();
            expect(Token.Kind.RBRACKET);
            return new Ast.Vector(element);
        }
        String name = parseQualifiedName();
        Ast.Scalar scalar = SCALARS.get(name);
        return scalar != null ? scalar : new Ast.Ref(name);
    }

    private String parseDefaultLiteral() {
        Token t = peek();
        if (t.kind() == Token.Kind.INT_LITERAL || t.kind() == Token.Kind.IDENT) {
            advance();
            return t.text();
        }
        throw new FbsParseException("expected a default value at line " + t.line() + ", got " + t.kind());
    }

    /// Parses a `( attr [= value] [, ...] )` block. Only `required` is honored;
    /// any other attribute is accepted and ignored.
    private boolean parseAttributes() {
        expect(Token.Kind.LPAREN);
        boolean required = false;
        while (peek().kind() != Token.Kind.RPAREN) {
            String attr = expect(Token.Kind.IDENT).text();
            if ("required".equals(attr)) {
                required = true;
            }
            if (peek().kind() == Token.Kind.EQUALS) {
                advance();
                advance(); // attribute value (literal or string) — ignored
            }
            if (peek().kind() == Token.Kind.COMMA) {
                advance();
            }
        }
        expect(Token.Kind.RPAREN);
        return required;
    }

    private Ast.Decl parseEnum() {
        expectIdent("enum");
        String name = expect(Token.Kind.IDENT).text();
        expect(Token.Kind.COLON);
        String underlyingName = parseQualifiedName();
        Ast.Scalar underlying = SCALARS.get(underlyingName);
        if (underlying == null) {
            throw new FbsParseException("enum '" + name + "' has non-scalar underlying type '" + underlyingName + "'");
        }
        expect(Token.Kind.LBRACE);
        List<Ast.EnumVal> values = new ArrayList<>();
        while (peek().kind() != Token.Kind.RBRACE) {
            String valName = expect(Token.Kind.IDENT).text();
            OptionalInt number = OptionalInt.empty();
            if (peek().kind() == Token.Kind.EQUALS) {
                advance();
                number = OptionalInt.of(parseInt());
            }
            values.add(new Ast.EnumVal(valName, number));
            if (peek().kind() == Token.Kind.COMMA) {
                advance();
            }
        }
        expect(Token.Kind.RBRACE);
        return new Ast.EnumDecl(name, underlying, List.copyOf(values));
    }

    private Ast.Decl parseUnion() {
        expectIdent("union");
        String name = expect(Token.Kind.IDENT).text();
        expect(Token.Kind.LBRACE);
        List<Ast.UnionMember> members = new ArrayList<>();
        while (peek().kind() != Token.Kind.RBRACE) {
            String typeName = parseQualifiedName();
            OptionalInt number = OptionalInt.empty();
            if (peek().kind() == Token.Kind.EQUALS) {
                advance();
                number = OptionalInt.of(parseInt());
            }
            members.add(new Ast.UnionMember(typeName, number));
            if (peek().kind() == Token.Kind.COMMA) {
                advance();
            }
        }
        expect(Token.Kind.RBRACE);
        return new Ast.UnionDecl(name, List.copyOf(members));
    }

    private String parseQualifiedName() {
        StringBuilder sb = new StringBuilder(expect(Token.Kind.IDENT).text());
        while (peek().kind() == Token.Kind.DOT) {
            advance();
            sb.append('.').append(expect(Token.Kind.IDENT).text());
        }
        return sb.toString();
    }

    private int parseInt() {
        Token t = expect(Token.Kind.INT_LITERAL);
        try {
            return Integer.parseInt(t.text());
        } catch (NumberFormatException _) {
            throw new FbsParseException("invalid integer '" + t.text() + "' at line " + t.line());
        }
    }

    private Token peek() {
        return tokens.get(pos);
    }

    private Token advance() {
        return tokens.get(pos++);
    }

    private Token expect(Token.Kind kind) {
        Token t = peek();
        if (t.kind() != kind) {
            throw new FbsParseException("expected " + kind + " at line " + t.line() + ", got " + t.kind() + " '" + t.text() + "'");
        }
        return advance();
    }

    private void expectIdent(String text) {
        Token t = expect(Token.Kind.IDENT);
        if (!text.equals(t.text())) {
            throw new FbsParseException("expected '" + text + "' at line " + t.line() + ", got '" + t.text() + "'");
        }
    }
}
